package de.minecraftgilde.farmwelt.reset;

import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.INITIAL_FIGHT;
import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.PRESERVE;
import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FarmworldRegenerationOptionsTest {

    @Test
    void selectsFreshFightDataForTheEffectiveEndDragonPolicy() {
        FarmworldResetConfig dragonSuppressed = config(
                FarmworldType.END,
                Optional.of(new EndPostResetConfig(false))
        );

        assertEquals(SUPPRESSED, FarmworldRegenerationOptions.forReset(
                dragonSuppressed,
                ResetOptions.defaults()
        ).endDragonFightDataMode());
        assertEquals(INITIAL_FIGHT, FarmworldRegenerationOptions.forReset(
                dragonSuppressed,
                ResetOptions.allowingEnderDragon()
        ).endDragonFightDataMode());
        assertEquals(INITIAL_FIGHT, FarmworldRegenerationOptions.forReset(
                config(FarmworldType.END, Optional.of(new EndPostResetConfig(true))),
                ResetOptions.defaults()
        ).endDragonFightDataMode());
        assertEquals(PRESERVE, FarmworldRegenerationOptions.forReset(
                config(FarmworldType.OVERWORLD, Optional.empty()),
                ResetOptions.defaults()
        ).endDragonFightDataMode());
        assertEquals(PRESERVE, FarmworldRegenerationOptions.forReset(
                config(FarmworldType.NETHER, Optional.empty()),
                ResetOptions.defaults()
        ).endDragonFightDataMode());
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
