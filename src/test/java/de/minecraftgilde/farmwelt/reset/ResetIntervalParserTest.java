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
            "30d, 720",
            "60d, 1440",
            "12h, 12",
            "90m, 1.5"
    })
    void parsesSupportedIntervals(String value, double expectedHours) {
        assertEquals(
                Duration.ofMinutes((long) (expectedHours * 60)),
                parser.parse(value).orElseThrow()
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0d", "-1d", "abc", "30", "   "})
    void rejectsInvalidIntervals(String value) {
        assertTrue(parser.parse(value).isEmpty());
    }

    @Test
    void rejectsIntervalsThatOverflowDuration() {
        assertTrue(parser.parse(Long.MAX_VALUE + "d").isEmpty());
    }
}
