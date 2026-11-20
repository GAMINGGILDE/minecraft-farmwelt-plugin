package de.minecraftgilde.farmwelt.reset;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent schedule state for one logical farmworld.
 */
public record FarmworldResetState(
        String farmworldKey,
        Optional<Instant> lastReset,
        Instant nextReset,
        Optional<UUID> attemptId
) {

    public FarmworldResetState(String farmworldKey, Optional<Instant> lastReset, Instant nextReset) {
        this(farmworldKey, lastReset, nextReset, Optional.empty());
    }

    public FarmworldResetState {
        Objects.requireNonNull(attemptId, "attemptId");
        if (farmworldKey == null || farmworldKey.isBlank()) {
            throw new IllegalArgumentException("farmworldKey darf nicht leer sein.");
        }

        lastReset = Objects.requireNonNull(lastReset, "lastReset");
        nextReset = Objects.requireNonNull(nextReset, "nextReset");
    }
}
