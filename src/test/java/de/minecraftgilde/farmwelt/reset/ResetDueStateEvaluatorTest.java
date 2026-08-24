package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResetDueStateEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    private final ResetDueStateEvaluator evaluator = new ResetDueStateEvaluator();

    @Test
    void futureResetIsNotDue() {
        assertEquals(
                ResetDueState.NOT_DUE,
                evaluate(enabledConfig("overworld"), NOW.plusSeconds(1))
        );
    }

    @Test
    void resetAtCurrentInstantIsDue() {
        assertEquals(ResetDueState.DUE, evaluate(enabledConfig("overworld"), NOW));
    }

    @Test
    void pastResetIsDue() {
        assertEquals(
                ResetDueState.DUE,
                evaluate(enabledConfig("overworld"), NOW.minusSeconds(1))
        );
    }

    @Test
    void disabledResetOrMissingScheduleIsDisabled() {
        FarmworldResetConfig disabledConfig = new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                false,
                Duration.ofDays(30)
        );

        assertEquals(ResetDueState.DISABLED, evaluate(disabledConfig, NOW));
        assertEquals(
                ResetDueState.DISABLED,
                evaluator.evaluate(enabledConfig("overworld"), Optional.empty(), NOW)
        );
    }

    @Test
    void farmworldsAreEvaluatedIndependently() {
        Map<String, ResetDueState> dueStates = new LinkedHashMap<>();
        dueStates.put("overworld", evaluate(enabledConfig("overworld"), NOW));
        dueStates.put("nether", evaluate(enabledConfig("nether"), NOW.plusSeconds(1)));
        dueStates.put(
                "end",
                evaluator.evaluate(enabledConfig("end"), Optional.empty(), NOW)
        );

        assertEquals(Map.of(
                "overworld", ResetDueState.DUE,
                "nether", ResetDueState.NOT_DUE,
                "end", ResetDueState.DISABLED
        ), dueStates);
    }

    @Test
    void stateForDifferentFarmworldIsNotAValidSchedule() {
        FarmworldResetState mismatchedState = new FarmworldResetState(
                "nether",
                Optional.empty(),
                NOW
        );

        assertEquals(
                ResetDueState.DISABLED,
                evaluator.evaluate(
                        enabledConfig("overworld"),
                        Optional.of(mismatchedState),
                        NOW
                )
        );
    }

    private ResetDueState evaluate(FarmworldResetConfig config, Instant nextReset) {
        return evaluator.evaluate(
                config,
                Optional.of(new FarmworldResetState(
                        config.farmworldKey(),
                        Optional.empty(),
                        nextReset
                )),
                NOW
        );
    }

    private FarmworldResetConfig enabledConfig(String farmworldKey) {
        return new FarmworldResetConfig(
                farmworldKey,
                "test_" + farmworldKey,
                true,
                Duration.ofDays(30)
        );
    }
}
