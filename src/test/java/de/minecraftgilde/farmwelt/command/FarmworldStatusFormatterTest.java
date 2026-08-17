package de.minecraftgilde.farmwelt.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FarmworldStatusFormatterTest {

    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");
    private static final FarmworldResetConfig CONFIG = new FarmworldResetConfig(
            "overworld",
            "test_farmwelt",
            true,
            Duration.ofDays(30)
    );
    private final FarmworldStatusFormatter formatter = new FarmworldStatusFormatter(
            Clock.fixed(NOW, ZoneOffset.UTC),
            ZoneId.of("Europe/Berlin")
    );

    @Test
    void formatsEmptyLastResetAsNochNieAndUsesLocalTimeWithoutSeconds() {
        FarmworldResetState state = new FarmworldResetState(
                "overworld",
                Optional.empty(),
                Instant.parse("2026-09-16T06:37:02Z")
        );

        List<String> lines = formatter.formatDetails(snapshot(state));

        assertTrue(lines.contains("§7Letzter Reset: §fNoch nie"));
        assertTrue(lines.contains("§7Nächster Reset: §f16.09.2026 08:37"));
    }

    @Test
    void formatsFutureRemainingDuration() {
        FarmworldResetState state = new FarmworldResetState(
                "overworld",
                Optional.empty(),
                NOW.plus(Duration.ofDays(29)).plus(Duration.ofHours(4))
        );

        assertEquals("§f29 Tage, 4 Stunden", formatter.formatRemaining(Optional.of(state)));
    }

    @Test
    void formatsPastNextResetAsOverdueWithoutTriggeringAnAction() {
        FarmworldResetState state = new FarmworldResetState(
                "overworld",
                Optional.empty(),
                NOW.minusSeconds(1)
        );

        assertEquals("§cÜberfällig", formatter.formatRemaining(Optional.of(state)));
        assertTrue(formatter.formatOverview(List.of(snapshot(state))).contains(
                "§7Verbleibend: §cÜberfällig"
        ));
    }

    @Test
    void formatsMissingDisabledStateWithDashes() {
        FarmworldResetConfig disabledConfig = new FarmworldResetConfig(
                "overworld",
                "test_farmwelt",
                false,
                Duration.ofDays(30)
        );

        List<String> lines = formatter.formatDetails(
                new FarmworldResetStatusSnapshot(disabledConfig, Optional.empty(), false)
        );

        assertTrue(lines.contains("§7Letzter Reset: §f-"));
        assertTrue(lines.contains("§7Nächster Reset: §f-"));
        assertTrue(lines.contains("§7Verbleibend: §f-"));
    }

    @Test
    void formatsThirtyDayInterval() {
        assertEquals("30 Tage", new GermanDurationFormatter().format(Duration.ofDays(30)));
    }

    private FarmworldResetStatusSnapshot snapshot(FarmworldResetState state) {
        return new FarmworldResetStatusSnapshot(CONFIG, Optional.of(state), false);
    }
}
