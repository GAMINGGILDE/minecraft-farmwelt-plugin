package de.minecraftgilde.farmwelt.reset;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class FoliaFarmweltScheduler implements FarmweltScheduler {

    private final Plugin plugin;
    // Nur noch nicht begonnene Callbacks sind registriert. Entfernen vergibt das Ausführungsrecht.
    private final Set<CompletableFuture<?>> waiting = new HashSet<>();
    private boolean stopped;

    public FoliaFarmweltScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
        return submit(operation, run -> plugin.getServer().getGlobalRegionScheduler().execute(plugin, run));
    }

    @Override
    public <T> CompletableFuture<T> runGlobalDelayed(long ticks, CheckedSupplier<T> operation) {
        return submit(operation, run -> {
            requireDelay(ticks);
            Objects.requireNonNull(plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> run.run(), ticks));
        });
    }

    @Override
    public <T> CompletableFuture<T> runRegion(World world, int chunkX, int chunkZ, CheckedSupplier<T> operation) {
        return submit(operation, run -> plugin.getServer().getRegionScheduler().execute(plugin, Objects.requireNonNull(world), chunkX, chunkZ, run));
    }

    @Override
    public <T> CompletableFuture<T> runRegionDelayed(World world, int chunkX, int chunkZ, long ticks,
                                                     CheckedSupplier<T> operation) {
        return submit(operation, run -> {
            requireDelay(ticks);
            Objects.requireNonNull(plugin.getServer().getRegionScheduler()
                    .runDelayed(plugin, Objects.requireNonNull(world), chunkX, chunkZ, task -> run.run(), ticks));
        });
    }

    @Override
    public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
        return submit(operation, run -> Objects.requireNonNull(
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> run.run())));
    }

    private static void requireDelay(long ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Die Verzögerung muss mindestens einen Tick betragen.");
    }

    <T> CompletableFuture<T> runEntity(Entity entity, CheckedSupplier<T> operation, Function<Boolean, T> retired) {
        return submit(operation, (run, unavailable) -> {
            boolean accepted = entity.getScheduler().execute(plugin, run, () -> unavailable.accept(true), 1L);
            if (!accepted) unavailable.accept(false);
        }, retired);
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> operation, Consumer<Runnable> enqueue) {
        return submit(operation, (run, ignored) -> enqueue.accept(run), ignored -> null);
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> operation,
            BiConsumer<Runnable, Consumer<Boolean>> enqueue, Function<Boolean, T> retired) {
        var result = new CompletableFuture<T>();
        synchronized (waiting) {
            if (stopped) return CompletableFuture.failedFuture(stoppedException());
            waiting.add(result);
        }
        try {
            Objects.requireNonNull(operation, "operation");
            enqueue.accept(() -> executeOnce(result, operation),
                    accepted -> executeOnce(result, () -> retired.apply(accepted)));
        } catch (RuntimeException | LinkageError failure) {
            if (claim(result)) result.completeExceptionally(failure);
        }
        return result.copy();
    }

    private boolean claim(CompletableFuture<?> result) {
        synchronized (waiting) {
            return waiting.remove(result);
        }
    }

    private <T> void executeOnce(CompletableFuture<T> result, CheckedSupplier<T> operation) {
        if (!claim(result)) return;
        try {
            result.complete(operation.get());
        } catch (Exception | LinkageError failure) {
            result.completeExceptionally(failure);
        }
    }

    public void stop() {
        List<CompletableFuture<?>> abandoned;
        synchronized (waiting) {
            stopped = true;
            abandoned = List.copyOf(waiting);
            waiting.clear();
        }
        abandoned.forEach(result -> result.completeExceptionally(stoppedException()));
    }

    private RejectedExecutionException stoppedException() {
        return new RejectedExecutionException("Farmwelt-Scheduler wurde gestoppt.");
    }
}
