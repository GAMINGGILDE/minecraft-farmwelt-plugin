package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable, validated reset configuration for one logical farmworld.
 */
public record FarmworldResetConfig(
        String farmworldKey,
        String worldName,
        boolean enabled,
        Duration interval
) {

    public FarmworldResetConfig {
        if (farmworldKey == null || farmworldKey.isBlank()) {
            throw new IllegalArgumentException("farmworldKey darf nicht leer sein.");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName darf nicht leer sein.");
        }

        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval muss positiv sein.");
        }
    }
}
