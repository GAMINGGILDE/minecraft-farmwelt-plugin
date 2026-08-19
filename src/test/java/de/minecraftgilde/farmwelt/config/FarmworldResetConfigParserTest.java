package de.minecraftgilde.farmwelt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldType;
import de.minecraftgilde.farmwelt.reset.ResetNotificationConfig;
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

    @Test
    void usesNotificationDefaultsWhenSectionIsMissing() throws Exception {
        FarmworldResetConfig config = parse(singleFarmworld(
                "world: \"farmwelt\"\n      interval: \"30d\"\n"
        )).getFirst();

        assertEquals(ResetNotificationConfig.defaults(), config.notifications());
    }

    @Test
    void parsesDisabledNotifications() throws Exception {
        FarmworldResetConfig config = parseNotifications("""
                enabled: false
                """);

        assertFalse(config.notifications().enabled());
        assertEquals(ResetNotificationConfig.defaults().warnings(), config.notifications().warnings());
    }

    @Test
    void parsesAllSupportedNotificationWarningDurations() throws Exception {
        FarmworldResetConfig config = parseNotifications("""
                warnings:
                  - 1m
                  - 30m
                  - 1h
                  - 6h
                  - 1d
                """);

        assertEquals(List.of(
                Duration.ofDays(1),
                Duration.ofHours(6),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                Duration.ofMinutes(1)
        ), config.notifications().warnings());
    }

    @Test
    void ignoresInvalidNotificationWarningsWithoutDisablingReset() throws Exception {
        FarmworldResetConfig config = parseNotifications("""
                warnings:
                  - 0m
                  - -5m
                  - abc
                  - 10
                  - 1w
                """);

        assertTrue(config.enabled());
        assertEquals(Duration.ofDays(30), config.interval());
        assertTrue(config.notifications().warnings().isEmpty());
    }

    @Test
    void keepsValidNotificationWarningsFromMixedList() throws Exception {
        FarmworldResetConfig config = parseNotifications("""
                warnings:
                  - 1h
                  - kaputt
                  - 5m
                """);

        assertEquals(
                List.of(Duration.ofHours(1), Duration.ofMinutes(5)),
                config.notifications().warnings()
        );
    }

    @Test
    void removesDuplicateNotificationWarningsAndSortsDescending() throws Exception {
        FarmworldResetConfig config = parseNotifications("""
                warnings:
                  - 5m
                  - 30m
                  - 1h
                  - 30m
                """);

        assertEquals(List.of(
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        ), config.notifications().warnings());
    }

    @Test
    void fallsBackToDefaultForMissingEmptyAndNonStringMessages() throws Exception {
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();
        FarmworldResetConfig config = parseNotifications("""
                warning-message: "   "
                reset-start:
                  enabled: false
                  message: ""
                reset-success:
                  message: 42
                reset-failure:
                  enabled: true
                  message:
                """);

        assertEquals(defaults.warningMessage(), config.notifications().warningMessage());
        assertFalse(config.notifications().resetStart().enabled());
        assertEquals(defaults.resetStart().message(), config.notifications().resetStart().message());
        assertEquals(defaults.resetSuccess().message(), config.notifications().resetSuccess().message());
        assertTrue(config.notifications().resetFailure().enabled());
        assertEquals(defaults.resetFailure().message(), config.notifications().resetFailure().message());
        assertEquals(defaults.evacuation(), config.notifications().evacuation());
    }

    @Test
    void invalidNotificationStructureFallsBackWithoutAffectingResetConfiguration() throws Exception {
        FarmworldResetConfig config = parse(singleFarmworld("""
                world: "farmwelt"
                      interval: "30d"
                      notifications: kaputt
                """)).getFirst();

        assertTrue(config.enabled());
        assertEquals(Duration.ofDays(30), config.interval());
        assertEquals(ResetNotificationConfig.defaults(), config.notifications());
    }

    private List<FarmworldResetConfig> parse(String content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        return parser.parse(yaml.getConfigurationSection("farmworlds"));
    }

    private FarmworldResetConfig parseNotifications(String notificationValues) throws Exception {
        return parse("""
                farmworlds:
                  overworld:
                    enabled: true
                    reset:
                      enabled: true
                      world: "farmwelt"
                      interval: "30d"
                      notifications:
                %s""".formatted(notificationValues.indent(8))).getFirst();
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
