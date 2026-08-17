package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;

/** Options that must already be applied while Worlds regenerates a farmworld. */
public record FarmworldRegenerationOptions(boolean resetEndDragonFightData) {

    private static final FarmworldRegenerationOptions DEFAULTS =
            new FarmworldRegenerationOptions(false);

    public static FarmworldRegenerationOptions defaults() {
        return DEFAULTS;
    }

    public static FarmworldRegenerationOptions forReset(
            FarmworldResetConfig config,
            ResetOptions resetOptions
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resetOptions, "resetOptions");

        boolean resetFightData = config.farmworldType() == FarmworldType.END
                && !resetOptions.allowEnderDragon()
                && config.postReset().end()
                        .map(endConfig -> !endConfig.dragon())
                        .orElse(false);
        return resetFightData
                ? new FarmworldRegenerationOptions(true)
                : defaults();
    }
}
