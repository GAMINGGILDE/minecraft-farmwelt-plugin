package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import de.minecraftgilde.farmwelt.reset.ResetDueState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

record FarmworldResetStatusSnapshot(
        FarmworldResetConfig config,
        Optional<FarmworldResetState> state,
        boolean resetRunning,
        ResetDueState dueState,
        Instant evaluatedAt
) {

    FarmworldResetStatusSnapshot {
        Objects.requireNonNull(config, "config");
        state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(dueState, "dueState");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }
}
