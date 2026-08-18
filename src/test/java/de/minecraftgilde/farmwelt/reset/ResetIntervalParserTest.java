package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResetIntervalParserTest {

    private final ResetIntervalParser parser = new ResetIntervalParser();

    @ParameterizedTest
    @CsvSource({
            "1m, 1",
            "90m, 90",
            "12h, 720",
            "30d, 43200",
            "60d, 86400"
    })
    void parsesSupportedIntervals(String value, long expectedMinutes) {
        assertEquals(
                Duration.ofMinutes(expectedMinutes),
                parser.parse(value).orElseThrow()
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "0d", "-1d", "abc", "30", "1s", "1w", "1mo", "30days", "1.5d",
            "30 d", "P30D", "0 0 4 * * *", "   "
    })
    void rejectsInvalidIntervals(String value) {
        assertTrue(parser.parse(value).isEmpty());
    }

    @Test
    void trimsOuterWhitespace() {
        assertEquals(Duration.ofDays(30), parser.parse(" 30d ").orElseThrow());
    }

    @Test
    void acceptsLargestWholeDayAmountRepresentableAsDuration() {
        long maximumWholeDays = Long.MAX_VALUE / (24L * 60L * 60L);

        assertEquals(
                Duration.ofDays(maximumWholeDays),
                parser.parse(maximumWholeDays + "d").orElseThrow()
        );
    }

    @Test
    void rejectsIntervalsThatOverflowDuration() {
        assertTrue(parser.parse(Long.MAX_VALUE + "d").isEmpty());
    }
}
