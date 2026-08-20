package de.minecraftgilde.farmwelt.reset;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** Prüft Reset-Pläne im globalen Folia-Kontext und stößt fällige Resets zentral an. */
public final class AutomaticResetScheduler {

    static final long CHECK_INTERVAL_TICKS = 60L * 20L;

    private final Plugin plugin;
    private final GlobalRegionScheduler globalRegionScheduler;
    private final FarmworldResetService resetService;
    private final FarmworldResetExecutor resetExecutor;
    private final ResetNotificationService notificationService;
    private final ResetDueStateEvaluator dueStateEvaluator;
    private final Clock clock;
    private final Logger logger;

    private ScheduledTask scheduledTask;
    private long lifecycleGeneration;

    public AutomaticResetScheduler(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            FarmworldResetService resetService,
            FarmworldResetExecutor resetExecutor,
            ResetNotificationService notificationService,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.globalRegionScheduler = Objects.requireNonNull(
                globalRegionScheduler,
                "globalRegionScheduler"
        );
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.resetExecutor = Objects.requireNonNull(resetExecutor, "resetExecutor");
        this.notificationService = Objects.requireNonNull(
                notificationService,
                "notificationService"
        );
        this.dueStateEvaluator = new ResetDueStateEvaluator();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(plugin.getLogger(), "plugin.getLogger()");
    }

    public synchronized void start() {
        if (scheduledTask != null) {
            return;
        }

        long generation = ++lifecycleGeneration;
        try {
            scheduledTask = Objects.requireNonNull(
                    globalRegionScheduler.runAtFixedRate(
                            plugin,
                            ignored -> runScheduledCheck(generation),
                            CHECK_INTERVAL_TICKS,
                            CHECK_INTERVAL_TICKS
                    ),
                    "globalRegionScheduler.runAtFixedRate(...)"
            );
        } catch (RuntimeException exception) {
            lifecycleGeneration++;
            throw exception;
        }
    }

    public synchronized void stop() {
        if (scheduledTask == null) {
            return;
        }

        ScheduledTask task = scheduledTask;
        scheduledTask = null;
        lifecycleGeneration++;
        try {
            task.cancel();
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Periodischer Reset-Scheduler konnte nicht sauber gestoppt werden.",
                    exception
            );
        }
    }

    Map<String, ResetDueState> evaluateDueStates(Instant now) {
        Objects.requireNonNull(now, "now");
        Map<String, ResetDueState> dueStates = new LinkedHashMap<>();
        for (FarmworldResetConfig configuration : resetService.getConfiguredWorlds()) {
            dueStates.put(
                    configuration.farmworldKey(),
                    dueStateEvaluator.evaluate(
                            configuration,
                            resetService.getState(configuration.farmworldKey()),
                            now
                    )
            );
        }
        return Collections.unmodifiableMap(dueStates);
    }

    void startDueResets(Instant now) {
        Map<String, ResetDueState> dueStates = evaluateDueStates(now);
        for (Map.Entry<String, ResetDueState> entry : dueStates.entrySet()) {
            if (entry.getValue() != ResetDueState.DUE) {
                continue;
            }
            startReset(entry.getKey());
        }
    }

    private void runScheduledCheck(long generation) {
        if (!isCurrentLifecycle(generation)) {
            return;
        }

        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Zeitpunkt für automatische Reset-Prüfung konnte nicht ermittelt werden.",
                    exception
            );
            return;
        }

        try {
            notificationService.broadcastDueWarnings(now);
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Countdown-Warnungen konnten nicht vollständig geprüft werden.",
                    exception
            );
        }

        if (!isCurrentLifecycle(generation)) {
            return;
        }

        try {
            startDueResets(now, generation);
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Automatische Reset-Fälligkeiten konnten nicht vollständig geprüft werden.",
                    exception
            );
        }
    }

    private void startDueResets(Instant now, long generation) {
        Map<String, ResetDueState> dueStates = evaluateDueStates(now);
        for (Map.Entry<String, ResetDueState> entry : dueStates.entrySet()) {
            if (entry.getValue() != ResetDueState.DUE) {
                continue;
            }
            synchronized (this) {
                if (!isCurrentLifecycle(generation)) {
                    return;
                }
                startReset(entry.getKey());
            }
        }
    }

    private synchronized boolean isCurrentLifecycle(long generation) {
        return scheduledTask != null && lifecycleGeneration == generation;
    }

    CompletableFuture<ResetResult> startReset(String farmworldKey) {
        try {
            CompletableFuture<ResetResult> resetFuture = Objects.requireNonNull(
                    resetExecutor.reset(farmworldKey),
                    "resetExecutor.reset(...)"
            );
            return resetFuture.whenComplete((result, failure) -> handleResult(
                    farmworldKey,
                    result,
                    failure
            ));
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Automatischer Reset für Farmwelt '" + farmworldKey
                            + "' konnte nicht gestartet werden.",
                    exception
            );
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void handleResult(String farmworldKey, ResetResult result, Throwable failure) {
        if (failure != null) {
            logger.log(
                    Level.SEVERE,
                    "Automatischer Reset für Farmwelt '" + farmworldKey
                            + "' wurde mit einer Exception beendet.",
                    failure
            );
            return;
        }
        if (result == null) {
            logger.severe("Automatischer Reset für Farmwelt '" + farmworldKey
                    + "' lieferte kein Ergebnis.");
            return;
        }

        if (result.status() == ResetStatus.SUCCESS) {
            logger.info("Automatischer Reset für Farmwelt '" + farmworldKey
                    + "' erfolgreich abgeschlossen.");
            return;
        }
        if (result.status() == ResetStatus.ALREADY_RUNNING) {
            return;
        }

        String message = "Automatischer Reset für Farmwelt '" + farmworldKey
                + "' fehlgeschlagen: " + result.status() + " - " + result.message();
        if (result.cause() == null) {
            logger.warning(message);
        } else {
            logger.log(Level.WARNING, message, result.cause());
        }
    }
}
