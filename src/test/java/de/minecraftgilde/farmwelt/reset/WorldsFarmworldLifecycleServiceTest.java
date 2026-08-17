package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.thenextlvl.worlds.Level;
import net.thenextlvl.worlds.WorldsAccess;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class WorldsFarmworldLifecycleServiceTest {

    @Test
    void delegatesRegenerationWithOnlyRandomSeedCustomization() {
        World original = world("original");
        World regenerated = world("regenerated");
        AtomicReference<World> received = new AtomicReference<>();
        AtomicReference<Consumer<Level.Builder>> receivedCustomization = new AtomicReference<>();
        WorldsAccess worldsAccess = worldsAccess((world, customization) -> {
            received.set(world);
            receivedCustomization.set(customization);
            return CompletableFuture.completedFuture(regenerated);
        });
        WorldsFarmworldLifecycleService service = new WorldsFarmworldLifecycleService(worldsAccess);

        World result = service.regenerate(original).join();

        assertSame(original, received.get());
        assertSame(regenerated, result);

        Consumer<Level.Builder> customization = receivedCustomization.get();
        assertNotNull(customization);
        Level.Builder builder = Level.builder(Key.key("worlds", "original"))
                .seed(123L)
                .hardcore(true)
                .structures(false)
                .bonusChest(true)
                .resetSpawnPosition(true)
                .ignoreLevelData(false)
                .legacyName("original");
        List<Object> unchangedSettings = settingsExceptSeed(builder);

        customization.accept(builder);

        assertTrue(builder.seed().isEmpty());
        assertEquals(unchangedSettings, settingsExceptSeed(builder));
    }

    @Test
    void preservesWorldsFailureAsOriginalCause() {
        IllegalStateException failure = new IllegalStateException("Worlds failure");
        WorldsAccess worldsAccess = worldsAccess(
                (world, customization) -> CompletableFuture.failedFuture(failure)
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
                    if (method.getName().equals("regenerate") && method.getParameterCount() == 2) {
                        @SuppressWarnings("unchecked")
                        Consumer<Level.Builder> customization =
                                (Consumer<Level.Builder>) arguments[1];
                        return regeneration.regenerate((World) arguments[0], customization);
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

    private static List<Object> settingsExceptSeed(Level.Builder builder) {
        return List.of(
                builder.key(),
                builder.dimension(),
                builder.hardcore(),
                builder.structures(),
                builder.bonusChest(),
                builder.resetSpawnPosition(),
                builder.forcedSpawnPosition(),
                builder.forcedSpawnRotation(),
                builder.generatorType(),
                builder.generator(),
                builder.ignoreLevelData(),
                builder.legacyName()
        );
    }

    @FunctionalInterface
    private interface Regeneration {

        CompletableFuture<World> regenerate(
                World world,
                Consumer<Level.Builder> customization
        );
    }
}
