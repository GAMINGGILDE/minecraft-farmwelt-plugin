package de.minecraftgilde.farmwelt.reset;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent schedule state for one logical farmworld.
 */
public record FarmworldResetState(
        String farmworldKey,
        Optional<Instant> lastReset,
        Instant nextReset
) {

    public FarmworldResetState {
        if (farmworldKey == null || farmworldKey.isBlank()) {
            throw new IllegalArgumentException("farmworldKey darf nicht leer sein.");
        }

        lastReset = Objects.requireNonNull(lastReset, "lastReset");
        nextReset = Objects.requireNonNull(nextReset, "nextReset");
    }
}
