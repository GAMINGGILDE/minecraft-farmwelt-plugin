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
    private final ResetDueStateEvaluator dueStateEvaluator;
    private final Clock clock;
    private final Logger logger;

    private ScheduledTask scheduledTask;

    public AutomaticResetScheduler(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            FarmworldResetService resetService,
            FarmworldResetExecutor resetExecutor,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.globalRegionScheduler = Objects.requireNonNull(
                globalRegionScheduler,
                "globalRegionScheduler"
        );
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.resetExecutor = Objects.requireNonNull(resetExecutor, "resetExecutor");
        this.dueStateEvaluator = new ResetDueStateEvaluator();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(plugin.getLogger(), "plugin.getLogger()");
    }

    public synchronized void start() {
        if (scheduledTask != null) {
            return;
        }

        scheduledTask = globalRegionScheduler.runAtFixedRate(
                plugin,
                ignored -> runScheduledCheck(),
                CHECK_INTERVAL_TICKS,
                CHECK_INTERVAL_TICKS
        );
    }

    public synchronized void stop() {
        if (scheduledTask == null) {
            return;
        }

        scheduledTask.cancel();
        scheduledTask = null;
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

    private void runScheduledCheck() {
        try {
            startDueResets(clock.instant());
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Automatische Reset-Fälligkeiten konnten nicht vollständig geprüft werden.",
                    exception
            );
        }
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
