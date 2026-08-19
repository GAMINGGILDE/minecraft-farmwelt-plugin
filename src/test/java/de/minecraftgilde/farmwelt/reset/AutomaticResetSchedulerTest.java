package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
        FarmworldResetService resetService = resetService(
                List.of(
                        config("overworld", true),
                        config("nether", true),
                        config("end", false)
                ),
                Map.of(
                        "overworld",
                        state("overworld", NOW),
                        "nether",
                        state("nether", NOW.plusSeconds(1))
                )
        );
        AutomaticResetScheduler scheduler = scheduler(
                new RecordingGlobalRegionScheduler(),
                resetService,
                new RecordingResetExecutor(this::successfulResult),
                quietLogger()
        );

        assertEquals(Map.of(
                "overworld", ResetDueState.DUE,
                "nether", ResetDueState.NOT_DUE,
                "end", ResetDueState.DISABLED
        ), scheduler.evaluateDueStates(NOW));
    }

    @Test
    void notDueDoesNotStartReset() {
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(config("overworld", true)),
                Map.of("overworld", state("overworld", NOW.plusSeconds(1))),
                executor,
                quietLogger()
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of(), executor.calls);
    }

    @Test
    void disabledDoesNotStartReset() {
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(config("overworld", false)),
                Map.of(),
                executor,
                quietLogger()
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of(), executor.calls);
    }

    @Test
    void dueStartsExactlyOneResetForCorrectFarmworld() {
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(config("overworld", true)),
                Map.of("overworld", state("overworld", NOW)),
                executor,
                quietLogger()
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of("overworld"), executor.calls);
    }

    @Test
    void startsOnlyDueFarmworldAmongMixedStates() {
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(
                        config("overworld", true),
                        config("nether", true),
                        config("end", false)
                ),
                Map.of(
                        "overworld", state("overworld", NOW),
                        "nether", state("nether", NOW.plusSeconds(1))
                ),
                executor,
                quietLogger()
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of("overworld"), executor.calls);
    }

    @Test
    void startsEveryDueFarmworldIndependently() {
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(
                        config("overworld", true),
                        config("nether", true),
                        config("end", true)
                ),
                Map.of(
                        "overworld", state("overworld", NOW),
                        "nether", state("nether", NOW.minusSeconds(1)),
                        "end", state("end", NOW.plusSeconds(1))
                ),
                executor,
                quietLogger()
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of("overworld", "nether"), executor.calls);
    }

    @Test
    void alreadyRunningIsSilentAndDoesNotStopFollowingTicks() {
        RecordingLogHandler logHandler = new RecordingLogHandler();
        Logger logger = recordingLogger(logHandler);
        int[] invocation = {0};
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey -> {
            invocation[0]++;
            if (invocation[0] == 1) {
                return CompletableFuture.completedFuture(result(
                        farmworldKey,
                        ResetStatus.ALREADY_RUNNING,
                        "Reset läuft bereits.",
                        null
                ));
            }
            return successfulResult(farmworldKey);
        });
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        AutomaticResetScheduler scheduler = scheduler(
                globalScheduler,
                resetService(
                        List.of(config("overworld", true)),
                        Map.of("overworld", state("overworld", NOW))
                ),
                executor,
                logger
        );
        scheduler.start();

        globalScheduler.tick();
        globalScheduler.tick();

        assertEquals(List.of("overworld", "overworld"), executor.calls);
        assertEquals(0, logHandler.countAtLeast(Level.WARNING));
        assertTrue(logHandler.contains("erfolgreich abgeschlossen"));
        assertEquals(ScheduledTask.ExecutionState.IDLE, globalScheduler.task.getExecutionState());
    }

    @Test
    void pendingResetFutureDoesNotBlockSchedulerCheck() {
        CompletableFuture<ResetResult> pendingResult = new CompletableFuture<>();
        RecordingResetExecutor executor = new RecordingResetExecutor(ignored -> pendingResult);
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(config("overworld", true)),
                Map.of("overworld", state("overworld", NOW)),
                executor,
                quietLogger()
        );

        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> scheduler.startDueResets(NOW)
        );

        assertEquals(List.of("overworld"), executor.calls);
        assertFalse(pendingResult.isDone());
    }

    @Test
    void successfulResultIsLoggedAndSchedulerRemainsActive() {
        RecordingLogHandler logHandler = new RecordingLogHandler();
        Logger logger = recordingLogger(logHandler);
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        AutomaticResetScheduler scheduler = scheduler(
                globalScheduler,
                resetService(
                        List.of(config("overworld", true)),
                        Map.of("overworld", state("overworld", NOW))
                ),
                new RecordingResetExecutor(this::successfulResult),
                logger
        );
        scheduler.start();

        globalScheduler.tick();

        assertTrue(logHandler.contains(
                "Automatischer Reset für Farmwelt 'overworld' erfolgreich abgeschlossen."
        ));
        assertEquals(ScheduledTask.ExecutionState.IDLE, globalScheduler.task.getExecutionState());
    }

    @Test
    void failedResultIsLoggedWithoutPreventingOtherFarmworlds() {
        RecordingLogHandler logHandler = new RecordingLogHandler();
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey -> {
            if (farmworldKey.equals("overworld")) {
                return CompletableFuture.completedFuture(result(
                        farmworldKey,
                        ResetStatus.REGENERATE_FAILED,
                        "Worlds-Fehler",
                        new IllegalStateException("Testfehler")
                ));
            }
            return successfulResult(farmworldKey);
        });
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(config("overworld", true), config("nether", true)),
                Map.of(
                        "overworld", state("overworld", NOW),
                        "nether", state("nether", NOW)
                ),
                executor,
                recordingLogger(logHandler)
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of("overworld", "nether"), executor.calls);
        assertTrue(logHandler.contains("REGENERATE_FAILED - Worlds-Fehler"));
        assertTrue(logHandler.contains("Farmwelt 'nether' erfolgreich abgeschlossen"));
    }

    @Test
    void synchronousAndExceptionalFailuresDoNotPreventLaterFarmworlds() {
        RecordingLogHandler logHandler = new RecordingLogHandler();
        RecordingResetExecutor executor = new RecordingResetExecutor(farmworldKey -> switch (
                farmworldKey
        ) {
            case "overworld" -> throw new IllegalStateException("Synchroner Testfehler");
            case "nether" -> CompletableFuture.failedFuture(
                    new IllegalStateException("Asynchroner Testfehler")
            );
            default -> successfulResult(farmworldKey);
        });
        AutomaticResetScheduler scheduler = schedulerForStates(
                List.of(
                        config("overworld", true),
                        config("nether", true),
                        config("end", true)
                ),
                Map.of(
                        "overworld", state("overworld", NOW),
                        "nether", state("nether", NOW),
                        "end", state("end", NOW)
                ),
                executor,
                recordingLogger(logHandler)
        );

        scheduler.startDueResets(NOW);

        assertEquals(List.of("overworld", "nether", "end"), executor.calls);
        assertTrue(logHandler.contains("Farmwelt 'overworld' konnte nicht gestartet werden"));
        assertTrue(logHandler.contains("Farmwelt 'nether' wurde mit einer Exception beendet"));
        assertTrue(logHandler.contains("Farmwelt 'end' erfolgreich abgeschlossen"));
    }

    @Test
    void notificationBroadcastFailureDoesNotPreventDueReset() {
        RecordingLogHandler logHandler = new RecordingLogHandler();
        Logger logger = recordingLogger(logHandler);
        FarmworldResetService resetService = resetService(
                List.of(config("overworld", true), config("nether", true)),
                Map.of(
                        "overworld", state("overworld", NOW.plus(Duration.ofMinutes(5))),
                        "nether", state("nether", NOW)
                )
        );
        RecordingResetExecutor executor = new RecordingResetExecutor(this::successfulResult);
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        Plugin plugin = plugin(logger);
        ResetNotificationService notificationService = new ResetNotificationService(
                resetService,
                new ResetWarningTracker(),
                ignored -> {
                    throw new IllegalStateException("Broadcast-Testfehler");
                },
                ZoneOffset.UTC,
                logger
        );
        AutomaticResetScheduler scheduler = new AutomaticResetScheduler(
                plugin,
                globalScheduler,
                resetService,
                executor,
                notificationService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        scheduler.start();

        globalScheduler.tick();

        assertEquals(List.of("nether"), executor.calls);
        assertTrue(logHandler.contains("konnte nicht versendet werden"));
        assertTrue(logHandler.contains("Farmwelt 'nether' erfolgreich abgeschlossen"));
    }

    private AutomaticResetScheduler createScheduler(
            RecordingGlobalRegionScheduler globalScheduler
    ) {
        return scheduler(
                globalScheduler,
                resetService(List.of(), Map.of()),
                new RecordingResetExecutor(this::successfulResult),
                quietLogger()
        );
    }

    private AutomaticResetScheduler schedulerForStates(
            List<FarmworldResetConfig> configurations,
            Map<String, FarmworldResetState> states,
            FarmworldResetExecutor resetExecutor,
            Logger logger
    ) {
        return scheduler(
                new RecordingGlobalRegionScheduler(),
                resetService(configurations, states),
                resetExecutor,
                logger
        );
    }

    private AutomaticResetScheduler scheduler(
            RecordingGlobalRegionScheduler globalScheduler,
            FarmworldResetService resetService,
            FarmworldResetExecutor resetExecutor,
            Logger logger
    ) {
        return new AutomaticResetScheduler(
                plugin(logger),
                globalScheduler,
                resetService,
                resetExecutor,
                new ResetNotificationService(
                        resetService,
                        new ResetWarningTracker(),
                        ignored -> { },
                        ZoneOffset.UTC,
                        logger
                ),
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
        Logger logger = Logger.getLogger("AutomaticResetSchedulerTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private Logger recordingLogger(RecordingLogHandler handler) {
        Logger logger = Logger.getLogger("AutomaticResetSchedulerTest-" + System.nanoTime());
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

        private boolean contains(String messagePart) {
            return records.stream().anyMatch(record -> record.getMessage().contains(messagePart));
        }

        private long countAtLeast(Level level) {
            return records.stream()
                    .filter(record -> record.getLevel().intValue() >= level.intValue())
                    .count();
        }
    }

    private static final class RecordingGlobalRegionScheduler implements GlobalRegionScheduler {

        private int scheduledTasks;
        private int cancelAllCalls;
        private long initialDelay;
        private long period;
        private RecordingScheduledTask task;
        private Consumer<ScheduledTask> scheduledAction;

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
            scheduledAction = task;
            this.task = new RecordingScheduledTask(plugin);
            return this.task;
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            cancelAllCalls++;
        }

        private void tick() {
            scheduledAction.accept(task);
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
