package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/** Applies configured settings after Worlds regenerated and the engine validated a farmworld. */
public interface FarmworldPostResetInitializer {

    /**
     * Captures temporary Dragon-Guard state for one reset invocation.
     *
     * <p>The returned scope must be closed on every completion path. Implementations may use it
     * to keep the immutable reset snapshot independent from concurrently reloaded configuration.</p>
     */
    default ResetScope beginReset(
            FarmworldResetConfig config,
            ResetOptions options
    ) {
        return ResetScope.NOOP;
    }

    CompletableFuture<PostResetResult> apply(
            FarmworldResetConfig config,
            World regeneratedWorld,
            ResetOptions options
    );

    @FunctionalInterface
    interface ResetScope {

        ResetScope NOOP = () -> { };

        void close();
    }
}
