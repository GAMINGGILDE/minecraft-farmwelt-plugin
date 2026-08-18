package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
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
    public <T> CompletableFuture<T> runGlobalDelayed(
            long ticks,
            CheckedSupplier<T> operation
    ) {
        Objects.requireNonNull(operation, "operation");
        if (ticks < 1L) {
            throw new IllegalArgumentException("ticks must be at least 1");
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(
                    plugin,
                    ignored -> complete(future, operation),
                    ticks
            );
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> runRegion(
            World world,
            int chunkX,
            int chunkZ,
            CheckedSupplier<T> operation
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            plugin.getServer().getRegionScheduler().execute(
                    plugin,
                    world,
                    chunkX,
                    chunkZ,
                    () -> complete(future, operation)
            );
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> runRegionDelayed(
            World world,
            int chunkX,
            int chunkZ,
            long ticks,
            CheckedSupplier<T> operation
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(operation, "operation");
        if (ticks < 1L) {
            throw new IllegalArgumentException("ticks must be at least 1");
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            plugin.getServer().getRegionScheduler().runDelayed(
                    plugin,
                    world,
                    chunkX,
                    chunkZ,
                    ignored -> complete(future, operation),
                    ticks
            );
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
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
