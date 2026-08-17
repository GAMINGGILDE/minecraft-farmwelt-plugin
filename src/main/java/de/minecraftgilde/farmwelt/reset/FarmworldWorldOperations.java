package de.minecraftgilde.farmwelt.reset;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Bukkit-facing operations. Synchronous methods are invoked on the global region by the engine. */
public interface FarmworldWorldOperations {

    WorldInspection inspect(FarmworldResetConfig resetConfig);

    CompletableFuture<Boolean> evacuatePlayers(FarmworldResetConfig resetConfig);

    boolean hasPlayers(FarmworldResetConfig resetConfig);

    boolean unload(FarmworldResetConfig resetConfig);

    boolean isLoaded(FarmworldResetConfig resetConfig);

    /** Returns the actual folder of the newly created and validated Bukkit world. */
    Optional<Path> createAndValidate(FarmworldResetConfig resetConfig);
}
