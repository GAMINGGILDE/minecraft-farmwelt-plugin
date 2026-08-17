package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FarmworldResetEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-17T07:00:00Z");

    private final List<String> calls = new ArrayList<>();
    private final TestResetStateRepository repository = new TestResetStateRepository(calls);
    private final FakeWorldOperations worldOperations = new FakeWorldOperations(calls);
    private final FakeWorldDirectoryOperations directoryOperations =
            new FakeWorldDirectoryOperations(calls);
    private final FarmworldResetService resetService = new FarmworldResetService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            quietLogger()
    );
    private final FarmworldResetEngine engine = new FarmworldResetEngine(
            resetService,
            worldOperations,
            directoryOperations,
            new DirectScheduler(),
            quietLogger()
    );

    @BeforeEach
    void configureReset() {
        resetService.reload(List.of(config()));
        calls.clear();
    }

    @Test
    void executesHappyPathInOrder() {
        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(
                "inspect",
                "evacuate",
                "hasPlayers",
                "unload",
                "isLoaded",
                "delete",
                "create",
                "state"
        ), calls);
        FarmworldResetState state = resetService.getState("overworld").orElseThrow();
        assertEquals(NOW, state.lastReset().orElseThrow());
        assertEquals(NOW.plus(Duration.ofDays(30)), state.nextReset());
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void evacuationFailureStopsBeforeUnloadAndReleasesLock() {
        worldOperations.evacuationResult = CompletableFuture.completedFuture(false);

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertFalse(calls.contains("unload"));
        assertFalse(calls.contains("delete"));
        assertFalse(calls.contains("create"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void unloadFailureStopsBeforeDelete() {
        worldOperations.unloadResult = false;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.UNLOAD_FAILED, result.status());
        assertFalse(calls.contains("delete"));
        assertFalse(calls.contains("create"));
    }

    @Test
    void deleteFailureStopsBeforeCreateAndStateUpdate() {
        directoryOperations.deleteFailure = new IOException("delete failed");

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.DELETE_FAILED, result.status());
        assertFalse(calls.contains("create"));
        assertFalse(calls.contains("state"));
    }

    @Test
    void createFailureStopsBeforeStateUpdate() {
        worldOperations.createResult = false;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.CREATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void stateFailureIsNotReportedAsSuccessAfterWorldCreation() {
        repository.failSaves = true;
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.STATE_SAVE_FAILED, result.status());
        assertTrue(calls.contains("create"));
        assertTrue(calls.contains("state"));
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
    }

    @Test
    void concurrentResetOfSameKeyIsRejectedAndFailureReleasesLock() {
        CompletableFuture<Boolean> pendingEvacuation = new CompletableFuture<>();
        worldOperations.evacuationResult = pendingEvacuation;

        CompletableFuture<ResetResult> firstReset = engine.reset("overworld");
        ResetResult concurrentResult = engine.reset("overworld").join();

        assertTrue(engine.isResetRunning("overworld"));
        assertEquals(ResetStatus.ALREADY_RUNNING, concurrentResult.status());

        pendingEvacuation.complete(false);
        assertEquals(ResetStatus.EVACUATION_FAILED, firstReset.join().status());
        assertFalse(engine.isResetRunning("overworld"));
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
    void unloadedWorldWithSafeDirectoryCanBeResetWithoutEvacuationOrUnload() {
        worldOperations.inspection = WorldInspection.unloaded();
        worldOperations.loaded = false;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertFalse(calls.contains("evacuate"));
        assertFalse(calls.contains("unload"));
        assertTrue(calls.contains("delete"));
    }

    @Test
    void missingLoadedWorldAndDirectoryReturnsWorldNotFound() {
        worldOperations.inspection = WorldInspection.unloaded();
        worldOperations.loaded = false;
        directoryOperations.worldExists = false;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.WORLD_NOT_FOUND, result.status());
        assertFalse(calls.contains("delete"));
        assertFalse(calls.contains("create"));
    }

    @Test
    void reloadDuringResetKeepsLockAndOriginalIntervalSnapshot() {
        CompletableFuture<Boolean> pendingEvacuation = new CompletableFuture<>();
        worldOperations.evacuationResult = pendingEvacuation;
        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");

        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                true,
                Duration.ofDays(60)
        )));

        assertTrue(engine.isResetRunning("overworld"));
        assertEquals(ResetStatus.ALREADY_RUNNING, engine.reset("overworld").join().status());
        pendingEvacuation.complete(true);

        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        assertEquals(Duration.ofDays(60), resetService.getConfig("overworld").orElseThrow().interval());
        assertEquals(
                NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset()
        );
    }

    private FarmworldResetConfig config() {
        return new FarmworldResetConfig("overworld", "farmwelt", true, Duration.ofDays(30));
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
        private WorldInspection inspection = WorldInspection.loaded(
                Path.of("worlds", "farmwelt").toAbsolutePath().normalize(),
                FarmworldType.OVERWORLD
        );
        private CompletableFuture<Boolean> evacuationResult = CompletableFuture.completedFuture(true);
        private boolean hasPlayers;
        private boolean unloadResult = true;
        private boolean loaded = true;
        private boolean createResult = true;

        private FakeWorldOperations(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public WorldInspection inspect(FarmworldResetConfig resetConfig) {
            calls.add("inspect");
            return inspection;
        }

        @Override
        public CompletableFuture<Boolean> evacuatePlayers(FarmworldResetConfig resetConfig) {
            calls.add("evacuate");
            return evacuationResult;
        }

        @Override
        public boolean hasPlayers(FarmworldResetConfig resetConfig) {
            calls.add("hasPlayers");
            return hasPlayers;
        }

        @Override
        public boolean unload(FarmworldResetConfig resetConfig) {
            calls.add("unload");
            if (unloadResult) {
                loaded = false;
            }
            return unloadResult;
        }

        @Override
        public boolean isLoaded(FarmworldResetConfig resetConfig) {
            calls.add("isLoaded");
            return loaded;
        }

        @Override
        public boolean createAndValidate(FarmworldResetConfig resetConfig) {
            calls.add("create");
            return createResult;
        }
    }

    private static final class FakeWorldDirectoryOperations implements WorldDirectoryOperations {

        private final List<String> calls;
        private final Path worldDirectory = Path.of("worlds", "farmwelt").toAbsolutePath().normalize();
        private IOException deleteFailure;
        private boolean worldExists = true;

        private FakeWorldDirectoryOperations(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Path resolveWorldDirectory(String worldName) {
            return worldDirectory;
        }

        @Override
        public boolean exists(Path candidate) {
            return worldExists;
        }

        @Override
        public void deleteRecursively(Path candidate) throws IOException {
            calls.add("delete");
            if (deleteFailure != null) {
                throw deleteFailure;
            }
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
