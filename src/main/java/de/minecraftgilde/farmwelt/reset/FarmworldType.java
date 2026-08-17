package de.minecraftgilde.farmwelt.reset;

import java.util.Locale;
import java.util.Optional;

/**
 * Dimension of a logical farmworld. This is deliberately independent of the configured Bukkit
 * world name.
 */
public enum FarmworldType {
    OVERWORLD,
    NETHER,
    END;

    public static Optional<FarmworldType> fromFarmworldKey(String farmworldKey) {
        if (farmworldKey == null) {
            return Optional.empty();
        }

        return switch (farmworldKey.toLowerCase(Locale.ROOT)) {
            case "overworld" -> Optional.of(OVERWORLD);
            case "nether" -> Optional.of(NETHER);
            case "end" -> Optional.of(END);
            default -> Optional.empty();
        };
    }
}
