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

    private static final class InMemoryResetStateRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states;
        private int saveCount;
        private boolean failSaves;

        private InMemoryResetStateRepository() {
            this(Map.of());
        }

        private InMemoryResetStateRepository(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
        }

        @Override
        public Map<String, FarmworldResetState> load() {
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
