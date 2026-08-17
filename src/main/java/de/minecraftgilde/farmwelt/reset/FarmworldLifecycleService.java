package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/** Owns the technical lifecycle operation used to regenerate a loaded farmworld. */
public interface FarmworldLifecycleService {

    CompletableFuture<World> regenerate(
            World world,
            FarmworldRegenerationOptions options
    );
}
