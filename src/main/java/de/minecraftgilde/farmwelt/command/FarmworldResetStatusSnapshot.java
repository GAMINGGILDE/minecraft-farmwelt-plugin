package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import java.util.Objects;
import java.util.Optional;

record FarmworldResetStatusSnapshot(
        FarmworldResetConfig config,
        Optional<FarmworldResetState> state,
        boolean resetRunning
) {

    FarmworldResetStatusSnapshot {
        Objects.requireNonNull(config, "config");
        state = Objects.requireNonNull(state, "state");
    }
}
