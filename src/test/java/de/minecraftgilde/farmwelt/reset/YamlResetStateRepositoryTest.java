package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlResetStateRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsIsoInstantsIncludingLastReset() throws Exception {
        Path stateFile = temporaryDirectory.resolve("reset-state.yml");
        YamlResetStateRepository repository = repository(stateFile);
        FarmworldResetState state = new FarmworldResetState(
                "overworld",
                Optional.of(Instant.parse("2026-08-15T02:00:00Z")),
                Instant.parse("2026-09-14T02:00:00Z")
        );

        repository.save(Map.of("overworld", state));
        Map<String, FarmworldResetState> loadedStates = repository.load();

        assertEquals(state, loadedStates.get("overworld"));
        assertFalse(Files.exists(temporaryDirectory.resolve("reset-state.yml.tmp")));
        String yaml = Files.readString(stateFile, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("version: 1"));
        assertTrue(yaml.contains("2026-09-14T02:00:00Z"));
    }

    @Test
    void skipsInvalidStateWithoutDroppingValidState() throws Exception {
        Path stateFile = temporaryDirectory.resolve("reset-state.yml");
        Files.writeString(stateFile, """
                version: 1
                worlds:
                  overworld:
                    last-reset: null
                    next-reset: "abc"
                  nether:
                    last-reset: "2026-08-01T00:00:00Z"
                    next-reset: "2026-09-01T00:00:00Z"
                """, StandardCharsets.UTF_8);

        Map<String, FarmworldResetState> loadedStates = repository(stateFile).load();

        assertFalse(loadedStates.containsKey("overworld"));
        assertEquals(
                Instant.parse("2026-09-01T00:00:00Z"),
                loadedStates.get("nether").nextReset()
        );
    }

    @Test
    void malformedFileIsReplacedWithInitializedState() throws Exception {
        Path stateFile = temporaryDirectory.resolve("reset-state.yml");
        Files.writeString(stateFile, "worlds: [invalid", StandardCharsets.UTF_8);
        YamlResetStateRepository repository = repository(stateFile);
        Instant now = Instant.parse("2026-08-17T06:00:00Z");
        FarmworldResetService service = new FarmworldResetService(
                repository,
                Clock.fixed(now, ZoneOffset.UTC),
                logger()
        );

        service.reload(List.of(new FarmworldResetConfig(
                "overworld", "farmwelt", true, Duration.ofDays(30)
        )));

        assertEquals(
                Instant.parse("2026-09-16T06:00:00Z"),
                repository.load().get("overworld").nextReset()
        );
    }

    private YamlResetStateRepository repository(Path stateFile) {
        return new YamlResetStateRepository(stateFile, logger());
    }

    private Logger logger() {
        Logger logger = Logger.getLogger("YamlResetStateRepositoryTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }
}
