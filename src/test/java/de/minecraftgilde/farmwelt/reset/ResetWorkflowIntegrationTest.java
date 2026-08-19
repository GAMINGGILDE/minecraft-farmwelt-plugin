package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResetWorkflowIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    @Test
    void dueAutomaticResetPersistsCompletionAndPreventsDuplicateTicks() {
        TestResetStateRepository repository = repositoryWithDueState("overworld");
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );
        CompletableFuture<World> pendingRegeneration = harness.runtime.defer("overworld");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();

        assertTrue(harness.engine.isResetRunning("overworld"));
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(0, repository.saveCount);
        assertEquals(
                List.of("&eDie &6Farmwelt&e wird jetzt zurückgesetzt."),
                harness.notifications
        );

        Instant completedAt = NOW.plus(Duration.ofMinutes(2));
        harness.clock.setInstant(completedAt);
        harness.runtime.complete("overworld", pendingRegeneration);

        FarmworldResetState completedState = harness.resetService.getState("overworld").orElseThrow();
        assertFalse(harness.engine.isResetRunning("overworld"));
        assertEquals(Optional.of(completedAt), completedState.lastReset());
        assertEquals(completedAt.plus(THIRTY_DAYS), completedState.nextReset());
        assertEquals(completedState, repository.states.get("overworld"));
        assertEquals(1, repository.saveCount);
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), harness.notifications);

        harness.globalScheduler.tick();

        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(1, repository.saveCount);
    }

    @Test
    void notDueScheduleRemainsReadOnlyAcrossMultipleTicks() {
        FarmworldResetState futureState = state("overworld", NOW.plus(Duration.ofDays(10)));
        TestResetStateRepository repository = new TestResetStateRepository(Map.of(
                "overworld", futureState
        ));
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();

        assertEquals(List.of(), harness.runtime.regenerationKeys);
        assertEquals(futureState, harness.resetService.getState("overworld").orElseThrow());
        assertEquals(futureState, repository.states.get("overworld"));
        assertEquals(0, repository.saveCount);
    }

    @Test
    void manualForcePathBroadcastsLifecycleWithoutCountdown() {
        FarmworldResetState futureState = state("overworld", NOW.plus(Duration.ofDays(10)));
        TestResetStateRepository repository = new TestResetStateRepository(Map.of(
                "overworld", futureState
        ));
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );

        ResetResult result = harness.engine.reset("overworld", ResetOptions.defaults()).join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), harness.notifications);
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(1, repository.saveCount);
    }

    @Test
    void regularSchedulerIsolatesFailedWorldAndRetriesItOnLaterTick() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS)
                ),
                repository
        );
        harness.runtime.failEveryRegeneration("overworld");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();

        assertEquals(List.of("overworld", "nether"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(Optional.of(NOW), harness.resetService.getState("nether").orElseThrow().lastReset());
        assertEquals(1, repository.saveCount);
        assertEquals(ScheduledTask.ExecutionState.IDLE, harness.globalScheduler.periodicTask.getExecutionState());

        harness.globalScheduler.tick();

        assertEquals(List.of("overworld", "nether", "overworld"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(1, repository.saveCount);
    }

    @Test
    void startupCatchUpRunsRealPipelinesSequentiallyBeforePeriodicScheduler() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether", "end"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS),
                        endConfig(Duration.ofDays(60))
                ),
                repository
        );
        CompletableFuture<World> overworld = harness.runtime.defer("overworld");
        CompletableFuture<World> nether = harness.runtime.defer("nether");
        CompletableFuture<World> end = harness.runtime.defer("end");

        harness.startupCoordinator.start();

        assertEquals(StartupResetCoordinator.STARTUP_DELAY_TICKS, harness.globalScheduler.startupDelay);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.globalScheduler.runStartupTask();
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.runtime.complete("overworld", overworld);
        assertEquals(List.of("overworld", "nether"), harness.runtime.regenerationKeys);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.runtime.complete("nether", nether);
        assertEquals(List.of("overworld", "nether", "end"), harness.runtime.regenerationKeys);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.runtime.complete("end", end);

        assertEquals(1, harness.globalScheduler.periodicTasks);
        assertEquals(3, repository.saveCount);
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt.",
                "&eDie &6Netherfarm&e wird jetzt zurückgesetzt.",
                "&aDie &6Netherfarm&a wurde erfolgreich zurückgesetzt.",
                "&eDie &6Endfarm&e wird jetzt zurückgesetzt.",
                "&aDie &6Endfarm&a wurde erfolgreich zurückgesetzt."
        ), harness.notifications);
        assertEquals(Optional.of(NOW), harness.resetService.getState("overworld").orElseThrow().lastReset());
        assertEquals(Optional.of(NOW), harness.resetService.getState("nether").orElseThrow().lastReset());
        assertEquals(Optional.of(NOW), harness.resetService.getState("end").orElseThrow().lastReset());

        harness.globalScheduler.tick();
        assertEquals(List.of("overworld", "nether", "end"), harness.runtime.regenerationKeys);
    }

    @Test
    void startupFailureLeavesOnlyFailedWorldDueAndPeriodicSchedulerRetriesIt() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether", "end"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS),
                        endConfig(Duration.ofDays(60))
                ),
                repository
        );
        harness.runtime.failEveryRegeneration("overworld");

        harness.startupCoordinator.start();
        harness.globalScheduler.runStartupTask();

        assertEquals(List.of("overworld", "nether", "end"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(Optional.of(NOW), harness.resetService.getState("nether").orElseThrow().lastReset());
        assertEquals(Optional.of(NOW), harness.resetService.getState("end").orElseThrow().lastReset());
        assertEquals(2, repository.saveCount);
        assertEquals(1, harness.globalScheduler.periodicTasks);

        harness.globalScheduler.tick();

        assertEquals(
                List.of("overworld", "nether", "end", "overworld"),
                harness.runtime.regenerationKeys
        );
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(2, repository.saveCount);
    }

    @Test
    void automaticEndResetUsesDefaultOptionsAndConfiguredDragonlessPolicy() {
        TestResetStateRepository repository = repositoryWithDueState("end");
        WorkflowHarness harness = harness(
                List.of(endConfig(Duration.ofDays(60))),
                repository
        );

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();

        RegenerationCall call = harness.runtime.regenerationCalls.getFirst();
        assertEquals("end", call.farmworldKey());
        assertEquals(
                FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED,
                call.options().endDragonFightDataMode()
        );
        assertEquals(List.of(ResetOptions.defaults()), harness.runtime.postResetOptions);
        assertEquals(Optional.of(NOW), harness.resetService.getState("end").orElseThrow().lastReset());
    }

    @Test
    void automaticResetKeepsSnapshotAcrossReloadAndNextPipelineUsesNewInterval() {
        TestResetStateRepository repository = repositoryWithDueState("overworld");
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );
        CompletableFuture<World> pendingRegeneration = harness.runtime.defer("overworld");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        assertTrue(harness.resetService.reload(List.of(
                config("overworld", Duration.ofDays(60))
        )));

        Instant firstCompletion = NOW.plus(Duration.ofMinutes(2));
        harness.clock.setInstant(firstCompletion);
        harness.runtime.complete("overworld", pendingRegeneration);

        FarmworldResetState firstState = harness.resetService.getState("overworld").orElseThrow();
        assertEquals(firstCompletion.plus(THIRTY_DAYS), firstState.nextReset());

        harness.clock.setInstant(firstState.nextReset());
        harness.globalScheduler.tick();

        FarmworldResetState secondState = harness.resetService.getState("overworld").orElseThrow();
        assertEquals(Optional.of(firstState.nextReset()), secondState.lastReset());
        assertEquals(firstState.nextReset().plus(Duration.ofDays(60)), secondState.nextReset());
        assertEquals(List.of("overworld", "overworld"), harness.runtime.regenerationKeys);
        assertEquals(2, repository.saveCount);
    }

    @Test
    void restartWithYamlRepositoryPreservesFutureScheduleAndSkipsCatchUp(
            @TempDir Path tempDir
    ) throws IOException {
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        Path stateFile = tempDir.resolve("reset-state.yml");
        YamlResetStateRepository repository = new YamlResetStateRepository(stateFile, quietLogger());
        FarmworldResetConfig config = config("overworld", THIRTY_DAYS);
        FarmworldResetService firstInstance = new FarmworldResetService(
                repository,
                clock,
                quietLogger()
        );
        assertTrue(firstInstance.reload(List.of(config)));
        FarmworldResetState persistedState = firstInstance.getState("overworld").orElseThrow();

        WorkflowHarness restarted = new WorkflowHarness(List.of(config), repository, clock);
        restarted.startupCoordinator.start();
        restarted.globalScheduler.runStartupTask();

        assertEquals(NOW.plus(THIRTY_DAYS), persistedState.nextReset());
        assertEquals(persistedState, restarted.resetService.getState("overworld").orElseThrow());
        assertEquals(persistedState, repository.load().get("overworld"));
        assertEquals(List.of(), restarted.runtime.regenerationKeys);
        assertEquals(1, restarted.globalScheduler.periodicTasks);
    }

    private WorkflowHarness harness(
            List<FarmworldResetConfig> configurations,
            TestResetStateRepository repository
    ) {
        return new WorkflowHarness(
                configurations,
                repository,
                new MutableClock(NOW, ZoneOffset.UTC)
        );
    }

    private static FarmworldResetConfig config(String farmworldKey, Duration interval) {
        return new FarmworldResetConfig(
                farmworldKey,
                "test_" + farmworldKey,
                true,
                interval
        );
    }

    private static FarmworldResetConfig endConfig(Duration interval) {
        return new FarmworldResetConfig(
                "end",
                "test_end",
                true,
                interval,
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(false))
                )
        );
    }

    private static TestResetStateRepository repositoryWithDueState(String farmworldKey) {
        return new TestResetStateRepository(Map.of(
                farmworldKey,
                state(farmworldKey, NOW)
        ));
    }

    private static Map<String, FarmworldResetState> dueStates(String... farmworldKeys) {
        Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        for (String farmworldKey : farmworldKeys) {
            states.put(farmworldKey, state(farmworldKey, NOW));
        }
        return states;
    }

    private static FarmworldResetState state(String farmworldKey, Instant nextReset) {
        return new FarmworldResetState(farmworldKey, Optional.empty(), nextReset);
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("ResetWorkflowIntegrationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static Plugin plugin(Logger logger) {
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

    private static World world(
            String name,
            FarmworldType type,
            String directory,
            long seed
    ) {
        World.Environment environment = switch (type) {
            case OVERWORLD -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case END -> World.Environment.THE_END;
        };
        File worldFolder = Path.of("server", "world", "workflow-tests", directory)
                .toAbsolutePath()
                .toFile();
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getEnvironment" -> environment;
                    case "getWorldFolder" -> worldFolder;
                    case "getSeed" -> seed;
                    case "toString" -> "FakeWorld[" + name + ", " + directory + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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

    private static final class WorkflowHarness {

        private final MutableClock clock;
        private final FarmworldResetService resetService;
        private final FakeMinecraftRuntime runtime;
        private final FarmworldResetEngine engine;
        private final RecordingGlobalRegionScheduler globalScheduler;
        private final AutomaticResetScheduler automaticScheduler;
        private final StartupResetCoordinator startupCoordinator;
        private final List<String> notifications = new ArrayList<>();

        private WorkflowHarness(
                List<FarmworldResetConfig> configurations,
                ResetStateRepository repository,
                MutableClock clock
        ) {
            this.clock = clock;
            Logger logger = quietLogger();
            this.resetService = new FarmworldResetService(repository, clock, logger);
            assertTrue(resetService.reload(configurations));
            this.runtime = new FakeMinecraftRuntime(configurations);
            ResetNotificationService notificationService = new ResetNotificationService(
                    resetService,
                    new ResetWarningTracker(),
                    notifications::add,
                    ZoneOffset.UTC,
                    logger
            );
            this.engine = new FarmworldResetEngine(
                    resetService,
                    runtime,
                    runtime,
                    runtime,
                    new DirectScheduler(),
                    notificationService,
                    logger
            );
            this.globalScheduler = new RecordingGlobalRegionScheduler();
            Plugin plugin = plugin(logger);
            this.automaticScheduler = new AutomaticResetScheduler(
                    plugin,
                    globalScheduler,
                    resetService,
                    engine,
                    notificationService,
                    clock
            );
            this.startupCoordinator = new StartupResetCoordinator(
                    plugin,
                    globalScheduler,
                    automaticScheduler,
                    clock
            );
        }
    }

    private record RegenerationCall(
            String farmworldKey,
            FarmworldRegenerationOptions options
    ) {
    }

    private static final class FakeMinecraftRuntime implements
            FarmworldWorldOperations,
            FarmworldLifecycleService,
            FarmworldPostResetInitializer {

        private final Map<String, FarmworldResetConfig> configurations = new LinkedHashMap<>();
        private final Map<String, World> activeWorlds = new LinkedHashMap<>();
        private final Map<World, String> farmworldKeys = new IdentityHashMap<>();
        private final Map<String, Deque<CompletableFuture<World>>> plannedRegenerations =
                new LinkedHashMap<>();
        private final Map<String, Integer> generations = new LinkedHashMap<>();
        private final Set<String> alwaysFailing = new java.util.HashSet<>();
        private final List<String> regenerationKeys = new ArrayList<>();
        private final List<RegenerationCall> regenerationCalls = new ArrayList<>();
        private final List<ResetOptions> postResetOptions = new ArrayList<>();

        private FakeMinecraftRuntime(List<FarmworldResetConfig> configurations) {
            for (FarmworldResetConfig configuration : configurations) {
                this.configurations.put(configuration.farmworldKey(), configuration);
                World initialWorld = world(
                        configuration.worldName(),
                        configuration.farmworldType(),
                        configuration.farmworldKey() + "-initial",
                        1L
                );
                activeWorlds.put(configuration.farmworldKey(), initialWorld);
                farmworldKeys.put(initialWorld, configuration.farmworldKey());
                generations.put(configuration.farmworldKey(), 0);
            }
        }

        private CompletableFuture<World> defer(String farmworldKey) {
            CompletableFuture<World> future = new CompletableFuture<>();
            plannedRegenerations.computeIfAbsent(
                    farmworldKey,
                    ignored -> new ArrayDeque<>()
            ).addLast(future);
            return future;
        }

        private void complete(String farmworldKey, CompletableFuture<World> future) {
            future.complete(createRegeneratedWorld(farmworldKey));
        }

        private void failEveryRegeneration(String farmworldKey) {
            alwaysFailing.add(farmworldKey);
        }

        @Override
        public WorldInspection inspect(FarmworldResetConfig resetConfig) {
            return WorldInspection.loaded(
                    activeWorlds.get(resetConfig.farmworldKey()),
                    resetConfig.farmworldType(),
                    false
            );
        }

        @Override
        public CompletableFuture<Boolean> evacuatePlayers(World resetWorld) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public boolean hasPlayers(World world) {
            return false;
        }

        @Override
        public CompletableFuture<World> regenerate(
                World world,
                FarmworldRegenerationOptions options
        ) {
            String farmworldKey = farmworldKeys.get(world);
            regenerationKeys.add(farmworldKey);
            regenerationCalls.add(new RegenerationCall(farmworldKey, options));
            if (alwaysFailing.contains(farmworldKey)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("simulierter Worlds-Fehler")
                );
            }

            Deque<CompletableFuture<World>> planned = plannedRegenerations.get(farmworldKey);
            CompletableFuture<World> result = planned == null || planned.isEmpty()
                    ? CompletableFuture.completedFuture(createRegeneratedWorld(farmworldKey))
                    : planned.removeFirst();
            return result.thenApply(regeneratedWorld -> {
                activeWorlds.put(farmworldKey, regeneratedWorld);
                farmworldKeys.put(regeneratedWorld, farmworldKey);
                return regeneratedWorld;
            });
        }

        @Override
        public CompletableFuture<PostResetResult> apply(
                FarmworldResetConfig config,
                World regeneratedWorld,
                ResetOptions options
        ) {
            postResetOptions.add(options);
            return CompletableFuture.completedFuture(PostResetResult.success());
        }

        private World createRegeneratedWorld(String farmworldKey) {
            FarmworldResetConfig configuration = configurations.get(farmworldKey);
            int generation = generations.compute(farmworldKey, (ignored, current) -> current + 1);
            return world(
                    configuration.worldName(),
                    configuration.farmworldType(),
                    farmworldKey + "-generation-" + generation,
                    generation + 1L
            );
        }
    }

    private static final class DirectScheduler implements FarmweltScheduler {

        @Override
        public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runGlobalDelayed(
                long ticks,
                CheckedSupplier<T> operation
        ) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runRegion(
                World world,
                int chunkX,
                int chunkZ,
                CheckedSupplier<T> operation
        ) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runRegionDelayed(
                World world,
                int chunkX,
                int chunkZ,
                long ticks,
                CheckedSupplier<T> operation
        ) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
            return execute(operation);
        }

        private <T> CompletableFuture<T> execute(CheckedSupplier<T> operation) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }

    private static final class TestResetStateRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states;
        private int saveCount;

        private TestResetStateRepository(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
        }

        @Override
        public Map<String, FarmworldResetState> load() {
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
            saveCount++;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class RecordingGlobalRegionScheduler implements GlobalRegionScheduler {

        private long startupDelay;
        private int periodicTasks;
        private Consumer<ScheduledTask> startupAction;
        private Consumer<ScheduledTask> periodicAction;
        private RecordingScheduledTask startupTask;
        private RecordingScheduledTask periodicTask;

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
            periodicTasks++;
            periodicAction = task;
            periodicTask = new RecordingScheduledTask(plugin, true);
            return periodicTask;
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        private void runStartupTask() {
            startupAction.accept(startupTask);
        }

        private void tick() {
            periodicAction.accept(periodicTask);
        }
    }

    private static final class RecordingScheduledTask implements ScheduledTask {

        private final Plugin plugin;
        private final boolean repeating;
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
