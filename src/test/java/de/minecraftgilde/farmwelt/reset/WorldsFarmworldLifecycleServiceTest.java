package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.kyori.adventure.key.Key;
import net.thenextlvl.worlds.Level;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.event.WorldRegenerateEvent;
import org.bukkit.World;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.io.TempDir;
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
        WorldsFarmworldLifecycleService service = service(worldsAccess);

        World result = service.regenerate(
                original,
                FarmworldRegenerationOptions.defaults()
        ).join();

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
        WorldsFarmworldLifecycleService service = service(worldsAccess);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(
                        world("original"),
                        FarmworldRegenerationOptions.defaults()
                ).join()
        );

        assertSame(failure, exception.getCause());
    }

    @Test
    void resetsDragonFightDataOnlyInsidePreparedWorldsRegeneration(@TempDir Path tempDir)
            throws Exception {
        Path fightData = createFightData(tempDir, "old fight");
        World original = world("endfarm", tempDir);
        World regenerated = world("endfarm", tempDir.resolve("regenerated"));
        AtomicReference<WorldsFarmworldLifecycleService> serviceReference = new AtomicReference<>();
        WorldsAccess worldsAccess = worldsAccess((world, customization) -> {
            publishRegenerationEvents(serviceReference.get(), world);
            assertPreparedFightData(fightData);
            return CompletableFuture.completedFuture(regenerated);
        });
        WorldsFarmworldLifecycleService service = service(worldsAccess);
        serviceReference.set(service);

        World result = service.regenerate(
                original,
                new FarmworldRegenerationOptions(true)
        ).join();

        assertSame(regenerated, result);
        assertPreparedFightData(fightData);
        try (Stream<Path> files = Files.list(fightData.getParent())) {
            assertTrue(files.noneMatch(path ->
                    path.getFileName().toString().contains(".farmwelt-reset-")
            ));
        }
    }

    @Test
    void restoresDragonFightDataWhenWorldsRegenerationFails(@TempDir Path tempDir)
            throws Exception {
        Path fightData = createFightData(tempDir, "old fight");
        World original = world("endfarm", tempDir);
        IllegalStateException failure = new IllegalStateException("Worlds failure");
        AtomicReference<WorldsFarmworldLifecycleService> serviceReference = new AtomicReference<>();
        WorldsAccess worldsAccess = worldsAccess((world, customization) -> {
            publishRegenerationEvents(serviceReference.get(), world);
            assertPreparedFightData(fightData);
            return CompletableFuture.failedFuture(failure);
        });
        WorldsFarmworldLifecycleService service = service(worldsAccess);
        serviceReference.set(service);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(
                        original,
                        new FarmworldRegenerationOptions(true)
                ).join()
        );

        assertSame(failure, exception.getCause());
        assertEquals("old fight", Files.readString(fightData));
    }

    @Test
    void leavesDragonFightDataUntouchedWithoutResetOption(@TempDir Path tempDir)
            throws Exception {
        Path fightData = createFightData(tempDir, "allowed dragon");
        World original = world("endfarm", tempDir);
        WorldsAccess worldsAccess = worldsAccess((world, customization) ->
                CompletableFuture.completedFuture(world("endfarm", tempDir.resolve("new")))
        );
        WorldsFarmworldLifecycleService service = service(worldsAccess);

        service.regenerate(original, FarmworldRegenerationOptions.defaults()).join();

        assertEquals("allowed dragon", Files.readString(fightData));
    }

    @Test
    void failsClosedWhenWorldsDoesNotPublishItsRegenerationEvent(@TempDir Path tempDir)
            throws Exception {
        Path fightData = createFightData(tempDir, "must stay untouched");
        World original = world("endfarm", tempDir);
        WorldsAccess worldsAccess = worldsAccess((world, customization) ->
                CompletableFuture.completedFuture(world("endfarm", tempDir.resolve("new")))
        );
        WorldsFarmworldLifecycleService service = service(worldsAccess);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(
                        original,
                        new FarmworldRegenerationOptions(true)
                ).join()
        );

        assertTrue(exception.getCause().getMessage().contains("kein WorldRegenerateEvent"));
        assertEquals("must stay untouched", Files.readString(fightData));
    }

    @Test
    void cancelsRegenerationWhenFightDataCannotBePrepared(@TempDir Path tempDir)
            throws Exception {
        Path invalidFightData = tempDir.resolve(
                Path.of("data", "minecraft", "ender_dragon_fight.dat")
        );
        Files.createDirectories(invalidFightData);
        World original = world("endfarm", tempDir);
        AtomicReference<WorldsFarmworldLifecycleService> serviceReference = new AtomicReference<>();
        WorldsAccess worldsAccess = worldsAccess((world, customization) -> {
            WorldsFarmworldLifecycleService service = serviceReference.get();
            service.resetEndDragonFightData(new WorldRegenerateEvent(world));
            WorldUnloadEvent unloadEvent = new WorldUnloadEvent(world);
            service.prepareEndDragonFightData(unloadEvent);
            assertTrue(unloadEvent.isCancelled());
            return CompletableFuture.completedFuture(world("endfarm", tempDir.resolve("new")));
        });
        WorldsFarmworldLifecycleService service = service(worldsAccess);
        serviceReference.set(service);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(
                        original,
                        new FarmworldRegenerationOptions(true)
                ).join()
        );

        assertTrue(exception.getCause() instanceof java.io.IOException);
        assertTrue(Files.isDirectory(invalidFightData));
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
        return world(name, null);
    }

    private static World world(String name, Path worldFolder) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getWorldFolder" -> worldFolder == null ? null : worldFolder.toFile();
                    case "toString" -> "FakeWorld[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Path createFightData(Path worldFolder, String content) throws Exception {
        Path fightData = worldFolder.resolve(
                Path.of("data", "minecraft", "ender_dragon_fight.dat")
        );
        Files.createDirectories(fightData.getParent());
        Files.writeString(fightData, content);
        return fightData;
    }

    private static void publishRegenerationEvents(
            WorldsFarmworldLifecycleService service,
            World world
    ) {
        service.resetEndDragonFightData(new WorldRegenerateEvent(world));
        service.prepareEndDragonFightData(new WorldUnloadEvent(world));
    }

    private static void assertPreparedFightData(Path fightData) throws java.io.IOException {
        try (DataInputStream data = new DataInputStream(
                new GZIPInputStream(Files.newInputStream(fightData))
        )) {
            assertEquals(10, data.readUnsignedByte());
            assertEquals("", data.readUTF());

            assertEquals(3, data.readUnsignedByte());
            assertEquals("DataVersion", data.readUTF());
            assertEquals(4790, data.readInt());

            assertEquals(10, data.readUnsignedByte());
            assertEquals("data", data.readUTF());
            assertBooleanTag(data, "needs_state_scanning", false);
            assertBooleanTag(data, "dragon_killed", true);
            assertBooleanTag(data, "previously_killed", true);
            assertEquals(0, data.readUnsignedByte());
            assertEquals(0, data.readUnsignedByte());
        }
    }

    private static void assertBooleanTag(
            DataInputStream data,
            String expectedName,
            boolean expectedValue
    ) throws java.io.IOException {
        assertEquals(1, data.readUnsignedByte());
        assertEquals(expectedName, data.readUTF());
        assertEquals(expectedValue ? 1 : 0, data.readUnsignedByte());
    }

    private static WorldsFarmworldLifecycleService service(WorldsAccess worldsAccess) {
        Logger logger = Logger.getLogger(
                "WorldsFarmworldLifecycleServiceTest-" + System.nanoTime()
        );
        logger.setLevel(java.util.logging.Level.OFF);
        return new WorldsFarmworldLifecycleService(
                worldsAccess,
                new EndDragonFightDataStore(4790),
                logger
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
        ) throws Exception;
    }
}
