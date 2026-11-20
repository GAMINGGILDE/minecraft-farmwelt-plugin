package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.*;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class FoliaFarmweltSchedulerTest {
    enum Path { GLOBAL, GLOBAL_DELAYED, REGION, REGION_DELAYED, ASYNC, ENTITY }

    private final ScheduledTask task = proxy(ScheduledTask.class, (p, m, a) -> null);
    private final World world = proxy(World.class, (p, m, a) -> null);
    private Runnable callback;
    private RuntimeException rejection;
    private final Server server = proxy(Server.class, (p, m, a) -> proxy(m.getReturnType(), this::schedule));
    private final Plugin plugin = proxy(Plugin.class, (p, m, a) -> server);
    private final Entity entity = proxy(Entity.class, (p, m, a) -> proxy(m.getReturnType(), this::schedule));
    private final FoliaFarmweltScheduler scheduler = new FoliaFarmweltScheduler(plugin);

    @ParameterizedTest
    @EnumSource(Path.class)
    void schedulingFailureRetainsCauseAndLateCallbackCannotRun(Path path) {
        rejection = new IllegalStateException("Scheduling abgelehnt");
        var calls = new AtomicInteger();
        var result = submit(path, calls::incrementAndGet);
        assertSame(rejection, failure(result));
        callback.run();
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void callbackCompletesWithItsOriginalFailureOrValue(Path path) {
        var checked = new IOException("Callback");
        var runtime = new IllegalStateException("Callback");
        var linkage = new NoSuchMethodError("API");
        for (var error : new Throwable[]{checked, runtime, linkage}) {
            var result = submit(path, () -> {
                if (error instanceof Exception exception) throw exception;
                throw linkage;
            });
            callback.run();
            assertSame(error, failure(result));
        }
        var calls = new AtomicInteger();
        var result = submit(path, calls::incrementAndGet);
        callback.run();
        callback.run();
        assertEquals(1, result.getNow(-1));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void stopBeforeCallbackFencesLateExecutionAndRejectsNewWork(Path path) {
        var calls = new AtomicInteger();
        var result = submit(path, calls::incrementAndGet);
        scheduler.stop();
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, failure(result));
        callback.run();
        assertTrue(submit(path, calls::incrementAndGet).isCompletedExceptionally());
        assertEquals(0, calls.get());
    }

    @Test
    void stopLeavesAnAlreadyRunningCallbackWithItsOwner() throws Exception {
        var running = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var result = scheduler.runAsync(() -> {
            running.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Testfrist");
            return 7;
        });
        var thread = Thread.ofVirtual().start(callback);
        try {
            assertTrue(running.await(5, TimeUnit.SECONDS));
            scheduler.stop();
            assertFalse(result.isDone());
        } finally {
            finish.countDown();
        }
        assertEquals(7, result.get(5, TimeUnit.SECONDS));
        thread.join(5000);
    }

    private CompletableFuture<Integer> submit(Path path, FarmweltScheduler.CheckedSupplier<Integer> operation) {
        return switch (path) {
            case GLOBAL -> scheduler.runGlobal(operation);
            case GLOBAL_DELAYED -> scheduler.runGlobalDelayed(1, operation);
            case REGION -> scheduler.runRegion(world, 2, 3, operation);
            case REGION_DELAYED -> scheduler.runRegionDelayed(world, 2, 3, 1, operation);
            case ASYNC -> scheduler.runAsync(operation);
            case ENTITY -> scheduler.runEntity(entity, operation, accepted -> -1);
        };
    }

    private Throwable failure(CompletableFuture<?> result) {
        assertTrue(result.isCompletedExceptionally());
        return assertThrows(CompletionException.class, result::join).getCause();
    }

    @SuppressWarnings("unchecked")
    private Object schedule(Object proxy, java.lang.reflect.Method method, Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Runnable run) {
                callback = run;
                break;
            }
            if (arg instanceof Consumer<?> consumer) {
                callback = () -> ((Consumer<ScheduledTask>) consumer).accept(task);
                break;
            }
        }
        if (rejection != null) throw rejection;
        return method.getReturnType() == boolean.class ? true : task;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
