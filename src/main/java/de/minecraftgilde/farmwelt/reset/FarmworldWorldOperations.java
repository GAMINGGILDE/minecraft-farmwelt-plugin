package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/** Bukkit-facing checks and player handling invoked in Folia-safe contexts by the engine. */
public interface FarmworldWorldOperations {

    WorldInspection inspect(FarmworldResetConfig resetConfig);

    CompletableFuture<FarmworldEvacuationResult> evacuatePlayers(World resetWorld);

    boolean hasPlayers(World world);
}
