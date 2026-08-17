package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.thenextlvl.worlds.WorldsAccess;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class WorldsFarmworldLifecycleServiceTest {

    @Test
    void delegatesRegenerationToInjectedWorldsAccess() {
        World original = world("original");
        World regenerated = world("regenerated");
        AtomicReference<World> received = new AtomicReference<>();
        WorldsAccess worldsAccess = worldsAccess((World world) -> {
            received.set(world);
            return CompletableFuture.completedFuture(regenerated);
        });
        WorldsFarmworldLifecycleService service = new WorldsFarmworldLifecycleService(worldsAccess);

        World result = service.regenerate(original).join();

        assertSame(original, received.get());
        assertSame(regenerated, result);
    }

    @Test
    void preservesWorldsFailureAsOriginalCause() {
        IllegalStateException failure = new IllegalStateException("Worlds failure");
        WorldsAccess worldsAccess = worldsAccess(
                world -> CompletableFuture.failedFuture(failure)
        );
        WorldsFarmworldLifecycleService service = new WorldsFarmworldLifecycleService(worldsAccess);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(world("original")).join()
        );

        assertSame(failure, exception.getCause());
    }

    private static WorldsAccess worldsAccess(Regeneration regeneration) {
        return (WorldsAccess) Proxy.newProxyInstance(
                WorldsAccess.class.getClassLoader(),
                new Class<?>[]{WorldsAccess.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("regenerate") && method.getParameterCount() == 1) {
                        return regeneration.regenerate((World) arguments[0]);
                    }
                    return switch (method.getName()) {
                        case "toString" -> "FakeWorldsAccess";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> defaultValue(method.getReturnType());
                    };
                }
        );
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "FakeWorld[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    @FunctionalInterface
    private interface Regeneration {

        CompletableFuture<World> regenerate(World world);
    }
}
