package de.minecraftgilde.farmwelt.reset;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bewertet einen persistenten Reset-Plan, ohne einen Reset auszulösen. */
public final class ResetDueStateEvaluator {

    public ResetDueState evaluate(
            FarmworldResetConfig configuration,
            Optional<FarmworldResetState> resetState,
            Instant now
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(resetState, "resetState");
        Objects.requireNonNull(now, "now");

        if (!configuration.enabled() || resetState.isEmpty()) {
            return ResetDueState.DISABLED;
        }

        FarmworldResetState schedule = resetState.orElseThrow();
        if (!configuration.farmworldKey().equals(schedule.farmworldKey())) {
            return ResetDueState.DISABLED;
        }

        return now.isBefore(schedule.nextReset())
                ? ResetDueState.NOT_DUE
                : ResetDueState.DUE;
    }
}
