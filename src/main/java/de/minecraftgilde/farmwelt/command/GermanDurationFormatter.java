package de.minecraftgilde.farmwelt.command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bukkit-independent formatting for German command output. */
public final class GermanDurationFormatter {

    public String format(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            return "Überfällig";
        }

        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        List<String> parts = new ArrayList<>(2);
        if (days > 0) {
            parts.add(unit(days, "Tag", "Tage"));
            if (hours > 0) {
                parts.add(unit(hours, "Stunde", "Stunden"));
            }
        } else if (hours > 0) {
            parts.add(unit(hours, "Stunde", "Stunden"));
            if (minutes > 0) {
                parts.add(unit(minutes, "Minute", "Minuten"));
            }
        } else if (minutes > 0) {
            parts.add(unit(minutes, "Minute", "Minuten"));
        } else {
            return "unter 1 Minute";
        }
        return String.join(", ", parts);
    }

    private String unit(long value, String singular, String plural) {
        return value + " " + (value == 1 ? singular : plural);
    }
}
