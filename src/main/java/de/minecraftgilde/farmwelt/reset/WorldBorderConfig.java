package de.minecraftgilde.farmwelt.reset;

/** Immutable post-reset world-border configuration. */
public record WorldBorderConfig(double size) {

    public WorldBorderConfig {
        if (!Double.isFinite(size) || size < 1.0D) {
            throw new IllegalArgumentException("world-border.size muss mindestens 1 betragen.");
        }
    }
}
