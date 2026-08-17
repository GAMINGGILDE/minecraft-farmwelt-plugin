package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/** Schedules the plugin's own Bukkit checks and blocking I/O in Folia-safe contexts. */
public interface FarmweltScheduler {

    <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation);

    <T> CompletableFuture<T> runGlobalDelayed(long ticks, CheckedSupplier<T> operation);

    <T> CompletableFuture<T> runRegion(
            World world,
            int chunkX,
            int chunkZ,
            CheckedSupplier<T> operation
    );

    <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation);

    @FunctionalInterface
    interface CheckedSupplier<T> {

        T get() throws Exception;
    }
}
