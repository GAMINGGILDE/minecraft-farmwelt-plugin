package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AutomaticResetSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void startIsIdempotentAndUsesSixtySecondGlobalTask() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        AutomaticResetScheduler scheduler = createScheduler(globalScheduler);

        scheduler.start();
        scheduler.start();

        assertEquals(1, globalScheduler.scheduledTasks);
        assertEquals(AutomaticResetScheduler.CHECK_INTERVAL_TICKS, globalScheduler.initialDelay);
        assertEquals(AutomaticResetScheduler.CHECK_INTERVAL_TICKS, globalScheduler.period);
        assertTrue(globalScheduler.task.isRepeatingTask());
    }

    @Test
    void stopCancelsOnlyOwnedTaskAndAllowsCleanRestart() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        AutomaticResetScheduler scheduler = createScheduler(globalScheduler);

        scheduler.start();
        RecordingScheduledTask firstTask = globalScheduler.task;
        scheduler.stop();
        scheduler.stop();

        assertTrue(firstTask.isCancelled());
        assertEquals(1, firstTask.cancelCalls);
        assertEquals(0, globalScheduler.cancelAllCalls);

        scheduler.start();

        assertEquals(2, globalScheduler.scheduledTasks);
        assertFalse(globalScheduler.task.isCancelled());
    }

    @Test
    void evaluatesEveryConfiguredFarmworldIndependently() {
        ResetStateRepository repository = new FixedResetStateRepository(Map.of(
                "overworld",
                new FarmworldResetState("overworld", Optional.empty(), NOW),
                "nether",
                new FarmworldResetState("nether", Optional.empty(), NOW.plusSeconds(1))
        ));
        FarmworldResetService resetService = new FarmworldResetService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                quietLogger()
        );
        resetService.reload(List.of(
                config("overworld", true),
                config("nether", true),
                config("end", false)
        ));
        AutomaticResetScheduler scheduler = new AutomaticResetScheduler(
                plugin(),
                new RecordingGlobalRegionScheduler(),
                resetService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertEquals(Map.of(
                "overworld", ResetDueState.DUE,
                "nether", ResetDueState.NOT_DUE,
                "end", ResetDueState.DISABLED
        ), scheduler.evaluateDueStates(NOW));
    }

    private AutomaticResetScheduler createScheduler(
            RecordingGlobalRegionScheduler globalScheduler
    ) {
        FarmworldResetService resetService = new FarmworldResetService(
                new EmptyResetStateRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                quietLogger()
        );
        return new AutomaticResetScheduler(
                plugin(),
                globalScheduler,
                resetService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private FarmworldResetConfig config(String farmworldKey, boolean enabled) {
        return new FarmworldResetConfig(
                farmworldKey,
                "test_" + farmworldKey,
                enabled,
                Duration.ofDays(30)
        );
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "FarmweltTest";
                    case "toString" -> "FarmweltTestPlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private Logger quietLogger() {
        Logger logger = Logger.getLogger("AutomaticResetSchedulerTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static final class EmptyResetStateRepository implements ResetStateRepository {

        @Override
        public Map<String, FarmworldResetState> load() {
            return Map.of();
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
        }
    }

    private static final class FixedResetStateRepository implements ResetStateRepository {

        private final Map<String, FarmworldResetState> states;

        private FixedResetStateRepository(Map<String, FarmworldResetState> states) {
            this.states = states;
        }

        @Override
        public Map<String, FarmworldResetState> load() {
            return states;
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
        }
    }

    private static final class RecordingGlobalRegionScheduler implements GlobalRegionScheduler {

        private int scheduledTasks;
        private int cancelAllCalls;
        private long initialDelay;
        private long period;
        private RecordingScheduledTask task;

        @Override
        public void execute(Plugin plugin, Runnable run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long delayTicks
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long initialDelayTicks,
                long periodTicks
        ) {
            scheduledTasks++;
            initialDelay = initialDelayTicks;
            period = periodTicks;
            this.task = new RecordingScheduledTask(plugin);
            return this.task;
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            cancelAllCalls++;
        }
    }

    private static final class RecordingScheduledTask implements ScheduledTask {

        private final Plugin plugin;
        private int cancelCalls;
        private boolean cancelled;

        private RecordingScheduledTask(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return true;
        }

        @Override
        public CancelledState cancel() {
            cancelCalls++;
            if (cancelled) {
                return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
            }
            cancelled = true;
            return CancelledState.NEXT_RUNS_CANCELLED;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }
}
