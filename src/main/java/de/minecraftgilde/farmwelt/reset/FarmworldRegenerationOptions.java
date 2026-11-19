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

        // Ohne End-Abschnitt betrifft --dragon ausschließlich den geladenen Kampf.
        if (config.postReset().end().isEmpty()) {
            return defaults();
        }

        return new FarmworldRegenerationOptions(effectiveDragonPolicy(config, resetOptions));
    }

    static EndDragonFightDataMode effectiveDragonPolicy(FarmworldResetConfig config, ResetOptions options) {
        if (!config.enabled() || config.farmworldType() != FarmworldType.END) {
            return EndDragonFightDataMode.PRESERVE;
        }
        if (options.allowEnderDragon()) {
            return EndDragonFightDataMode.INITIAL_FIGHT;
        }
        return config.postReset().end()
                .map(end -> end.dragon() ? EndDragonFightDataMode.INITIAL_FIGHT : EndDragonFightDataMode.SUPPRESSED)
                .orElse(EndDragonFightDataMode.PRESERVE);
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
