package de.minecraftgilde.farmwelt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldType;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FarmworldResetConfigParserTest {

    private final FarmworldResetConfigParser parser =
            new FarmworldResetConfigParser(quietLogger());

    @Test
    void parsesValidOverworldConfiguration() throws Exception {
        List<FarmworldResetConfig> configs = parse("""
                farmworlds:
                  overworld:
                    enabled: true
                    reset:
                      enabled: true
                      world: "farmwelt"
                      interval: "30d"
                """);

        assertEquals(1, configs.size());
        FarmworldResetConfig config = configs.getFirst();
        assertTrue(config.enabled());
        assertEquals("farmwelt", config.worldName());
        assertEquals(Duration.ofDays(30), config.interval());
        assertEquals(FarmworldType.OVERWORLD, config.farmworldType());
    }

    @Test
    void parsesIndependentIntervalsForAllSupportedFarmworlds() throws Exception {
        List<FarmworldResetConfig> configs = parse("""
                farmworlds:
                  overworld:
                    enabled: true
                    reset:
                      enabled: true
                      world: "farmwelt"
                      interval: "30d"
                  nether:
                    enabled: true
                    reset:
                      enabled: true
                      world: "netherfarm"
                      interval: "45d"
                  end:
                    enabled: true
                    reset:
                      enabled: true
                      world: "endfarm"
                      interval: "60d"
                """);

        assertEquals(List.of(
                Duration.ofDays(30),
                Duration.ofDays(45),
                Duration.ofDays(60)
        ), configs.stream().map(FarmworldResetConfig::interval).toList());
        assertEquals(List.of(
                FarmworldType.OVERWORLD,
                FarmworldType.NETHER,
                FarmworldType.END
        ), configs.stream().map(FarmworldResetConfig::farmworldType).toList());
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, true",
            "true, false, false",
            "false, true, false",
            "false, false, false"
    })
    void combinesFarmworldAndResetEnabledFlags(
            boolean farmworldEnabled,
            boolean resetEnabled,
            boolean expectedEnabled
    ) throws Exception {
        List<FarmworldResetConfig> configs = parse("""
                farmworlds:
                  overworld:
                    enabled: %s
                    reset:
                      enabled: %s
                      world: "farmwelt"
                      interval: "30d"
                """.formatted(farmworldEnabled, resetEnabled));

        assertEquals(1, configs.size());
        assertEquals(expectedEnabled, configs.getFirst().enabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0d", "-1d", "abc", "1s", "1w", "1mo", "30days", "1.5d",
            "30 d", "P30D", "0 0 4 * * *"
    })
    void rejectsUnsupportedIntervals(String interval) throws Exception {
        assertTrue(parse(singleFarmworld("world: \"farmwelt\"\n      interval: \""
                + interval + "\"\n")).isEmpty());
    }

    @Test
    void rejectsMissingInterval() throws Exception {
        assertTrue(parse(singleFarmworld("world: \"farmwelt\"\n")).isEmpty());
    }

    @Test
    void rejectsMissingWorldName() throws Exception {
        assertTrue(parse(singleFarmworld("interval: \"30d\"\n")).isEmpty());
    }

    @Test
    void rejectsUnknownFarmworldId() throws Exception {
        assertTrue(parse("""
                farmworlds:
                  moon:
                    enabled: true
                    reset:
                      enabled: true
                      world: "moon"
                      interval: "30d"
                """).isEmpty());
    }

    @Test
    void trimsOuterWhitespaceFromIntervalAndWorldName() throws Exception {
        FarmworldResetConfig config = parse(singleFarmworld(
                "world: \"  farmwelt  \"\n      interval: \" 30d \"\n"
        )).getFirst();

        assertEquals("farmwelt", config.worldName());
        assertEquals(Duration.ofDays(30), config.interval());
    }

    @Test
    void rejectsIntervalThatOverflowsDuration() throws Exception {
        assertTrue(parse(singleFarmworld(
                "world: \"farmwelt\"\n      interval: \"" + Long.MAX_VALUE + "d\"\n"
        )).isEmpty());
    }

    @Test
    void invalidPostResetDisablesOnlyAffectedConfiguration() throws Exception {
        List<FarmworldResetConfig> configs = parse("""
                farmworlds:
                  overworld:
                    enabled: true
                    reset:
                      enabled: true
                      world: "farmwelt"
                      interval: "30d"
                      post-reset:
                        world-border:
                          size: 0
                  nether:
                    enabled: true
                    reset:
                      enabled: true
                      world: "netherfarm"
                      interval: "45d"
                """);

        assertEquals(1, configs.size());
        assertEquals("nether", configs.getFirst().farmworldKey());
        assertFalse(configs.getFirst().postReset().worldBorder().isPresent());
    }

    private List<FarmworldResetConfig> parse(String content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        return parser.parse(yaml.getConfigurationSection("farmworlds"));
    }

    private static String singleFarmworld(String resetValues) {
        return """
                farmworlds:
                  overworld:
                    enabled: true
                    reset:
                      enabled: true
                      %s""".formatted(resetValues);
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("FarmworldResetConfigParserTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }
}
