package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.entity.Player;

/** Wertet Notification-Snapshots aus und versendet Reset-Nachrichten best-effort. */
public final class ResetNotificationService {

    private final FarmworldResetService resetService;
    private final ResetWarningTracker warningTracker;
    private final ResetNotificationAudience audience;
    private final ResetPlayerNotificationAudience playerAudience;
    private final ResetNotificationMessageFormatter messageFormatter;
    private final Logger logger;

    public ResetNotificationService(
            FarmworldResetService resetService,
            ResetWarningTracker warningTracker,
            ResetNotificationAudience audience,
            ResetPlayerNotificationAudience playerAudience,
            ZoneId zoneId,
            Logger logger
    ) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.warningTracker = Objects.requireNonNull(warningTracker, "warningTracker");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.playerAudience = Objects.requireNonNull(playerAudience, "playerAudience");
        this.messageFormatter = new ResetNotificationMessageFormatter(zoneId);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Optional<ResetNotificationConfig> getConfig(String farmworldKey) {
        return resetService.getConfig(farmworldKey).map(FarmworldResetConfig::notifications);
    }

    /** Räumt bei Reloads entfernte oder deaktivierte Warning-Zustände unmittelbar auf. */
    public void reload() {
        warningTracker.retainFarmworlds(activeFarmworldKeys(
                resetService.getConfiguredWorlds()
        ));
    }

    public void broadcastDueWarnings(Instant now) {
        Objects.requireNonNull(now, "now");
        Collection<FarmworldResetConfig> configurations = resetService.getConfiguredWorlds();
        warningTracker.retainFarmworlds(activeFarmworldKeys(configurations));

        for (FarmworldResetConfig configuration : configurations) {
            if (!isActive(configuration)) {
                continue;
            }
            try {
                broadcastDueWarnings(configuration, now);
            } catch (RuntimeException exception) {
                logger.log(
                        Level.SEVERE,
                        "Countdown-Warnungen für Farmwelt '"
                                + configuration.farmworldKey()
                                + "' konnten nicht ausgewertet werden.",
                        exception
                );
            }
        }
    }

    public void broadcastResetStart(FarmworldResetConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        broadcastLifecycleMessage(
                configuration,
                configuration.notifications().resetStart(),
                "Reset-Startmeldung"
        );
    }

    public void broadcastResetSuccess(FarmworldResetConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        broadcastLifecycleMessage(
                configuration,
                configuration.notifications().resetSuccess(),
                "Reset-Erfolgsmeldung"
        );
    }

    public void broadcastResetFailure(FarmworldResetConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        broadcastLifecycleMessage(
                configuration,
                configuration.notifications().resetFailure(),
                "Reset-Fehlermeldung"
        );
    }

    public void sendEvacuationMessage(
            FarmworldResetConfig configuration,
            Player player
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(player, "player");
        ResetNotificationMessageConfig messageConfig = configuration.notifications().evacuation();
        if (!configuration.enabled()
                || !configuration.notifications().enabled()
                || !messageConfig.enabled()) {
            return;
        }

        try {
            Optional<Instant> nextReset = resetService.getState(configuration.farmworldKey())
                    .map(FarmworldResetState::nextReset);
            String message = messageFormatter.formatLifecycle(
                    messageConfig.message(),
                    configuration.displayName(),
                    nextReset
            );
            CompletableFuture<Void> delivery = Objects.requireNonNull(
                    playerAudience.send(player, message),
                    "playerAudience.send(...)"
            );
            delivery.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    logEvacuationFailure(configuration, unwrap(failure));
                }
            });
        } catch (RuntimeException exception) {
            logEvacuationFailure(configuration, exception);
        }
    }

    /**
     * Ordnet nur tatsächlich gestartete Reset-Ergebnisse einer Abschlussmeldung zu.
     * Fachlich abgewiesene Aufrufe erzeugen keine Lifecycle-Nachricht.
     */
    public void broadcastResetResult(
            FarmworldResetConfig configuration,
            ResetResult result
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(result, "result");
        switch (result.status()) {
            case SUCCESS -> broadcastResetSuccess(configuration);
            case NOT_CONFIGURED, DISABLED, ALREADY_RUNNING -> {
                // Diese Status entstehen vor dem eigentlichen Reset-Start.
            }
            default -> broadcastResetFailure(configuration);
        }
    }

    private void broadcastDueWarnings(FarmworldResetConfig configuration, Instant now) {
        Optional<FarmworldResetState> state = resetService.getState(
                configuration.farmworldKey()
        );
        if (state.isEmpty()) {
            return;
        }

        Instant nextReset = state.orElseThrow().nextReset();
        List<Duration> dueWarnings = warningTracker.evaluate(
                configuration.farmworldKey(),
                nextReset,
                now,
                configuration.notifications().warnings()
        );
        for (Duration warning : dueWarnings) {
            String message = messageFormatter.formatWarning(
                    configuration.notifications().warningMessage(),
                    configuration.displayName(),
                    warning,
                    nextReset
            );
            try {
                audience.broadcast(message);
            } catch (RuntimeException exception) {
                logger.log(
                        Level.SEVERE,
                        "Countdown-Warnung '" + warning + "' für Farmwelt '"
                                + configuration.farmworldKey()
                                + "' konnte nicht versendet werden.",
                        exception
                );
            }
        }
    }

    private void broadcastLifecycleMessage(
            FarmworldResetConfig configuration,
            ResetNotificationMessageConfig messageConfig,
            String messageType
    ) {
        if (!configuration.enabled()
                || !configuration.notifications().enabled()
                || !messageConfig.enabled()) {
            return;
        }

        try {
            Optional<Instant> nextReset = resetService.getState(configuration.farmworldKey())
                    .map(FarmworldResetState::nextReset);
            String message = messageFormatter.formatLifecycle(
                    messageConfig.message(),
                    configuration.displayName(),
                    nextReset
            );
            audience.broadcast(message);
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    messageType + " für Farmwelt '" + configuration.farmworldKey()
                            + "' konnte nicht versendet werden.",
                    exception
            );
        }
    }

    private Set<String> activeFarmworldKeys(
            Collection<FarmworldResetConfig> configurations
    ) {
        return configurations.stream()
                .filter(this::isActive)
                .map(FarmworldResetConfig::farmworldKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isActive(FarmworldResetConfig configuration) {
        return configuration.enabled()
                && configuration.notifications().enabled()
                && !configuration.notifications().warnings().isEmpty();
    }

    private void logEvacuationFailure(
            FarmworldResetConfig configuration,
            Throwable failure
    ) {
        logger.log(
                Level.SEVERE,
                "Evakuierungsnachricht für Farmwelt '"
                        + configuration.farmworldKey()
                        + "' konnte nicht versendet werden.",
                failure
        );
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
