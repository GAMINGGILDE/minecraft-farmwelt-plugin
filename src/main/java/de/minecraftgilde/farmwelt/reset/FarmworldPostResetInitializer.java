package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/** Applies configured settings after Worlds regenerated and the engine validated a farmworld. */
public interface FarmworldPostResetInitializer {

    CompletableFuture<PostResetResult> apply(
            FarmworldResetConfig config,
            World regeneratedWorld,
            ResetOptions options
    );
}
