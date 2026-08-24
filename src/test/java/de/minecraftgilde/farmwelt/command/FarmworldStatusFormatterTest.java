package de.minecraftgilde.farmwelt.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import de.minecraftgilde.farmwelt.reset.ResetDueStateEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
    private final ResetDueStateEvaluator dueStateEvaluator = new ResetDueStateEvaluator();
    private final FarmworldStatusFormatter formatter = new FarmworldStatusFormatter(
            ZoneId.of("Europe/Berlin")
    );

    @Test
    void formatsScheduledStatusAndFutureRemainingDuration() {
        FarmworldResetState state = state(NOW.plus(Duration.ofDays(29)).plus(Duration.ofHours(4)));

        List<String> lines = formatter.formatOverview(List.of(snapshot(CONFIG, state, false)));

        assertTrue(lines.contains("§7Status: §aGeplant"));
        assertTrue(lines.contains("§7Verbleibend: §f29 Tage, 4 Stunden"));
    }

    @Test
    void formatsResetAtCurrentInstantAsOverdue() {
        List<String> lines = formatter.formatDetails(snapshot(CONFIG, state(NOW), false));

        assertTrue(lines.contains("§7Status: §cÜberfällig"));
        assertTrue(lines.contains("§7Verbleibend: §cÜberfällig"));
    }

    @Test
    void formatsPastResetAsOverdueWithoutTriggeringAnAction() {
        List<String> lines = formatter.formatOverview(List.of(
                snapshot(CONFIG, state(NOW.minusSeconds(1)), false)
        ));

        assertTrue(lines.contains("§7Status: §cÜberfällig"));
        assertTrue(lines.contains("§7Verbleibend: §cÜberfällig"));
    }

    @Test
    void runningOverdueResetHasPriority() {
        List<String> lines = formatter.formatDetails(
                snapshot(CONFIG, state(NOW.minusSeconds(1)), true)
        );

        assertTrue(lines.contains("§7Status: §eLäuft"));
        assertFalse(lines.contains("§7Status: §cÜberfällig"));
        assertTrue(lines.contains("§7Verbleibend: §cÜberfällig"));
    }

    @Test
    void disabledResetWithHistoricalStateIsNotShownAsOverdue() {
        FarmworldResetConfig disabledConfig = disabledConfig();
        FarmworldResetState historicalState = new FarmworldResetState(
                "overworld",
                Optional.of(NOW.minus(Duration.ofDays(30))),
                NOW.minusSeconds(1)
        );

        List<String> lines = formatter.formatDetails(
                snapshot(disabledConfig, Optional.of(historicalState), false)
        );

        assertTrue(lines.contains("§7Status: §cDeaktiviert"));
        assertTrue(lines.contains("§7Reset aktiviert: §cNein"));
        assertTrue(lines.contains("§7Letzter Reset: §f18.07.2026 10:00"));
        assertTrue(lines.contains("§7Nächster Reset: §f17.08.2026 09:59"));
        assertTrue(lines.contains("§7Verbleibend: §f-"));
        assertFalse(lines.contains("§7Verbleibend: §cÜberfällig"));
    }

    @Test
    void disabledResetWithoutStateUsesDashes() {
        List<String> lines = formatter.formatDetails(
                snapshot(disabledConfig(), Optional.empty(), false)
        );

        assertTrue(lines.contains("§7Status: §cDeaktiviert"));
        assertTrue(lines.contains("§7Letzter Reset: §f-"));
        assertTrue(lines.contains("§7Nächster Reset: §f-"));
        assertTrue(lines.contains("§7Verbleibend: §f-"));
    }

    @Test
    void enabledResetWithoutStateHasNoSchedule() {
        List<String> lines = formatter.formatDetails(
                snapshot(CONFIG, Optional.empty(), false)
        );

        assertTrue(lines.contains("§7Status: §eKein Zeitplan"));
        assertTrue(lines.contains("§7Letzter Reset: §f-"));
        assertTrue(lines.contains("§7Nächster Reset: §f-"));
        assertTrue(lines.contains("§7Verbleibend: §f-"));
    }

    @Test
    void formatsEmptyLastResetAsNochNieAndUsesLocalTimeWithoutSeconds() {
        FarmworldResetState state = new FarmworldResetState(
                "overworld",
                Optional.empty(),
                Instant.parse("2026-09-16T06:37:02Z")
        );

        List<String> lines = formatter.formatDetails(snapshot(CONFIG, state, false));

        assertTrue(lines.contains("§7Letzter Reset: §fNoch nie"));
        assertTrue(lines.contains("§7Nächster Reset: §f16.09.2026 08:37"));
    }

    @Test
    void runningScheduledResetHasPriority() {
        List<String> lines = formatter.formatOverview(List.of(
                snapshot(CONFIG, state(NOW.plusSeconds(1)), true)
        ));

        assertTrue(lines.contains("§7Status: §eLäuft"));
        assertFalse(lines.contains("§7Status: §aGeplant"));
    }

    @Test
    void formatsThirtyDayInterval() {
        assertEquals("30 Tage", new GermanDurationFormatter().format(Duration.ofDays(30)));
    }

    private FarmworldResetStatusSnapshot snapshot(
            FarmworldResetConfig config,
            FarmworldResetState state,
            boolean running
    ) {
        return snapshot(config, Optional.of(state), running);
    }

    private FarmworldResetStatusSnapshot snapshot(
            FarmworldResetConfig config,
            Optional<FarmworldResetState> state,
            boolean running
    ) {
        return new FarmworldResetStatusSnapshot(
                config,
                state,
                running,
                dueStateEvaluator.evaluate(config, state, NOW),
                NOW
        );
    }

    private FarmworldResetState state(Instant nextReset) {
        return new FarmworldResetState("overworld", Optional.empty(), nextReset);
    }

    private FarmworldResetConfig disabledConfig() {
        return new FarmworldResetConfig(
                "overworld",
                "test_farmwelt",
                false,
                Duration.ofDays(30)
        );
    }
}
