package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;

/** Schedules the plugin's own Bukkit checks and blocking I/O in Folia-safe contexts. */
public interface FarmweltScheduler {

    <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation);

    <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation);

    @FunctionalInterface
    interface CheckedSupplier<T> {

        T get() throws Exception;
    }
}
