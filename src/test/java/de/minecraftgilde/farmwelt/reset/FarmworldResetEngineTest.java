package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FarmworldResetEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-17T07:00:00Z");
    private static final long OLD_SEED = 123L;
    private static final long NEW_SEED = 456L;

    private final List<String> calls = new ArrayList<>();
    private final TestResetStateRepository repository = new TestResetStateRepository(calls);
    private final World originalWorld = world(
            "farmwelt", World.Environment.NORMAL, "old-farmwelt", OLD_SEED
    );
    private final World regeneratedWorld = world(
            "farmwelt", World.Environment.NORMAL, "new-farmwelt", NEW_SEED
    );
    private final FakeWorldOperations worldOperations = new FakeWorldOperations(
            calls,
            WorldInspection.loaded(originalWorld, FarmworldType.OVERWORLD, false),
            WorldInspection.loaded(regeneratedWorld, FarmworldType.OVERWORLD, false)
    );
    private final FakeLifecycleService lifecycleService = new FakeLifecycleService(
            calls,
            CompletableFuture.completedFuture(regeneratedWorld)
    );
    private final FarmworldResetService resetService = new FarmworldResetService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            quietLogger()
    );
    private final FakePostResetInitializer postResetInitializer = new FakePostResetInitializer(calls);
    private final FarmworldResetEngine engine = new FarmworldResetEngine(
            resetService,
            worldOperations,
            lifecycleService,
            postResetInitializer,
            new DirectScheduler(),
            quietLogger()
    );

    @BeforeEach
    void configureReset() {
        resetService.reload(List.of(config()));
        postResetInitializer.result = CompletableFuture.completedFuture(PostResetResult.success());
        postResetInitializer.receivedConfig = null;
        postResetInitializer.receivedOptions = null;
        lifecycleService.receivedOptions = null;
        calls.clear();
    }

    @Test
    void executesWorldsHappyPathInOrderAndPersistsSchedule() {
        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(
                "inspect",
                "evacuate",
                "hasPlayers",
                "regenerate",
                "inspectRegenerated",
                "postReset",
                "state"
        ), calls);
        assertSame(originalWorld, lifecycleService.receivedWorld);
        FarmworldResetState state = resetService.getState("overworld").orElseThrow();
        assertEquals(NOW, state.lastReset().orElseThrow());
        assertEquals(NOW.plus(Duration.ofDays(30)), state.nextReset());
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void successfulResetLogsOldAndNewSeed() {
        List<LogRecord> logRecords = new ArrayList<>();
        FarmworldResetEngine loggingEngine = engineWith(recordingLogger(logRecords));

        ResetResult result = loggingEngine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getMessage().equals("Alter Seed: " + OLD_SEED)
        ));
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getMessage().equals("Neuer Seed: " + NEW_SEED)
        ));
    }

    @Test
    void identicalRandomSeedWarnsButResetStillSucceeds() {
        World sameSeedWorld = world(
                "farmwelt", World.Environment.NORMAL, "same-seed-farmwelt", OLD_SEED
        );
        lifecycleService.result = CompletableFuture.completedFuture(sameSeedWorld);
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                sameSeedWorld,
                FarmworldType.OVERWORLD,
                false
        );
        List<LogRecord> logRecords = new ArrayList<>();
        FarmworldResetEngine loggingEngine = engineWith(recordingLogger(logRecords));

        ResetResult result = loggingEngine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(1, lifecycleService.invocations);
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getLevel() == Level.WARNING
                        && record.getMessage().equals(
                                "Der zufällig erzeugte Seed entspricht dem vorherigen Seed."
                        )
        ));
    }

    @Test
    void evacuationFailureNeverInvokesWorldsAndReleasesLock() {
        worldOperations.evacuationResult = CompletableFuture.completedFuture(false);

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertEquals(0, lifecycleService.invocations);
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void playersRemainingAfterEvacuationNeverInvokesWorlds() {
        worldOperations.hasPlayers = true;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertEquals(0, lifecycleService.invocations);
        assertFalse(calls.contains("state"));
    }

    @Test
    void regenerationFailureDoesNotUpdateStateAndReleasesLock() {
        IllegalStateException worldsFailure = new IllegalStateException("regenerate failed");
        lifecycleService.result = CompletableFuture.failedFuture(worldsFailure);
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertSame(worldsFailure, result.cause());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void regeneratedWorldWithWrongNameIsRejected() {
        World wrongWorld = world("wrong_name", World.Environment.NORMAL, "wrong-name");
        lifecycleService.result = CompletableFuture.completedFuture(wrongWorld);
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                wrongWorld,
                FarmworldType.OVERWORLD,
                false
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void regeneratedWorldWithWrongDimensionIsRejected() {
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedWorld,
                FarmworldType.NETHER,
                false
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void regeneratedWorldMustBeTheInstanceReachableThroughBukkit() {
        World otherBukkitWorld = world("farmwelt", World.Environment.NORMAL, "other-farmwelt");
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                otherBukkitWorld,
                FarmworldType.OVERWORLD,
                false
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void staleWorldInstanceIsRejected() {
        lifecycleService.result = CompletableFuture.completedFuture(originalWorld);

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("inspectRegenerated"));
        assertFalse(calls.contains("state"));
    }

    @Test
    void stateFailureIsNotSuccessAndDoesNotRegenerateTwice() {
        repository.failSaves = true;
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.STATE_SAVE_FAILED, result.status());
        assertEquals(1, lifecycleService.invocations);
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void postResetFailureDoesNotCompleteStateAndReleasesLock() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        postResetInitializer.result = CompletableFuture.completedFuture(
                PostResetResult.failure("post reset failed", new IllegalStateException("broken"))
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.POST_RESET_FAILED, result.status());
        assertEquals(List.of(
                "inspect",
                "evacuate",
                "hasPlayers",
                "regenerate",
                "inspectRegenerated",
                "postReset"
        ), calls);
        assertFalse(calls.contains("state"));
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(engine.isResetRunning("overworld"));
        assertTrue(postResetInitializer.resetScopeClosed);
    }

    @Test
    void oneTimeDragonOverrideAlsoKeepsFightDataDuringRegeneration() {
        ResetOptions options = ResetOptions.allowingEnderDragon();

        ResetResult result = engine.reset("overworld", options).join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertSame(options, postResetInitializer.receivedOptions);
        assertEquals(1, lifecycleService.invocations);
        assertFalse(lifecycleService.receivedOptions.resetEndDragonFightData());
    }

    @Test
    void dragonDisabledEndResetRequestsFightDataResetFromLifecycle() {
        World originalEnd = world("endfarm", World.Environment.THE_END, "old-endfarm", OLD_SEED);
        World regeneratedEnd = world(
                "endfarm", World.Environment.THE_END, "new-endfarm", NEW_SEED
        );
        resetService.reload(List.of(new FarmworldResetConfig(
                "end",
                "endfarm",
                true,
                Duration.ofDays(60),
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.of(new EndPostResetConfig(false))
                )
        )));
        worldOperations.inspectCount = 0;
        worldOperations.initialInspection = WorldInspection.loaded(
                originalEnd,
                FarmworldType.END,
                false
        );
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedEnd,
                FarmworldType.END,
                false
        );
        lifecycleService.result = CompletableFuture.completedFuture(regeneratedEnd);

        ResetResult result = engine.reset("end").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertTrue(lifecycleService.receivedOptions.resetEndDragonFightData());
    }

    @Test
    void dragonOverrideSkipsFightDataResetForEndWorld() {
        World originalEnd = world("endfarm", World.Environment.THE_END, "old-endfarm", OLD_SEED);
        World regeneratedEnd = world(
                "endfarm", World.Environment.THE_END, "new-endfarm", NEW_SEED
        );
        resetService.reload(List.of(new FarmworldResetConfig(
                "end",
                "endfarm",
                true,
                Duration.ofDays(60),
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.of(new EndPostResetConfig(false))
                )
        )));
        worldOperations.inspectCount = 0;
        worldOperations.initialInspection = WorldInspection.loaded(
                originalEnd,
                FarmworldType.END,
                false
        );
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedEnd,
                FarmworldType.END,
                false
        );
        lifecycleService.result = CompletableFuture.completedFuture(regeneratedEnd);

        ResetResult result = engine.reset("end", ResetOptions.allowingEnderDragon()).join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertFalse(lifecycleService.receivedOptions.resetEndDragonFightData());
    }

    @Test
    void concurrentResetLocksTeleportUntilWorldsFutureCompletes() {
        CompletableFuture<World> pendingRegeneration = new CompletableFuture<>();
        lifecycleService.result = pendingRegeneration;

        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");

        assertTrue(engine.isResetRunning("overworld"));
        assertFalse(engine.isFarmworldAvailable("overworld"));
        assertEquals(ResetStatus.ALREADY_RUNNING, engine.reset("overworld").join().status());

        pendingRegeneration.complete(regeneratedWorld);

        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        assertFalse(engine.isResetRunning("overworld"));
        assertTrue(engine.isFarmworldAvailable("overworld"));
    }

    @Test
    void missingAndDisabledConfigurationsReturnExplicitStatuses() {
        assertEquals(ResetStatus.NOT_CONFIGURED, engine.reset("missing").join().status());

        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                false,
                Duration.ofDays(30)
        )));

        assertEquals(ResetStatus.DISABLED, engine.reset("overworld").join().status());
    }

    @Test
    void unloadedWorldIsRejectedBeforeEvacuation() {
        worldOperations.initialInspection = WorldInspection.unloaded();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.WORLD_NOT_LOADED, result.status());
        assertEquals(List.of("inspect"), calls);
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void protectedMainWorldIsRejectedBeforeEvacuation() {
        worldOperations.initialInspection = WorldInspection.loaded(
                originalWorld,
                FarmworldType.OVERWORLD,
                true
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.PROTECTED_WORLD, result.status());
        assertEquals(List.of("inspect"), calls);
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void initialWorldNameAndDimensionMustMatchConfiguration() {
        World wrongWorld = world("wrong_name", World.Environment.NORMAL, "wrong-name");
        worldOperations.initialInspection = WorldInspection.loaded(
                wrongWorld,
                FarmworldType.OVERWORLD,
                false
        );
        assertEquals(ResetStatus.INVALID_CONFIGURATION, engine.reset("overworld").join().status());

        worldOperations.inspectCount = 0;
        worldOperations.initialInspection = WorldInspection.loaded(
                originalWorld,
                FarmworldType.NETHER,
                false
        );
        assertEquals(ResetStatus.INVALID_CONFIGURATION, engine.reset("overworld").join().status());
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void reloadDuringWorldsResetKeepsLockAndOriginalIntervalSnapshot() {
        PostResetConfig originalPostReset = new PostResetConfig(
                Map.of("show_advancement_messages", false),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        );
        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                true,
                Duration.ofDays(30),
                FarmworldType.OVERWORLD,
                originalPostReset
        )));
        CompletableFuture<World> pendingRegeneration = new CompletableFuture<>();
        lifecycleService.result = pendingRegeneration;
        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");

        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "replacement_farmwelt",
                true,
                Duration.ofDays(60)
        )));

        assertTrue(engine.isResetRunning("overworld"));
        assertEquals(ResetStatus.ALREADY_RUNNING, engine.reset("overworld").join().status());
        pendingRegeneration.complete(regeneratedWorld);

        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        assertEquals("replacement_farmwelt", resetService.getConfig("overworld").orElseThrow().worldName());
        assertEquals(Duration.ofDays(60), resetService.getConfig("overworld").orElseThrow().interval());
        assertEquals("farmwelt", postResetInitializer.receivedConfig.worldName());
        assertSame(originalPostReset, postResetInitializer.receivedConfig.postReset());
        assertEquals(
                NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset()
        );
    }

    private FarmworldResetConfig config() {
        return new FarmworldResetConfig("overworld", "farmwelt", true, Duration.ofDays(30));
    }

    private FarmworldResetEngine engineWith(Logger logger) {
        return new FarmworldResetEngine(
                resetService,
                worldOperations,
                lifecycleService,
                postResetInitializer,
                new DirectScheduler(),
                logger
        );
    }

    private static World world(String name, World.Environment environment, String directory) {
        return world(name, environment, directory, 0L);
    }

    private static World world(
            String name,
            World.Environment environment,
            String directory,
            long seed
    ) {
        File worldFolder = Path.of("server", "world", "dimensions", "worlds", directory)
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
                    case "toString" -> "FakeWorld[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
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

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("FarmworldResetEngineTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static Logger recordingLogger(List<LogRecord> logRecords) {
        Logger logger = Logger.getLogger("FarmworldResetEngineTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
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

    private static final class FakeWorldOperations implements FarmworldWorldOperations {

        private final List<String> calls;
        private WorldInspection initialInspection;
        private WorldInspection regeneratedInspection;
        private CompletableFuture<Boolean> evacuationResult = CompletableFuture.completedFuture(true);
        private boolean hasPlayers;
        private int inspectCount;

        private FakeWorldOperations(
                List<String> calls,
                WorldInspection initialInspection,
                WorldInspection regeneratedInspection
        ) {
            this.calls = calls;
            this.initialInspection = initialInspection;
            this.regeneratedInspection = regeneratedInspection;
        }

        @Override
        public WorldInspection inspect(FarmworldResetConfig resetConfig) {
            calls.add(inspectCount++ == 0 ? "inspect" : "inspectRegenerated");
            return inspectCount == 1 ? initialInspection : regeneratedInspection;
        }

        @Override
        public CompletableFuture<Boolean> evacuatePlayers(World resetWorld) {
            calls.add("evacuate");
            return evacuationResult;
        }

        @Override
        public boolean hasPlayers(World world) {
            calls.add("hasPlayers");
            return hasPlayers;
        }
    }

    private static final class FakeLifecycleService implements FarmworldLifecycleService {

        private final List<String> calls;
        private CompletableFuture<World> result;
        private World receivedWorld;
        private FarmworldRegenerationOptions receivedOptions;
        private int invocations;

        private FakeLifecycleService(List<String> calls, CompletableFuture<World> result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public CompletableFuture<World> regenerate(
                World world,
                FarmworldRegenerationOptions options
        ) {
            calls.add("regenerate");
            receivedWorld = world;
            receivedOptions = options;
            invocations++;
            return result;
        }
    }

    private static final class FakePostResetInitializer implements FarmworldPostResetInitializer {

        private final List<String> calls;
        private CompletableFuture<PostResetResult> result =
                CompletableFuture.completedFuture(PostResetResult.success());
        private FarmworldResetConfig receivedConfig;
        private ResetOptions receivedOptions;
        private boolean resetScopeClosed;

        private FakePostResetInitializer(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public ResetScope beginReset(FarmworldResetConfig config, ResetOptions options) {
            resetScopeClosed = false;
            return () -> resetScopeClosed = true;
        }

        @Override
        public CompletableFuture<PostResetResult> apply(
                FarmworldResetConfig config,
                World regeneratedWorld,
                ResetOptions options
        ) {
            calls.add("postReset");
            receivedConfig = config;
            receivedOptions = options;
            return result;
        }
    }

    private static final class TestResetStateRepository implements ResetStateRepository {

        private final List<String> calls;
        private Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        private boolean failSaves;

        private TestResetStateRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Map<String, FarmworldResetState> load() {
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
            calls.add("state");
            if (failSaves) {
                throw new IOException("state failed");
            }
            this.states = new LinkedHashMap<>(states);
        }
    }
}
