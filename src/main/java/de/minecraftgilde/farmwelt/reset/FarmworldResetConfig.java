package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable, validated reset configuration for one logical farmworld.
 */
public record FarmworldResetConfig(
        String farmworldKey,
        String worldName,
        String displayName,
        boolean enabled,
        Duration interval,
        FarmworldType farmworldType,
        PostResetConfig postReset,
        ResetNotificationConfig notifications
) {

    public FarmworldResetConfig(
            String farmworldKey,
            String worldName,
            boolean enabled,
            Duration interval
    ) {
        this(
                farmworldKey,
                worldName,
                defaultDisplayName(farmworldKey),
                enabled,
                interval,
                FarmworldType.fromFarmworldKey(farmworldKey).orElseThrow(
                        () -> new IllegalArgumentException("Unbekannter Farmwelt-Typ: " + farmworldKey)
                ),
                PostResetConfig.none(),
                ResetNotificationConfig.defaults()
        );
    }

    public FarmworldResetConfig(
            String farmworldKey,
            String worldName,
            boolean enabled,
            Duration interval,
            FarmworldType farmworldType
    ) {
        this(
                farmworldKey,
                worldName,
                defaultDisplayName(farmworldKey),
                enabled,
                interval,
                farmworldType,
                PostResetConfig.none(),
                ResetNotificationConfig.defaults()
        );
    }

    public FarmworldResetConfig(
            String farmworldKey,
            String worldName,
            boolean enabled,
            Duration interval,
            FarmworldType farmworldType,
            PostResetConfig postReset
    ) {
        this(
                farmworldKey,
                worldName,
                defaultDisplayName(farmworldKey),
                enabled,
                interval,
                farmworldType,
                postReset,
                ResetNotificationConfig.defaults()
        );
    }

    public FarmworldResetConfig(
            String farmworldKey,
            String worldName,
            boolean enabled,
            Duration interval,
            FarmworldType farmworldType,
            PostResetConfig postReset,
            ResetNotificationConfig notifications
    ) {
        this(
                farmworldKey,
                worldName,
                defaultDisplayName(farmworldKey),
                enabled,
                interval,
                farmworldType,
                postReset,
                notifications
        );
    }

    public FarmworldResetConfig {
        if (farmworldKey == null || farmworldKey.isBlank()) {
            throw new IllegalArgumentException("farmworldKey darf nicht leer sein.");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName darf nicht leer sein.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName darf nicht leer sein.");
        }

        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(farmworldType, "farmworldType");
        Objects.requireNonNull(postReset, "postReset");
        Objects.requireNonNull(notifications, "notifications");
        FarmworldType.fromFarmworldKey(farmworldKey).ifPresent(expectedType -> {
            if (expectedType != farmworldType) {
                throw new IllegalArgumentException(
                        "farmworldType passt nicht zur logischen Farmwelt-ID '" + farmworldKey + "'."
                );
            }
        });
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval muss positiv sein.");
        }
    }

    private static String defaultDisplayName(String farmworldKey) {
        return FarmworldType.fromFarmworldKey(farmworldKey)
                .map(type -> switch (type) {
                    case OVERWORLD -> "Farmwelt";
                    case NETHER -> "Netherfarm";
                    case END -> "Endfarm";
                })
                .orElse(farmworldKey);
    }
}
