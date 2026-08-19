package de.minecraftgilde.farmwelt.reset;

import de.minecraftgilde.farmwelt.command.GermanDurationFormatter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Formatiert die konfigurierten Countdown-Nachrichten ohne Bukkit-Abhängigkeit. */
public final class ResetNotificationMessageFormatter {

    private final GermanDurationFormatter durationFormatter = new GermanDurationFormatter();
    private final DateTimeFormatter instantFormatter;

    public ResetNotificationMessageFormatter(ZoneId zoneId) {
        instantFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(Objects.requireNonNull(zoneId, "zoneId"));
    }

    public String formatWarning(
            String template,
            String displayName,
            Duration warning,
            Instant nextReset
    ) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(warning, "warning");
        Objects.requireNonNull(nextReset, "nextReset");
        return template
                .replace("{world}", displayName)
                .replace("{time}", durationFormatter.format(warning))
                .replace("{next-reset}", instantFormatter.format(nextReset));
    }
}
