package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FarmworldRegenerationOptionsTest {

    @Test
    void resetsFightDataOnlyForSuppressedEndDragonPolicy() {
        FarmworldResetConfig dragonSuppressed = config(
                FarmworldType.END,
                Optional.of(new EndPostResetConfig(false))
        );

        assertTrue(FarmworldRegenerationOptions.forReset(
                dragonSuppressed,
                ResetOptions.defaults()
        ).resetEndDragonFightData());
        assertFalse(FarmworldRegenerationOptions.forReset(
                dragonSuppressed,
                ResetOptions.allowingEnderDragon()
        ).resetEndDragonFightData());
        assertFalse(FarmworldRegenerationOptions.forReset(
                config(FarmworldType.END, Optional.of(new EndPostResetConfig(true))),
                ResetOptions.defaults()
        ).resetEndDragonFightData());
        assertFalse(FarmworldRegenerationOptions.forReset(
                config(FarmworldType.OVERWORLD, Optional.empty()),
                ResetOptions.defaults()
        ).resetEndDragonFightData());
        assertFalse(FarmworldRegenerationOptions.forReset(
                config(FarmworldType.NETHER, Optional.empty()),
                ResetOptions.defaults()
        ).resetEndDragonFightData());
    }

    private static FarmworldResetConfig config(
            FarmworldType type,
            Optional<EndPostResetConfig> endConfig
    ) {
        String key = switch (type) {
            case OVERWORLD -> "overworld";
            case NETHER -> "nether";
            case END -> "end";
        };
        return new FarmworldResetConfig(
                key,
                key + "farm",
                true,
                Duration.ofDays(30),
                type,
                new PostResetConfig(Map.of(), Optional.empty(), endConfig)
        );
    }
}
