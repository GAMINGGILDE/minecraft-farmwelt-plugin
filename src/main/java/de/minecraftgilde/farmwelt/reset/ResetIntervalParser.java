package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses reset intervals without any Bukkit dependencies.
 */
public final class ResetIntervalParser {

    private static final Pattern INTERVAL_PATTERN = Pattern.compile("^([1-9][0-9]*)([mhd])$");

    public Optional<Duration> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }

        Matcher matcher = INTERVAL_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("Unbekannte Intervalleinheit.");
            };
            return Optional.of(duration);
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
