package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.java.JavaPlugin;

public final class FoliaFarmweltScheduler implements FarmweltScheduler {

    private final JavaPlugin plugin;

    public FoliaFarmweltScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getGlobalRegionScheduler().execute(
                plugin,
                () -> complete(future, operation)
        );
        return future;
    }

    @Override
    public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(
                plugin,
                ignored -> complete(future, operation)
        );
        return future;
    }

    private <T> void complete(CompletableFuture<T> future, CheckedSupplier<T> operation) {
        try {
            future.complete(operation.get());
        } catch (Exception exception) {
            future.completeExceptionally(exception);
        }
    }
}
