package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;

/** Options that must already be applied while Worlds regenerates a farmworld. */
public record FarmworldRegenerationOptions(EndDragonFightDataMode endDragonFightDataMode) {

    private static final FarmworldRegenerationOptions DEFAULTS =
            new FarmworldRegenerationOptions(EndDragonFightDataMode.PRESERVE);

    public FarmworldRegenerationOptions {
        Objects.requireNonNull(endDragonFightDataMode, "endDragonFightDataMode");
    }

    public static FarmworldRegenerationOptions defaults() {
        return DEFAULTS;
    }

    public static FarmworldRegenerationOptions forReset(
            FarmworldResetConfig config,
            ResetOptions resetOptions
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resetOptions, "resetOptions");

        if (config.farmworldType() != FarmworldType.END
                || config.postReset().end().isEmpty()) {
            return defaults();
        }

        boolean spawnDragon = resetOptions.allowEnderDragon()
                || config.postReset().end().orElseThrow().dragon();
        return new FarmworldRegenerationOptions(
                spawnDragon
                        ? EndDragonFightDataMode.INITIAL_FIGHT
                        : EndDragonFightDataMode.SUPPRESSED
        );
    }

    public boolean resetsEndDragonFightData() {
        return endDragonFightDataMode != EndDragonFightDataMode.PRESERVE;
    }

    /** Saved-data state to install while the End world is unloaded. */
    public enum EndDragonFightDataMode {
        PRESERVE,
        SUPPRESSED,
        INITIAL_FIGHT
    }
}
