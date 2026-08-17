package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class BukkitFarmworldPostResetInitializerTest {

    @Test
    void appliesOnlyConfiguredGamerulesWithTheirResolvedTypes() {
        Map<String, Object> configured = new LinkedHashMap<>();
        configured.put("players_sleeping_percentage", 50);
        configured.put("show_advancement_messages", false);
        Map<String, Object> applied = new LinkedHashMap<>();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> switch (name) {
            case "players_sleeping_percentage" -> resolved(name, Integer.class, applied);
            case "show_advancement_messages" -> resolved(name, Boolean.class, applied);
            default -> null;
        });
        FarmworldResetConfig config = config(
                FarmworldType.OVERWORLD,
                new PostResetConfig(configured, Optional.empty(), Optional.empty())
        );

        PostResetResult result = initializer.apply(
                config,
                world("farmwelt", null, List.of()),
                ResetOptions.defaults()
        ).join();

        assertTrue(result.successful());
        assertEquals(Map.of(
                "players_sleeping_percentage", Integer.valueOf(50),
                "show_advancement_messages", Boolean.FALSE
        ), applied);
        assertFalse(applied.containsKey("unconfigured_rule"));
    }

    @Test
    void unknownOrInvalidGamerulesFailCleanly() {
        BukkitFarmworldPostResetInitializer unknownInitializer = initializer(name -> null);
        FarmworldResetConfig unknownConfig = config(
                FarmworldType.OVERWORLD,
                new PostResetConfig(Map.of("foo_bar", true), Optional.empty(), Optional.empty())
        );

        PostResetResult unknown = unknownInitializer.apply(
                unknownConfig, world("farmwelt", null, List.of()), ResetOptions.defaults()
        ).join();
        assertFalse(unknown.successful());

        BukkitFarmworldPostResetInitializer invalidInitializer = initializer(
                name -> resolved(name, Integer.class, new LinkedHashMap<>())
        );
        FarmworldResetConfig invalidConfig = config(
                FarmworldType.OVERWORLD,
                new PostResetConfig(Map.of("players_sleeping_percentage", "many"),
                        Optional.empty(), Optional.empty())
        );
        PostResetResult invalid = invalidInitializer.apply(
                invalidConfig, world("farmwelt", null, List.of()), ResetOptions.defaults()
        ).join();
        assertFalse(invalid.successful());
    }

    @Test
    void appliesConfiguredWorldBorderAndLeavesMissingBorderUntouched() {
        AtomicReference<Double> appliedSize = new AtomicReference<>();
        WorldBorder border = worldBorder(appliedSize);
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        FarmworldResetConfig configured = config(
                FarmworldType.OVERWORLD,
                new PostResetConfig(
                        Map.of(),
                        Optional.of(new WorldBorderConfig(20000)),
                        Optional.empty()
                )
        );

        assertTrue(initializer.apply(
                configured, world("farmwelt", border, List.of()), ResetOptions.defaults()
        ).join().successful());
        assertEquals(20000.0D, appliedSize.get());

        appliedSize.set(null);
        assertTrue(initializer.apply(
                config(FarmworldType.OVERWORLD, PostResetConfig.none()),
                world("farmwelt", border, List.of()),
                ResetOptions.defaults()
        ).join().successful());
        assertEquals(null, appliedSize.get());
    }

    @Test
    void emptyWorldSucceedsImmediatelyWithoutDelayedChecks() {
        DragonScenario scenario = dragonScenario(List.of(List.of()));
        ControllableScheduler scheduler = new ControllableScheduler();
        CompletableFuture<PostResetResult> result = initializer(name -> null, scheduler).apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );

        assertTrue(scenario.previouslyKilled().get());
        assertTrue(result.isDone());
        assertTrue(result.join().successful());
        assertTrue(scheduler.delays().isEmpty());
        assertEquals(1, scenario.entityLookups().get());
    }

    @Test
    void immediatelyPresentDragonIsRemovedAndLaterAbsenceIsVerified() {
        TestDragon dragon = enderDragon(true);
        DragonScenario scenario = dragonScenario(List.of(
                List.of(dragon.entity()),
                List.of()
        ));
        ControllableScheduler scheduler = new ControllableScheduler();
        CompletableFuture<PostResetResult> result = initializer(name -> null, scheduler).apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );

        assertTrue(dragon.removed().get());
        assertFalse(result.isDone());
        assertEquals(List.of(5L), scheduler.delays());
        scheduler.advance();

        assertTrue(result.join().successful());
        // The same dragon is returned by DragonBattle and World, but removed only once.
        assertEquals(1, dragon.removalAttempts().get());
        assertEquals(2, scenario.entityLookups().get());
    }

    @Test
    void dragonThatRemainsActiveFailsAfterRemovalVerification() {
        TestDragon dragon = enderDragon(false);
        DragonScenario scenario = dragonScenario(List.of(
                List.of(dragon.entity()),
                List.of(dragon.entity())
        ));
        ControllableScheduler scheduler = new ControllableScheduler();
        CompletableFuture<PostResetResult> result = initializer(name -> null, scheduler).apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );

        scheduler.advance();

        assertFalse(result.join().successful());
        assertEquals(1, dragon.removalAttempts().get());
        assertEquals(2, scenario.entityLookups().get());
        assertEquals(List.of(5L), scheduler.delays());
    }

    @Test
    void retiredDragonStillRequiresARealLaterVerification() {
        TestDragon dragon = retiredEnderDragon();
        DragonScenario scenario = dragonScenario(List.of(
                List.of(dragon.entity()), List.of()
        ));
        ControllableScheduler scheduler = new ControllableScheduler();
        CompletableFuture<PostResetResult> result = initializer(name -> null, scheduler).apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );

        assertTrue(dragon.removed().get());
        assertEquals(0, dragon.removalAttempts().get());
        assertFalse(result.isDone());
        scheduler.advance();
        assertTrue(result.join().successful());
        assertEquals(List.of(5L), scheduler.delays());
    }

    @Test
    void dragonBattleStateThatDoesNotRemainAppliedFailsThePolicy() {
        DragonScenario scenario = dragonScenario(
                List.of(List.of()),
                false
        );
        ControllableScheduler scheduler = new ControllableScheduler();
        CompletableFuture<PostResetResult> result = initializer(name -> null, scheduler).apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );

        assertTrue(result.isDone());
        assertFalse(result.join().successful());
        assertFalse(scenario.previouslyKilled().get());
        assertTrue(scheduler.delays().isEmpty());
    }

    @Test
    void verifiedDragonPolicyBlocksDragonSpawnDelayedUntilAPlayerEnters() {
        DragonScenario scenario = dragonScenario(List.of(List.of()));
        ControllableScheduler scheduler = new ControllableScheduler();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null, scheduler);
        CompletableFuture<PostResetResult> result = initializer.apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(scenario.world());

        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(result.join().successful());
        assertTrue(delayedSpawn.isCancelled());
    }

    @Test
    void configuredDragonPolicyArmsSpawnGuardAtPluginStartup() {
        World world = world("endfarm", null, List.of());
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.initializeDragonSpawnGuard(List.of(dragonDisabledConfig()));
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(world);

        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(delayedSpawn.isCancelled());
    }

    @Test
    void oneTimeDragonOverrideSkipsBattleStateSearchesAndDelays() {
        TestDragon dragon = enderDragon(true);
        DragonScenario scenario = dragonScenario(List.of(List.of(dragon.entity())));
        ControllableScheduler scheduler = new ControllableScheduler();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null, scheduler);
        initializer.initializeDragonSpawnGuard(List.of(dragonDisabledConfig()));

        PostResetResult result = initializer.apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.allowingEnderDragon()
        ).join();
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(scenario.world());
        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(result.successful());
        assertFalse(scenario.previouslyKilled().get());
        assertEquals(0, scenario.battleLookups().get());
        assertEquals(0, scenario.entityLookups().get());
        assertTrue(scheduler.delays().isEmpty());
        assertFalse(dragon.removed().get());
        assertFalse(delayedSpawn.isCancelled());
    }

    @Test
    void configuredDragonTrueAllowsExistingDragonWithoutSpawningOrRemovingOne() {
        TestDragon dragon = enderDragon(true);
        DragonScenario scenario = dragonScenario(List.of(List.of(dragon.entity())));
        ControllableScheduler scheduler = new ControllableScheduler();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null, scheduler);
        initializer.initializeDragonSpawnGuard(List.of(dragonDisabledConfig()));
        FarmworldResetConfig config = config(
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(true))
                )
        );

        PostResetResult result = initializer.apply(
                config,
                scenario.world(),
                ResetOptions.defaults()
        ).join();
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(scenario.world());
        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(result.successful());
        assertFalse(scenario.previouslyKilled().get());
        assertEquals(0, scenario.battleLookups().get());
        assertEquals(0, scenario.entityLookups().get());
        assertTrue(scheduler.delays().isEmpty());
        assertFalse(dragon.removed().get());
        assertFalse(delayedSpawn.isCancelled());
        assertTrue(config.postReset().end().orElseThrow().dragon());
    }

    private static FarmworldResetConfig dragonDisabledConfig() {
        return config(
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(false))
                )
        );
    }

    private static BukkitFarmworldPostResetInitializer.ResolvedGameRule resolved(
            String name,
            Class<?> type,
            Map<String, Object> applied
    ) {
        return new BukkitFarmworldPostResetInitializer.ResolvedGameRule(
                name,
                type,
                (world, value) -> {
                    applied.put(name, value);
                    return true;
                }
        );
    }

    private static BukkitFarmworldPostResetInitializer initializer(
            BukkitFarmworldPostResetInitializer.GameRuleAccess gameRuleAccess
    ) {
        return initializer(gameRuleAccess, new DirectScheduler());
    }

    private static BukkitFarmworldPostResetInitializer initializer(
            BukkitFarmworldPostResetInitializer.GameRuleAccess gameRuleAccess,
            FarmweltScheduler scheduler
    ) {
        return new BukkitFarmworldPostResetInitializer(
                proxy(Plugin.class),
                scheduler,
                quietLogger(),
                new GameruleValueConverter(),
                gameRuleAccess
        );
    }

    private static FarmworldResetConfig config(FarmworldType type, PostResetConfig postReset) {
        String key = switch (type) {
            case OVERWORLD -> "overworld";
            case NETHER -> "nether";
            case END -> "end";
        };
        return new FarmworldResetConfig(
                key,
                type == FarmworldType.END ? "endfarm" : "farmwelt",
                true,
                Duration.ofDays(30),
                type,
                postReset
        );
    }

    private static World world(String name, WorldBorder border, List<EnderDragon> dragons) {
        return world(name, border, dragons, new AtomicInteger());
    }

    private static World world(
            String name,
            WorldBorder border,
            List<EnderDragon> dragons,
            AtomicInteger entityLookups
    ) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getWorldBorder" -> border;
                    case "getEntitiesByClass" -> {
                        entityLookups.incrementAndGet();
                        yield dragons;
                    }
                    case "toString" -> "FakeWorld[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static WorldBorder worldBorder(AtomicReference<Double> appliedSize) {
        return (WorldBorder) Proxy.newProxyInstance(
                WorldBorder.class.getClassLoader(),
                new Class<?>[]{WorldBorder.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMaxSize" -> 59_999_968.0D;
                    case "setSize" -> {
                        appliedSize.set((Double) arguments[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static CreatureSpawnEvent dragonSpawnEvent(World world) {
        EnderDragon dragon = (EnderDragon) Proxy.newProxyInstance(
                EnderDragon.class.getClassLoader(),
                new Class<?>[]{EnderDragon.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        return new CreatureSpawnEvent(
                dragon,
                CreatureSpawnEvent.SpawnReason.DEFAULT
        );
    }

    private static TestDragon enderDragon(boolean removalSucceeds) {
        return enderDragon(removalSucceeds, true);
    }

    private static TestDragon retiredEnderDragon() {
        return enderDragon(false, false);
    }

    private static TestDragon enderDragon(
            boolean removalSucceeds,
            boolean schedulerAccepts
    ) {
        AtomicBoolean removed = new AtomicBoolean();
        AtomicInteger removalAttempts = new AtomicInteger();
        UUID uniqueId = UUID.randomUUID();
        EntityScheduler entityScheduler = (EntityScheduler) Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class},
                (proxy, method, arguments) -> {
                    if ("execute".equals(method.getName())) {
                        if (!schedulerAccepts) {
                            removed.set(true);
                            return false;
                        }
                        ((Runnable) arguments[1]).run();
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        EnderDragon entity = (EnderDragon) Proxy.newProxyInstance(
                EnderDragon.class.getClassLoader(),
                new Class<?>[]{EnderDragon.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getScheduler" -> entityScheduler;
                    case "getUniqueId" -> uniqueId;
                    case "isValid" -> !removed.get();
                    case "isDead" -> removed.get();
                    case "remove" -> {
                        removalAttempts.incrementAndGet();
                        if (removalSucceeds) {
                            removed.set(true);
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        return new TestDragon(entity, removed, removalAttempts);
    }

    private static DragonScenario dragonScenario(List<List<EnderDragon>> checks) {
        return dragonScenario(checks, true);
    }

    private static DragonScenario dragonScenario(
            List<List<EnderDragon>> checks,
            boolean battleStatePersists
    ) {
        AtomicInteger checkIndex = new AtomicInteger();
        AtomicInteger battleLookups = new AtomicInteger();
        AtomicInteger entityLookups = new AtomicInteger();
        AtomicBoolean previouslyKilled = new AtomicBoolean();

        DragonBattle battle = (DragonBattle) Proxy.newProxyInstance(
                DragonBattle.class.getClassLoader(),
                new Class<?>[]{DragonBattle.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setPreviouslyKilled" -> {
                        if (battleStatePersists) {
                            previouslyKilled.set((Boolean) arguments[0]);
                        }
                        yield null;
                    }
                    case "hasBeenPreviouslyKilled" -> previouslyKilled.get();
                    case "getEnderDragon" -> {
                        List<EnderDragon> dragons = checks.get(checkIndex.get());
                        yield dragons.isEmpty() ? null : dragons.getFirst();
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "endfarm";
                    case "getEnderDragonBattle" -> {
                        battleLookups.incrementAndGet();
                        yield battle;
                    }
                    case "getEntitiesByClass" -> {
                        entityLookups.incrementAndGet();
                        yield checks.get(checkIndex.getAndIncrement());
                    }
                    case "toString" -> "FakeWorld[endfarm]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        return new DragonScenario(
                world,
                previouslyKilled,
                battleLookups,
                entityLookups
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> defaultValue(method.getReturnType())
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

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("BukkitFarmworldPostResetInitializerTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static final class DirectScheduler implements FarmweltScheduler {

        @Override
        public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public <T> CompletableFuture<T> runGlobalDelayed(
                long ticks,
                CheckedSupplier<T> operation
        ) {
            return runGlobal(operation);
        }

        @Override
        public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
            return runGlobal(operation);
        }
    }

    private static final class ControllableScheduler implements FarmweltScheduler {

        private final Deque<PendingOperation<?>> pending = new ArrayDeque<>();
        private final List<Long> delays = new java.util.ArrayList<>();

        @Override
        public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runGlobalDelayed(
                long ticks,
                CheckedSupplier<T> operation
        ) {
            CompletableFuture<T> future = new CompletableFuture<>();
            delays.add(ticks);
            pending.addLast(new PendingOperation<>(operation, future));
            return future;
        }

        @Override
        public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
            return execute(operation);
        }

        private void advance() {
            PendingOperation<?> operation = pending.removeFirst();
            operation.execute();
        }

        private List<Long> delays() {
            return List.copyOf(delays);
        }

        private <T> CompletableFuture<T> execute(CheckedSupplier<T> operation) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }

    private record PendingOperation<T>(
            FarmweltScheduler.CheckedSupplier<T> operation,
            CompletableFuture<T> future
    ) {

        private void execute() {
            try {
                future.complete(operation.get());
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        }
    }

    private record TestDragon(
            EnderDragon entity,
            AtomicBoolean removed,
            AtomicInteger removalAttempts
    ) {
    }

    private record DragonScenario(
            World world,
            AtomicBoolean previouslyKilled,
            AtomicInteger battleLookups,
            AtomicInteger entityLookups
    ) {
    }
}
