package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable Konfiguration für die Kommunikation rund um Farmwelt-Resets. */
public record ResetNotificationConfig(
        boolean enabled,
        List<Duration> warnings,
        String warningMessage,
        ResetNotificationMessageConfig resetStart,
        ResetNotificationMessageConfig resetSuccess,
        ResetNotificationMessageConfig resetFailure,
        ResetNotificationMessageConfig evacuation
) {

    private static final ResetNotificationConfig DEFAULTS = new ResetNotificationConfig(
            true,
            List.of(
                    Duration.ofHours(1),
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1)
            ),
            "&eDie &6{world}&e wird in &6{time}&e zurückgesetzt.",
            new ResetNotificationMessageConfig(
                    true,
                    "&eDie &6{world}&e wird jetzt zurückgesetzt."
            ),
            new ResetNotificationMessageConfig(
                    true,
                    "&aDie &6{world}&a wurde erfolgreich zurückgesetzt."
            ),
            new ResetNotificationMessageConfig(
                    false,
                    "&cDer Reset der &6{world}&c konnte nicht abgeschlossen werden."
            ),
            new ResetNotificationMessageConfig(
                    true,
                    "&eDu wurdest aus der &6{world}&e teleportiert, da sie gerade zurückgesetzt wird."
            )
    );

    public ResetNotificationConfig {
        Objects.requireNonNull(warnings, "warnings");
        Objects.requireNonNull(warningMessage, "warningMessage");
        Objects.requireNonNull(resetStart, "resetStart");
        Objects.requireNonNull(resetSuccess, "resetSuccess");
        Objects.requireNonNull(resetFailure, "resetFailure");
        Objects.requireNonNull(evacuation, "evacuation");
        if (warningMessage.isBlank()) {
            throw new IllegalArgumentException("warningMessage darf nicht leer sein.");
        }

        TreeSet<Duration> normalizedWarnings = new TreeSet<>(Comparator.reverseOrder());
        for (Duration warning : warnings) {
            Objects.requireNonNull(warning, "warning");
            if (warning.isZero() || warning.isNegative()) {
                throw new IllegalArgumentException("Warning-Zeitpunkte müssen positiv sein.");
            }
            normalizedWarnings.add(warning);
        }
        warnings = List.copyOf(normalizedWarnings);
    }

    public static ResetNotificationConfig defaults() {
        return DEFAULTS;
    }
}
