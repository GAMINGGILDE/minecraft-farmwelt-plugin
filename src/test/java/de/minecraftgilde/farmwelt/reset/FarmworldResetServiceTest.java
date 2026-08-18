package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class FarmworldResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T06:00:00Z");

    @Test
    void initializesAndImmediatelyPersistsMissingState() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);

        service.reload(List.of(config(Duration.ofDays(30))));

        FarmworldResetState state = service.getState("overworld").orElseThrow();
        assertTrue(state.lastReset().isEmpty());
        assertEquals(Instant.parse("2026-09-16T06:00:00Z"), state.nextReset());
        assertEquals(state, repository.states.get("overworld"));
        assertEquals(1, repository.saveCount);
    }

    @Test
    void keepsPersistentNextResetAcrossRestart() {
        Instant storedNextReset = Instant.parse("2026-09-01T00:00:00Z");
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository(Map.of(
                "overworld",
                new FarmworldResetState("overworld", Optional.empty(), storedNextReset)
        ));

        FarmworldResetService service = createService(repository);
        service.reload(List.of(config(Duration.ofDays(30))));

        assertEquals(storedNextReset, service.getState("overworld").orElseThrow().nextReset());
        assertEquals(0, repository.saveCount);
    }

    @Test
    void intervalChangeDoesNotMovePersistentNextReset() {
        Instant storedNextReset = Instant.parse("2026-09-01T00:00:00Z");
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository(Map.of(
                "overworld",
                new FarmworldResetState("overworld", Optional.empty(), storedNextReset)
        ));
        FarmworldResetService service = createService(repository);

        service.reload(List.of(config(Duration.ofDays(30))));
        service.reload(List.of(config(Duration.ofDays(60))));

        assertEquals(Duration.ofDays(60), service.getConfig("overworld").orElseThrow().interval());
        assertEquals(storedNextReset, service.getState("overworld").orElseThrow().nextReset());
        assertEquals(0, repository.saveCount);
    }

    @Test
    void failedReloadKeepsPreviousConfigurationAndStateTogether() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        assertTrue(service.reload(List.of(config(Duration.ofDays(30)))));
        FarmworldResetState previousState = service.getState("overworld").orElseThrow();
        repository.failLoads = true;

        assertFalse(service.reload(List.of(config(Duration.ofDays(60)))));

        assertEquals(Duration.ofDays(30), service.getConfig("overworld").orElseThrow().interval());
        assertEquals(previousState, service.getState("overworld").orElseThrow());
    }

    @Test
    void loadsLastResetAndRetainsUnknownStates() {
        Instant lastReset = Instant.parse("2026-08-01T00:00:00Z");
        FarmworldResetState activeState = new FarmworldResetState(
                "overworld",
                Optional.of(lastReset),
                Instant.parse("2026-09-01T00:00:00Z")
        );
        FarmworldResetState removedState = new FarmworldResetState(
                "removed-world",
                Optional.empty(),
                Instant.parse("2026-10-01T00:00:00Z")
        );
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository(Map.of(
                "overworld", activeState,
                "removed-world", removedState
        ));

        FarmworldResetService service = createService(repository);
        service.reload(List.of(config(Duration.ofDays(30))));

        assertEquals(Optional.of(lastReset), service.getState("overworld").orElseThrow().lastReset());
        assertFalse(service.getState("removed-world").isPresent());
        assertEquals(removedState, repository.states.get("removed-world"));
    }

    @Test
    void disabledConfigDoesNotCreateState() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig disabledConfig = new FarmworldResetConfig(
                "overworld", "farmwelt", false, Duration.ofDays(30)
        );

        service.reload(List.of(disabledConfig));

        assertTrue(service.getState("overworld").isEmpty());
        assertEquals(0, repository.saveCount);
    }

    @Test
    void enablingPreviouslyDisabledConfigInitializesAndPersistsState() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig disabledConfig = new FarmworldResetConfig(
                "overworld", "farmwelt", false, Duration.ofDays(30)
        );

        assertTrue(service.reload(List.of(disabledConfig)));
        assertTrue(service.reload(List.of(config(Duration.ofDays(30)))));

        FarmworldResetState initializedState = service.getState("overworld").orElseThrow();
        assertTrue(initializedState.lastReset().isEmpty());
        assertEquals(NOW.plus(Duration.ofDays(30)), initializedState.nextReset());
        assertEquals(initializedState, repository.states.get("overworld"));
        assertEquals(1, repository.saveCount);
    }

    @Test
    void reenabledConfigReusesExistingPersistentState() {
        FarmworldResetState existingState = new FarmworldResetState(
                "overworld",
                Optional.of(Instant.parse("2026-07-01T00:00:00Z")),
                Instant.parse("2026-09-01T00:00:00Z")
        );
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository(Map.of(
                "overworld", existingState
        ));
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig disabledConfig = new FarmworldResetConfig(
                "overworld", "farmwelt", false, Duration.ofDays(30)
        );

        assertTrue(service.reload(List.of(disabledConfig)));
        assertTrue(service.reload(List.of(config(Duration.ofDays(60)))));

        assertEquals(existingState, service.getState("overworld").orElseThrow());
        assertEquals(existingState, repository.states.get("overworld"));
        assertEquals(0, repository.saveCount);
    }

    @Test
    void completesResetWithSnapshotIntervalAndPersistsState() throws IOException {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig resetConfig = config(Duration.ofDays(30));
        service.reload(List.of(resetConfig));

        Instant completion = Instant.parse("2026-08-17T07:00:00Z");
        FarmworldResetState completedState = service.completeReset(resetConfig, completion);

        assertEquals(Optional.of(completion), completedState.lastReset());
        assertEquals(Instant.parse("2026-09-16T07:00:00Z"), completedState.nextReset());
        assertEquals(completedState, service.getState("overworld").orElseThrow());
        assertEquals(completedState, repository.states.get("overworld"));
    }

    @Test
    void failedCompletionSaveDoesNotPublishCandidateState() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig resetConfig = config(Duration.ofDays(30));
        service.reload(List.of(resetConfig));
        FarmworldResetState stateBeforeCompletion = service.getState("overworld").orElseThrow();
        repository.failSaves = true;

        assertThrows(
                IOException.class,
                () -> service.completeReset(resetConfig, Instant.parse("2026-08-17T07:00:00Z"))
        );

        assertEquals(stateBeforeCompletion, service.getState("overworld").orElseThrow());
        assertEquals(stateBeforeCompletion, repository.states.get("overworld"));
    }

    @Test
    void completingOneFarmworldKeepsOtherPersistentStatesUnchanged() throws IOException {
        FarmworldResetState overworldState = state(
                "overworld",
                "2026-07-01T00:00:00Z",
                "2026-09-01T00:00:00Z"
        );
        FarmworldResetState netherState = state(
                "nether",
                "2026-07-02T00:00:00Z",
                "2026-09-02T00:00:00Z"
        );
        FarmworldResetState endState = state(
                "end",
                "2026-07-03T00:00:00Z",
                "2026-10-03T00:00:00Z"
        );
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository(Map.of(
                "overworld", overworldState,
                "nether", netherState,
                "end", endState
        ));
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig overworldConfig = config(Duration.ofDays(30));
        service.reload(List.of(
                overworldConfig,
                new FarmworldResetConfig("nether", "farmwelt_nether", true, Duration.ofDays(30)),
                new FarmworldResetConfig("end", "farmwelt_end", true, Duration.ofDays(60))
        ));

        Instant completion = Instant.parse("2026-08-17T07:00:00Z");
        FarmworldResetState completedState = service.completeReset(overworldConfig, completion);

        assertEquals(Optional.of(completion), completedState.lastReset());
        assertEquals(completion.plus(Duration.ofDays(30)), completedState.nextReset());
        assertEquals(completedState, repository.states.get("overworld"));
        assertEquals(netherState, repository.states.get("nether"));
        assertEquals(endState, repository.states.get("end"));
    }

    @Test
    void unknownFarmworldCannotCreateCompletionState() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        service.reload(List.of(config(Duration.ofDays(30))));

        assertThrows(IllegalArgumentException.class, () -> service.completeReset("foo"));
        assertTrue(service.getState("foo").isEmpty());
        assertFalse(repository.states.containsKey("foo"));
    }

    @Test
    void disabledFarmworldCannotCompleteReset() {
        InMemoryResetStateRepository repository = new InMemoryResetStateRepository();
        FarmworldResetService service = createService(repository);
        FarmworldResetConfig disabledConfig = new FarmworldResetConfig(
                "overworld", "farmwelt", false, Duration.ofDays(30)
        );
        service.reload(List.of(disabledConfig));

        assertThrows(IllegalStateException.class, () -> service.completeReset("overworld"));
        assertTrue(service.getState("overworld").isEmpty());
    }

    private FarmworldResetService createService(InMemoryResetStateRepository repository) {
        Logger logger = Logger.getLogger("FarmworldResetServiceTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return new FarmworldResetService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                logger
        );
    }

    private FarmworldResetConfig config(Duration interval) {
        return new FarmworldResetConfig("overworld", "farmwelt", true, interval);
    }

    private FarmworldResetState state(String farmworldKey, String lastReset, String nextReset) {
        return new FarmworldResetState(
                farmworldKey,
                Optional.of(Instant.parse(lastReset)),
                Instant.parse(nextReset)
        );
    }

    private static final class InMemoryResetStateRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states;
        private int saveCount;
        private boolean failLoads;
        private boolean failSaves;

        private InMemoryResetStateRepository() {
            this(Map.of());
        }

        private InMemoryResetStateRepository(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
        }

        @Override
        public Map<String, FarmworldResetState> load() throws IOException {
            if (failLoads) {
                throw new IOException("simulierter Ladefehler");
            }
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
            if (failSaves) {
                throw new IOException("simulierter Persistenzfehler");
            }
            this.states = new LinkedHashMap<>(states);
            saveCount++;
        }
    }
}
