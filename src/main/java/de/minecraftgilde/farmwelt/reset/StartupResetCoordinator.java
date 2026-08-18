package de.minecraftgilde.farmwelt.reset;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** Holt überfällige Resets nach einer sicheren Startup-Verzögerung sequenziell nach. */
public final class StartupResetCoordinator {

    static final long STARTUP_DELAY_TICKS = 60L * 20L;

    private final Plugin plugin;
    private final GlobalRegionScheduler globalRegionScheduler;
    private final AutomaticResetScheduler automaticResetScheduler;
    private final Clock clock;
    private final Logger logger;

    private boolean started;
    private long lifecycleGeneration;
    private ScheduledTask startupTask;

    public StartupResetCoordinator(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            AutomaticResetScheduler automaticResetScheduler,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.globalRegionScheduler = Objects.requireNonNull(
                globalRegionScheduler,
                "globalRegionScheduler"
        );
        this.automaticResetScheduler = Objects.requireNonNull(
                automaticResetScheduler,
                "automaticResetScheduler"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(plugin.getLogger(), "plugin.getLogger()");
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        long generation = ++lifecycleGeneration;
        try {
            startupTask = globalRegionScheduler.runDelayed(
                    plugin,
                    task -> beginStartupCatchUp(generation, task),
                    STARTUP_DELAY_TICKS
            );
        } catch (RuntimeException exception) {
            started = false;
            lifecycleGeneration++;
            throw exception;
        }
    }

    public synchronized void stop() {
        if (!started && startupTask == null) {
            automaticResetScheduler.stop();
            return;
        }

        started = false;
        lifecycleGeneration++;
        if (startupTask != null) {
            startupTask.cancel();
            startupTask = null;
        }
        automaticResetScheduler.stop();
    }

    private void beginStartupCatchUp(long generation, ScheduledTask task) {
        synchronized (this) {
            if (!isCurrentLifecycle(generation)) {
                return;
            }
            if (startupTask == task) {
                startupTask = null;
            }
        }

        final List<String> overdueFarmworlds;
        try {
            overdueFarmworlds = automaticResetScheduler.evaluateDueStates(clock.instant())
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() == ResetDueState.DUE)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Automatische Reset-Nachholung konnte nicht vorbereitet werden.",
                    exception
            );
            finishStartupCatchUp(generation, null);
            return;
        }

        if (!overdueFarmworlds.isEmpty()) {
            int overdueCount = overdueFarmworlds.size();
            logger.info("Automatische Reset-Nachholung: " + overdueCount
                    + (overdueCount == 1
                            ? " überfällige Farmwelt gefunden."
                            : " überfällige Farmwelten gefunden."));
        }

        CompletableFuture<Void> catchUpSequence = CompletableFuture.completedFuture(null);
        for (String farmworldKey : overdueFarmworlds) {
            catchUpSequence = catchUpSequence.thenCompose(
                    ignored -> catchUpIfStillDue(farmworldKey, generation)
            );
        }
        catchUpSequence.whenComplete(
                (ignored, failure) -> finishStartupCatchUp(generation, failure)
        );
    }

    private CompletableFuture<Void> catchUpIfStillDue(
            String farmworldKey,
            long generation
    ) {
        if (!isCurrentLifecycleSynchronized(generation)) {
            return CompletableFuture.completedFuture(null);
        }

        final ResetDueState dueState;
        try {
            dueState = automaticResetScheduler.evaluateDueStates(clock.instant())
                    .getOrDefault(farmworldKey, ResetDueState.DISABLED);
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Fälligkeit für Startup-Nachholung der Farmwelt '" + farmworldKey
                            + "' konnte nicht geprüft werden.",
                    exception
            );
            return CompletableFuture.completedFuture(null);
        }

        if (dueState != ResetDueState.DUE) {
            return CompletableFuture.completedFuture(null);
        }

        final CompletableFuture<ResetResult> resetFuture;
        synchronized (this) {
            if (!isCurrentLifecycle(generation)) {
                return CompletableFuture.completedFuture(null);
            }
            logger.info("Überfälliger Reset für Farmwelt '" + farmworldKey
                    + "' wird nachgeholt.");
            resetFuture = automaticResetScheduler.startReset(farmworldKey);
        }

        // Fehler sind bereits durch Scheduler beziehungsweise Engine protokolliert und
        // dürfen die Nachholung der folgenden Farmwelten nicht verhindern.
        return resetFuture.handle((result, failure) -> null);
    }

    private void finishStartupCatchUp(long generation, Throwable failure) {
        if (failure != null) {
            logger.log(
                    Level.SEVERE,
                    "Automatische Reset-Nachholung konnte nicht vollständig verarbeitet werden.",
                    failure
            );
        }

        synchronized (this) {
            if (!isCurrentLifecycle(generation)) {
                return;
            }
            automaticResetScheduler.start();
        }
    }

    private synchronized boolean isCurrentLifecycleSynchronized(long generation) {
        return isCurrentLifecycle(generation);
    }

    private boolean isCurrentLifecycle(long generation) {
        return started && lifecycleGeneration == generation;
    }
}
