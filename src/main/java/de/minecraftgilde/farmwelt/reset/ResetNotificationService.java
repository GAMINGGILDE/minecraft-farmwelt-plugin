package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Wertet die aktuell geladenen Notification-Snapshots aus und versendet Countdown-Warnungen.
 */
public final class ResetNotificationService {

    private final FarmworldResetService resetService;
    private final ResetWarningTracker warningTracker;
    private final ResetNotificationAudience audience;
    private final ResetNotificationMessageFormatter messageFormatter;
    private final Logger logger;

    public ResetNotificationService(
            FarmworldResetService resetService,
            ResetWarningTracker warningTracker,
            ResetNotificationAudience audience,
            ZoneId zoneId,
            Logger logger
    ) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.warningTracker = Objects.requireNonNull(warningTracker, "warningTracker");
        this.audience = Objects.requireNonNull(audience, "audience");
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
}
