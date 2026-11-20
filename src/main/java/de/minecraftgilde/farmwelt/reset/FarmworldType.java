package de.minecraftgilde.farmwelt.reset;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dimension of a logical farmworld. This is deliberately independent of the configured Bukkit
 * world name.
 */
public enum FarmworldType {
    OVERWORLD,
    NETHER,
    END;

    private final String key = name().toLowerCase(Locale.ROOT);
    private static final Map<String, FarmworldType> BY_KEY = Map.of(
            OVERWORLD.key, OVERWORLD, NETHER.key, NETHER, END.key, END);

    public String key() {
        return key;
    }

    /** Unbekannte Menü-IDs und archivierte Zeitpläne behalten ihren externen Namen. */
    public static String canonicalKey(String key) {
        return fromFarmworldKey(key).map(FarmworldType::key).orElse(key);
    }

    public static Optional<FarmworldType> fromFarmworldKey(String farmworldKey) {
        if (farmworldKey == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(BY_KEY.get(farmworldKey.toLowerCase(Locale.ROOT)));
    }
}
