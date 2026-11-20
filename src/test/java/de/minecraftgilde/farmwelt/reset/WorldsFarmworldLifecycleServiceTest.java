package de.minecraftgilde.farmwelt.reset;

import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.INITIAL_FIGHT;
import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.kyori.adventure.key.Key;
import net.thenextlvl.worlds.Level;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.event.WorldRegenerateEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorldsFarmworldLifecycleServiceTest {

    private final Map<String, World> loaded = new LinkedHashMap<>();

    WorldsFarmworldLifecycleServiceTest() {
        world("world");
    }

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
            assertPreparedFightData(fightData, SUPPRESSED);
            return CompletableFuture.completedFuture(regenerated);
        });
        WorldsFarmworldLifecycleService service = service(worldsAccess);
        serviceReference.set(service);

        World result = service.regenerate(
                original,
                new FarmworldRegenerationOptions(SUPPRESSED)
        ).join();

        assertSame(regenerated, result);
        assertPreparedFightData(fightData, SUPPRESSED);
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
            assertPreparedFightData(fightData, SUPPRESSED);
            return CompletableFuture.failedFuture(failure);
        });
        WorldsFarmworldLifecycleService service = service(worldsAccess);
        serviceReference.set(service);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(
                        original,
                        new FarmworldRegenerationOptions(SUPPRESSED)
                ).join()
        );

        assertSame(failure, exception.getCause());
        assertEquals("old fight", Files.readString(fightData));
    }

    @Test
    void cancellationPreservesStagedDragonDataAndBackup(@TempDir Path tempDir) throws Exception {
        var external = new CompletableFuture<World>();
        Path data = createFightData(tempDir, "alter Kampf");
        World world = world("endfarm", tempDir);
        var service = service(worldsAccess((original, customize) -> external));
        var result = service.regenerate(world, new FarmworldRegenerationOptions(SUPPRESSED));
        publishRegenerationEvents(service, world);
        external.cancel(false);
        assertThrows(CompletionException.class, result::join);
        assertStagedWithBackup(data);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingWorldsFuturePreservesStagedDragonDataAndBackup(boolean throwsDuringCall, @TempDir Path tempDir)
            throws Exception {
        Path data = createFightData(tempDir, "alter Kampf");
        World world = world("endfarm", tempDir);
        var owner = new AtomicReference<WorldsFarmworldLifecycleService>();
        var service = service(worldsAccess((original, customize) -> {
            publishRegenerationEvents(owner.get(), original);
            if (throwsDuringCall) throw new IllegalStateException("Kein Abschlussbeleg");
            return null;
        }));
        owner.set(service);
        Class<? extends RuntimeException> expected = throwsDuringCall ? IllegalStateException.class : NullPointerException.class;
        assertThrows(expected, () -> service.regenerate(world, new FarmworldRegenerationOptions(SUPPRESSED)));
        assertStagedWithBackup(data);
    }

    private static void assertStagedWithBackup(Path data) throws Exception {
        assertPreparedFightData(data, SUPPRESSED);
        try (var files = Files.list(data.getParent())) {
            var backups = files.filter(path -> path.getFileName().toString().contains(".farmwelt-reset-")).toList();
            assertEquals(1, backups.size());
            assertEquals("alter Kampf", Files.readString(backups.getFirst()));
        }
    }

    @Test
    void preparesFreshInitialFightDataWhenDragonShouldSpawn(@TempDir Path tempDir)
            throws Exception {
        Path fightData = createFightData(tempDir, "previously killed dragon");
        World original = world("endfarm", tempDir);
        World regenerated = world("endfarm", tempDir.resolve("regenerated"));
        AtomicReference<WorldsFarmworldLifecycleService> serviceReference =
                new AtomicReference<>();
        WorldsAccess worldsAccess = worldsAccess((world, customization) -> {
            publishRegenerationEvents(serviceReference.get(), world);
            assertPreparedFightData(fightData, INITIAL_FIGHT);
            return CompletableFuture.completedFuture(regenerated);
        });
        WorldsFarmworldLifecycleService service = service(worldsAccess);
        serviceReference.set(service);

        World result = service.regenerate(
                original,
                new FarmworldRegenerationOptions(INITIAL_FIGHT)
        ).join();

        assertSame(regenerated, result);
        assertPreparedFightData(fightData, INITIAL_FIGHT);
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
    void unsupportedMinecraftVersionRejectsDataResetBeforeWorldsOrFileWrite(
            @TempDir Path tempDir
    ) throws Exception {
        Path fightData = createFightData(tempDir, "must remain untouched");
        AtomicBoolean worldsInvoked = new AtomicBoolean();
        WorldsAccess worldsAccess = worldsAccess((world, customization) -> {
            worldsInvoked.set(true);
            return CompletableFuture.completedFuture(world);
        });
        WorldsFarmworldLifecycleService service = new WorldsFarmworldLifecycleService(
                server(),
                worldsAccess,
                new EndDragonFightDataStore(4790),
                new EndDragonFightCompatibility(() -> "26.2"),
                quietLogger()
        );

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.regenerate(
                        world("endfarm", tempDir),
                        new FarmworldRegenerationOptions(SUPPRESSED)
                ).join()
        );

        assertTrue(exception.getCause().getMessage().contains("nicht freigegeben"));
        assertTrue(exception.getCause().getMessage().contains("26.1.2"));
        assertFalse(worldsInvoked.get());
        assertEquals("must remain untouched", Files.readString(fightData));
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
                        new FarmworldRegenerationOptions(SUPPRESSED)
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
                        new FarmworldRegenerationOptions(SUPPRESSED)
                ).join()
        );

        assertTrue(exception.getCause() instanceof java.io.IOException);
        assertTrue(Files.isDirectory(invalidFightData));
    }

    @Test
    void rechecksRegisteredInstanceBeforeStaging(@TempDir Path tempDir) throws Exception {
        World original = world("endfarm", tempDir);
        Path data = createFightData(tempDir, "alter Kampf");
        AtomicReference<WorldsFarmworldLifecycleService> service = new AtomicReference<>();
        service.set(service(worldsAccess((world, customization) -> {
            service.get().resetEndDragonFightData(new WorldRegenerateEvent(world));
            loaded.remove("endfarm");
            World replacement = world("endfarm", tempDir.resolve("new"));
            WorldUnloadEvent unload = new WorldUnloadEvent(original);
            service.get().prepareEndDragonFightData(unload);
            assertTrue(unload.isCancelled());
            return CompletableFuture.completedFuture(replacement);
        })));

        assertThrows(CompletionException.class, () -> service.get()
                .regenerate(original, new FarmworldRegenerationOptions(SUPPRESSED)).join());
        assertEquals("alter Kampf", Files.readString(data));
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

    private World world(String name) {
        return world(name, null);
    }

    private World world(String name, Path worldFolder) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getEnvironment" -> World.Environment.THE_END;
                    case "getKey" -> new NamespacedKey("worlds", name);
                    case "getWorldFolder" -> worldFolder == null ? null : worldFolder.toFile();
                    case "toString" -> "FakeWorld[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        loaded.putIfAbsent(name, world);
        return world;
    }

    private Server server() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> loaded.get(args[0]);
                    case "getWorlds" -> List.copyOf(loaded.values());
                    default -> defaultValue(method.getReturnType());
                });
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

    private static void assertPreparedFightData(
            Path fightData,
            FarmworldRegenerationOptions.EndDragonFightDataMode mode
    ) throws java.io.IOException {
        boolean initialFight = mode == INITIAL_FIGHT;
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
            assertBooleanTag(data, "needs_state_scanning", initialFight);
            assertBooleanTag(data, "dragon_killed", !initialFight);
            assertBooleanTag(data, "previously_killed", !initialFight);
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

    private WorldsFarmworldLifecycleService service(WorldsAccess worldsAccess) {
        return new WorldsFarmworldLifecycleService(
                server(),
                worldsAccess,
                new EndDragonFightDataStore(4790),
                new EndDragonFightCompatibility(() -> "26.1.2"),
                quietLogger()
        );
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                "WorldsFarmworldLifecycleServiceTest-" + System.nanoTime()
        );
        logger.setLevel(java.util.logging.Level.OFF);
        return logger;
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
