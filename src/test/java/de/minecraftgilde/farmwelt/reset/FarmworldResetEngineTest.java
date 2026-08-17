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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FarmworldResetEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-17T07:00:00Z");

    private final List<String> calls = new ArrayList<>();
    private final TestResetStateRepository repository = new TestResetStateRepository(calls);
    private final World originalWorld = world("farmwelt", World.Environment.NORMAL, "old-farmwelt");
    private final World regeneratedWorld = world("farmwelt", World.Environment.NORMAL, "new-farmwelt");
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
    private final FarmworldResetEngine engine = new FarmworldResetEngine(
            resetService,
            worldOperations,
            lifecycleService,
            new DirectScheduler(),
            quietLogger()
    );

    @BeforeEach
    void configureReset() {
        resetService.reload(List.of(config()));
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
                "state"
        ), calls);
        assertSame(originalWorld, lifecycleService.receivedWorld);
        FarmworldResetState state = resetService.getState("overworld").orElseThrow();
        assertEquals(NOW, state.lastReset().orElseThrow());
        assertEquals(NOW.plus(Duration.ofDays(30)), state.nextReset());
        assertFalse(engine.isResetRunning("overworld"));
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
        assertEquals(
                NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset()
        );
    }

    private FarmworldResetConfig config() {
        return new FarmworldResetConfig("overworld", "farmwelt", true, Duration.ofDays(30));
    }

    private static World world(String name, World.Environment environment, String directory) {
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

    private static final class DirectScheduler implements FarmweltScheduler {

        @Override
        public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
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
        private int invocations;

        private FakeLifecycleService(List<String> calls, CompletableFuture<World> result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public CompletableFuture<World> regenerate(World world) {
            calls.add("regenerate");
            receivedWorld = world;
            invocations++;
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
