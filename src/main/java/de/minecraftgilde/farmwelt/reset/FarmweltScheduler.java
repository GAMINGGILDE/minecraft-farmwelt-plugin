package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;

/** Schedules Bukkit world work and blocking I/O in their respective Folia contexts. */
public interface FarmweltScheduler {

    <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation);

    <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation);

    @FunctionalInterface
    interface CheckedSupplier<T> {

        T get() throws Exception;
    }
}
