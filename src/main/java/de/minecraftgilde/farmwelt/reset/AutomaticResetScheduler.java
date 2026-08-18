package de.minecraftgilde.farmwelt.reset;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

/**
 * Prüft Reset-Pläne regelmäßig im globalen Folia-Kontext.
 *
 * <p>Diese Phase bewertet ausschließlich Fälligkeiten. Eine Ausführung von Resets gehört nicht
 * zu dieser Komponente.</p>
 */
public final class AutomaticResetScheduler {

    static final long CHECK_INTERVAL_TICKS = 60L * 20L;

    private final Plugin plugin;
    private final GlobalRegionScheduler globalRegionScheduler;
    private final FarmworldResetService resetService;
    private final ResetDueStateEvaluator dueStateEvaluator;
    private final Clock clock;

    private ScheduledTask scheduledTask;

    public AutomaticResetScheduler(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            FarmworldResetService resetService,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.globalRegionScheduler = Objects.requireNonNull(
                globalRegionScheduler,
                "globalRegionScheduler"
        );
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.dueStateEvaluator = new ResetDueStateEvaluator();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void start() {
        if (scheduledTask != null) {
            return;
        }

        scheduledTask = globalRegionScheduler.runAtFixedRate(
                plugin,
                ignored -> evaluateDueStates(clock.instant()),
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
}
