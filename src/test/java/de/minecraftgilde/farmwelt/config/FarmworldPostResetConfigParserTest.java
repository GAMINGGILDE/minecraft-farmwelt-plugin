package de.minecraftgilde.farmwelt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldType;
import de.minecraftgilde.farmwelt.reset.PostResetConfig;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FarmworldPostResetConfigParserTest {

    private final FarmworldPostResetConfigParser parser =
            new FarmworldPostResetConfigParser(quietLogger());

    @Test
    void parsesGenericGamerulesAndWorldBorder() throws Exception {
        YamlConfiguration yaml = yaml("""
                post-reset:
                  gamerules:
                    players_sleeping_percentage: 50
                    show_advancement_messages: false
                  world-border:
                    size: 20000
                """);

        PostResetConfig config = parser.parse(
                yaml.getConfigurationSection("post-reset"),
                "overworld",
                FarmworldType.OVERWORLD
        );

        assertEquals(50, config.gamerules().get("players_sleeping_percentage"));
        assertEquals(false, config.gamerules().get("show_advancement_messages"));
        assertEquals(20000.0D, config.worldBorder().orElseThrow().size());
        assertTrue(config.end().isEmpty());
    }

    @Test
    void missingPostResetSectionMeansNoSettings() {
        PostResetConfig config = parser.parse(null, "overworld", FarmworldType.OVERWORLD);

        assertTrue(config.gamerules().isEmpty());
        assertTrue(config.worldBorder().isEmpty());
        assertTrue(config.end().isEmpty());
    }

    @Test
    void parsesBothEndDragonPolicies() throws Exception {
        YamlConfiguration disabled = yaml("post-reset:\n  end:\n    dragon: false\n");
        YamlConfiguration enabled = yaml("post-reset:\n  end:\n    dragon: true\n");

        assertFalse(parser.parse(
                disabled.getConfigurationSection("post-reset"), "end", FarmworldType.END
        ).end().orElseThrow().dragon());
        assertTrue(parser.parse(
                enabled.getConfigurationSection("post-reset"), "end", FarmworldType.END
        ).end().orElseThrow().dragon());
    }

    @Test
    void rejectsInvalidWorldBorderValues() throws Exception {
        for (String value : new String[]{"-100", "0.5", "foo"}) {
            YamlConfiguration yaml = yaml(
                    "post-reset:\n  world-border:\n    size: " + value + "\n"
            );

            assertThrows(IllegalArgumentException.class, () -> parser.parse(
                    yaml.getConfigurationSection("post-reset"),
                    "overworld",
                    FarmworldType.OVERWORLD
            ));
        }
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        return yaml;
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("FarmworldPostResetConfigParserTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }
}
