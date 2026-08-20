package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class StartupResetCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void notDueFarmworldIsNotResetDuringStartup() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        StartupResetCoordinator coordinator = coordinator(
                globalScheduler,
                resetService(
                        List.of(config("overworld", true)),
                        Map.of("overworld", state("overworld", NOW.plusSeconds(1)))
                ),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of(), executor.calls);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void oneOverdueFarmworldIsResetExactlyOnce() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", true)),
                Map.of("overworld", state("overworld", NOW)),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of("overworld"), executor.calls);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void disabledFarmworldWithOverdueStateIsNotReset() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", false)),
                Map.of("overworld", state("overworld", NOW.minusSeconds(1))),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of(), executor.calls);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void multipleOverdueFarmworldsAreResetSequentiallyInConfigurationOrder() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        Map<String, CompletableFuture<ResetResult>> resetResults = Map.of(
                "overworld", new CompletableFuture<>(),
                "nether", new CompletableFuture<>(),
                "end", new CompletableFuture<>()
        );
        RecordingResetExecutor executor = new RecordingResetExecutor(resetResults::get);
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(
                        config("overworld", true),
                        config("nether", true),
                        config("end", true)
                ),
                dueStates("overworld", "nether", "end"),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of("overworld"), executor.calls);
        assertEquals(0, globalScheduler.repeatingTasks);

        resetResults.get("overworld").complete(successfulResult("overworld").join());
        assertEquals(List.of("overworld", "nether"), executor.calls);
        assertEquals(0, globalScheduler.repeatingTasks);

        resetResults.get("nether").complete(successfulResult("nether").join());
        assertEquals(List.of("overworld", "nether", "end"), executor.calls);
        assertEquals(0, globalScheduler.repeatingTasks);

        resetResults.get("end").complete(successfulResult("end").join());
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void failedResetDoesNotBlockFollowingFarmworld() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey -> {
            if (farmworldKey.equals("overworld")) {
                return CompletableFuture.completedFuture(result(
                        farmworldKey,
                        ResetStatus.REGENERATE_FAILED,
                        "Worlds-Fehler",
                        new IllegalStateException("Testfehler")
                ));
            }
            if (farmworldKey.equals("nether")) {
                throw new IllegalStateException("Synchroner Testfehler");
            }
            return successfulResult(farmworldKey);
        });
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(
                        config("overworld", true),
                        config("nether", true),
                        config("end", true)
                ),
                dueStates("overworld", "nether", "end"),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of("overworld", "nether", "end"), executor.calls);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void alreadyRunningIsSilentAndDoesNotBlockFollowingFarmworld() {
        RecordingLogHandler logHandler = new RecordingLogHandler();
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey -> {
            if (farmworldKey.equals("overworld")) {
                return CompletableFuture.completedFuture(result(
                        farmworldKey,
                        ResetStatus.ALREADY_RUNNING,
                        "Reset läuft bereits.",
                        null
                ));
            }
            return successfulResult(farmworldKey);
        });
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", true), config("nether", true)),
                dueStates("overworld", "nether"),
                executor,
                recordingLogger(logHandler)
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of("overworld", "nether"), executor.calls);
        assertEquals(0, logHandler.countAtLeast(Level.WARNING));
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void veryOldDueStateCausesOnlyOneCatchUpReset() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", true)),
                Map.of("overworld", state("overworld", NOW.minus(Duration.ofDays(180)))),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();

        assertEquals(List.of("overworld"), executor.calls);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void dueStateIsEvaluatedAgainImmediatelyBeforeEachReset() throws IOException {
        FarmworldResetConfig overworld = config("overworld", true);
        FarmworldResetConfig nether = config("nether", true);
        FarmworldResetService resetService = resetService(
                List.of(overworld, nether),
                dueStates("overworld", "nether")
        );
        CompletableFuture<ResetResult> overworldResult = new CompletableFuture<>();
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey ->
                farmworldKey.equals("overworld")
                        ? overworldResult
                        : successfulResult(farmworldKey)
        );
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        StartupResetCoordinator coordinator = coordinator(
                globalScheduler,
                resetService,
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();
        resetService.completeReset(nether, NOW);
        overworldResult.complete(successfulResult("overworld").join());

        assertEquals(List.of("overworld"), executor.calls);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void periodicSchedulerStartsOnlyAfterCatchUpCompletesAndOnlyOnce() {
        CompletableFuture<ResetResult> pendingResult = new CompletableFuture<>();
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(ignored -> pendingResult);
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", true)),
                dueStates("overworld"),
                executor,
                quietLogger()
        );

        coordinator.start();
        coordinator.start();

        assertEquals(1, globalScheduler.delayedTasks);
        assertEquals(StartupResetCoordinator.STARTUP_DELAY_TICKS, globalScheduler.startupDelay);
        assertEquals(0, globalScheduler.repeatingTasks);

        globalScheduler.runStartupTask();
        assertEquals(0, globalScheduler.repeatingTasks);

        pendingResult.complete(successfulResult("overworld").join());
        coordinator.start();

        assertEquals(1, globalScheduler.delayedTasks);
        assertEquals(1, globalScheduler.repeatingTasks);
    }

    @Test
    void stopDuringStartupDelayPreventsCatchUpAndPeriodicScheduler() {
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", true)),
                dueStates("overworld"),
                executor,
                quietLogger()
        );

        coordinator.start();
        RecordingScheduledTask startupTask = globalScheduler.startupTask;
        coordinator.stop();
        globalScheduler.runStartupTask();

        assertTrue(startupTask.isCancelled());
        assertEquals(1, startupTask.cancelCalls);
        assertEquals(0, globalScheduler.cancelAllCalls);
        assertEquals(List.of(), executor.calls);
        assertEquals(0, globalScheduler.repeatingTasks);
    }

    @Test
    void stopDuringCatchUpPreventsNextFarmworldAndPeriodicScheduler() {
        CompletableFuture<ResetResult> overworldResult = new CompletableFuture<>();
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey ->
                farmworldKey.equals("overworld")
                        ? overworldResult
                        : successfulResult(farmworldKey)
        );
        StartupResetCoordinator coordinator = coordinatorForStates(
                globalScheduler,
                List.of(config("overworld", true), config("nether", true)),
                dueStates("overworld", "nether"),
                executor,
                quietLogger()
        );

        coordinator.start();
        globalScheduler.runStartupTask();
        coordinator.stop();
        overworldResult.complete(successfulResult("overworld").join());

        assertEquals(List.of("overworld"), executor.calls);
        assertEquals(0, globalScheduler.repeatingTasks);
    }

    private StartupResetCoordinator coordinatorForStates(
            RecordingGlobalRegionScheduler globalScheduler,
            List<FarmworldResetConfig> configurations,
            Map<String, FarmworldResetState> states,
            FarmworldResetExecutor resetExecutor,
            Logger logger
    ) {
        return coordinator(
                globalScheduler,
                resetService(configurations, states),
                resetExecutor,
                logger
        );
    }

    private StartupResetCoordinator coordinator(
            RecordingGlobalRegionScheduler globalScheduler,
            FarmworldResetService resetService,
            FarmworldResetExecutor resetExecutor,
            Logger logger
    ) {
        Plugin plugin = plugin(logger);
        AutomaticResetScheduler automaticResetScheduler = new AutomaticResetScheduler(
                plugin,
                globalScheduler,
                resetService,
                resetExecutor,
                new ResetNotificationService(
                        resetService,
                        new ResetWarningTracker(),
                        ignored -> { },
                        ResetPlayerNotificationAudience.noop(),
                        ZoneOffset.UTC,
                        logger
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new StartupResetCoordinator(
                plugin,
                globalScheduler,
                automaticResetScheduler,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private FarmworldResetService resetService(
            List<FarmworldResetConfig> configurations,
            Map<String, FarmworldResetState> states
    ) {
        FarmworldResetService resetService = new FarmworldResetService(
                new FixedResetStateRepository(states),
                Clock.fixed(NOW, ZoneOffset.UTC),
                quietLogger()
        );
        assertTrue(resetService.reload(configurations));
        return resetService;
    }

    private Map<String, FarmworldResetState> dueStates(String... farmworldKeys) {
        Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        for (String farmworldKey : farmworldKeys) {
            states.put(farmworldKey, state(farmworldKey, NOW));
        }
        return states;
    }

    private FarmworldResetConfig config(String farmworldKey, boolean enabled) {
        return new FarmworldResetConfig(
                farmworldKey,
                "test_" + farmworldKey,
                enabled,
                Duration.ofDays(30)
        );
    }

    private FarmworldResetState state(String farmworldKey, Instant nextReset) {
        return new FarmworldResetState(farmworldKey, Optional.empty(), nextReset);
    }

    private CompletableFuture<ResetResult> successfulResult(String farmworldKey) {
        return CompletableFuture.completedFuture(result(
                farmworldKey,
                ResetStatus.SUCCESS,
                "Erfolgreich",
                null
        ));
    }

    private ResetResult result(
            String farmworldKey,
            ResetStatus status,
            String message,
            Throwable cause
    ) {
        return new ResetResult(
                farmworldKey,
                "test_" + farmworldKey,
                status,
                message,
                cause
        );
    }

    private Plugin plugin(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "FarmweltTest";
                    case "getLogger" -> logger;
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
        Logger logger = Logger.getLogger("StartupResetCoordinatorTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private Logger recordingLogger(RecordingLogHandler handler) {
        Logger logger = Logger.getLogger("StartupResetCoordinatorTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
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

    private static final class RecordingResetExecutor implements FarmworldResetExecutor {

        private final Function<String, CompletableFuture<ResetResult>> resetAction;
        private final List<String> calls = new ArrayList<>();

        private RecordingResetExecutor(
                Function<String, CompletableFuture<ResetResult>> resetAction
        ) {
            this.resetAction = resetAction;
        }

        @Override
        public CompletableFuture<ResetResult> reset(String farmworldKey) {
            calls.add(farmworldKey);
            return resetAction.apply(farmworldKey);
        }

        @Override
        public boolean isResetRunning(String farmworldKey) {
            return false;
        }

        @Override
        public boolean isFarmworldAvailable(String farmworldKey) {
            return true;
        }
    }

    private static final class RecordingLogHandler extends Handler {

        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private long countAtLeast(Level level) {
            return records.stream()
                    .filter(record -> record.getLevel().intValue() >= level.intValue())
                    .count();
        }
    }

    private static final class RecordingGlobalRegionScheduler implements GlobalRegionScheduler {

        private int delayedTasks;
        private int repeatingTasks;
        private int cancelAllCalls;
        private long startupDelay;
        private RecordingScheduledTask startupTask;
        private Consumer<ScheduledTask> startupAction;

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
            delayedTasks++;
            startupDelay = delayTicks;
            startupAction = task;
            startupTask = new RecordingScheduledTask(plugin, false);
            return startupTask;
        }

        @Override
        public ScheduledTask runAtFixedRate(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long initialDelayTicks,
            long periodTicks
        ) {
            repeatingTasks++;
            return new RecordingScheduledTask(plugin, true);
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            cancelAllCalls++;
        }

        private void runStartupTask() {
            startupAction.accept(startupTask);
        }
    }

    private static final class RecordingScheduledTask implements ScheduledTask {

        private final Plugin plugin;
        private final boolean repeating;
        private int cancelCalls;
        private boolean cancelled;

        private RecordingScheduledTask(Plugin plugin, boolean repeating) {
            this.plugin = plugin;
            this.repeating = repeating;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return repeating;
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
