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
        CompletableFuture<PostResetResult> result = applyWithConfiguredGuard(
                initializer(name -> null, scheduler),
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        );

        assertTrue(scenario.previouslyKilled().get());
        assertTrue(scenario.endPortalGenerated().get());
        assertEquals(1, scheduler.regionExecutions());
        assertEquals(scenario.world(), scheduler.regionWorld());
        assertEquals(0, scheduler.regionChunkX());
        assertEquals(0, scheduler.regionChunkZ());
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
        CompletableFuture<PostResetResult> result = applyWithConfiguredGuard(
                initializer(name -> null, scheduler),
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
        CompletableFuture<PostResetResult> result = applyWithConfiguredGuard(
                initializer(name -> null, scheduler),
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
        CompletableFuture<PostResetResult> result = applyWithConfiguredGuard(
                initializer(name -> null, scheduler),
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
        CompletableFuture<PostResetResult> result = applyWithConfiguredGuard(
                initializer(name -> null, scheduler),
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
    void portalGenerationFailureFailsPostResetPolicy() {
        DragonScenario scenario = dragonScenario(
                List.of(List.of()),
                true,
                new IllegalStateException("portal generation failed")
        );

        PostResetResult result = applyWithConfiguredGuard(
                initializer(name -> null),
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.defaults()
        ).join();

        assertFalse(result.successful());
        assertFalse(scenario.previouslyKilled().get());
    }

    @Test
    void verifiedDragonPolicyBlocksDragonSpawnDelayedUntilAPlayerEnters() {
        DragonScenario scenario = dragonScenario(List.of(List.of()));
        ControllableScheduler scheduler = new ControllableScheduler();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null, scheduler);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonDisabledConfig()));
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
        initializer.synchronizeDragonSpawnGuards(List.of(dragonDisabledConfig()));
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(world);

        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(delayedSpawn.isCancelled());
    }

    @Test
    void reloadFromDragonFalseToTrueDisablesConfiguredGuard() {
        World world = world("endfarm", null, List.of());
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, false)));
        assertTrue(spawnIsSuppressed(initializer, world));

        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, true)));

        assertFalse(spawnIsSuppressed(initializer, world));
    }

    @Test
    void reloadFromDragonTrueToFalseEnablesConfiguredGuard() {
        World world = world("endfarm", null, List.of());
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, true)));
        assertFalse(spawnIsSuppressed(initializer, world));

        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, false)));

        assertTrue(spawnIsSuppressed(initializer, world));
    }

    @Test
    void reloadRemovesGuardForWorldNoLongerConfigured() {
        World world = world("endfarm", null, List.of());
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, false)));
        assertTrue(spawnIsSuppressed(initializer, world));

        initializer.synchronizeDragonSpawnGuards(List.of());

        assertFalse(spawnIsSuppressed(initializer, world));
    }

    @Test
    void reloadDisablesGuardWhenFarmworldIsDisabled() {
        World world = world("endfarm", null, List.of());
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, false)));
        assertTrue(spawnIsSuppressed(initializer, world));

        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(false, false)));

        assertFalse(spawnIsSuppressed(initializer, world));
    }

    @Test
    void configuredGuardDoesNotAffectOtherEndWorlds() {
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonConfig(true, false)));

        assertFalse(spawnIsSuppressed(initializer, world("another_end", null, List.of())));
    }

    @Test
    void runningResetKeepsSnapshotSuppressionAcrossReload() {
        FarmworldResetConfig resetSnapshot = dragonConfig(true, false);
        FarmworldResetConfig reloadedConfig = dragonConfig(true, true);
        DragonScenario scenario = dragonScenario(List.of(List.of()));
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(resetSnapshot));
        FarmworldPostResetInitializer.ResetScope resetScope = initializer.beginReset(
                resetSnapshot,
                ResetOptions.defaults()
        );

        initializer.synchronizeDragonSpawnGuards(List.of(reloadedConfig));

        assertTrue(spawnIsSuppressed(initializer, scenario.world()));
        assertTrue(initializer.apply(
                resetSnapshot,
                scenario.world(),
                ResetOptions.defaults()
        ).join().successful());
        resetScope.close();
        assertFalse(spawnIsSuppressed(initializer, scenario.world()));
    }

    @Test
    void runningDragonAllowedResetKeepsSnapshotAllowAcrossReload() {
        FarmworldResetConfig resetSnapshot = dragonConfig(true, true);
        FarmworldResetConfig reloadedConfig = dragonConfig(true, false);
        World world = world("endfarm", null, List.of());
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(resetSnapshot));
        FarmworldPostResetInitializer.ResetScope resetScope = initializer.beginReset(
                resetSnapshot,
                ResetOptions.defaults()
        );

        initializer.synchronizeDragonSpawnGuards(List.of(reloadedConfig));

        assertFalse(spawnIsSuppressed(initializer, world));
        resetScope.close();
        assertTrue(spawnIsSuppressed(initializer, world));
    }

    @Test
    void oneTimeDragonOverrideSkipsBattleStateSearchesAndDelays() {
        TestDragon dragon = enderDragon(true);
        DragonScenario scenario = dragonScenario(List.of(List.of(dragon.entity())));
        ControllableScheduler scheduler = new ControllableScheduler();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null, scheduler);
        initializer.synchronizeDragonSpawnGuards(List.of(dragonDisabledConfig()));
        FarmworldPostResetInitializer.ResetScope resetScope = initializer.beginReset(
                dragonDisabledConfig(),
                ResetOptions.allowingEnderDragon()
        );

        PostResetResult result = initializer.apply(
                dragonDisabledConfig(),
                scenario.world(),
                ResetOptions.allowingEnderDragon()
        ).join();
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(scenario.world());
        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(result.successful());
        assertFalse(scenario.previouslyKilled().get());
        assertFalse(scenario.endPortalGenerated().get());
        assertEquals(0, scenario.battleLookups().get());
        assertEquals(0, scenario.entityLookups().get());
        assertEquals(0, scheduler.regionExecutions());
        assertTrue(scheduler.delays().isEmpty());
        assertFalse(dragon.removed().get());
        assertFalse(delayedSpawn.isCancelled());
        resetScope.close();

        CreatureSpawnEvent laterSpawn = dragonSpawnEvent(scenario.world());
        initializer.preventSuppressedEnderDragonSpawn(laterSpawn);
        assertTrue(laterSpawn.isCancelled());
    }

    @Test
    void failedDragonOverrideScopeRestoresConfiguredSuppression() {
        World world = world("endfarm", null, List.of());
        FarmworldResetConfig config = dragonConfig(true, false);
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(config));
        FarmworldPostResetInitializer.ResetScope resetScope = initializer.beginReset(
                config,
                ResetOptions.allowingEnderDragon()
        );
        assertFalse(spawnIsSuppressed(initializer, world));

        // The engine closes this scope from whenComplete for both success and failure results.
        resetScope.close();

        assertTrue(spawnIsSuppressed(initializer, world));
    }

    @Test
    void pluginShutdownClearsTemporaryDragonOverrideState() {
        World world = world("endfarm", null, List.of());
        FarmworldResetConfig config = dragonConfig(true, false);
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);
        initializer.synchronizeDragonSpawnGuards(List.of(config));
        FarmworldPostResetInitializer.ResetScope resetScope = initializer.beginReset(
                config,
                ResetOptions.allowingEnderDragon()
        );
        assertFalse(spawnIsSuppressed(initializer, world));

        initializer.shutdownDragonSpawnGuards();
        initializer.synchronizeDragonSpawnGuards(List.of(config));

        assertTrue(spawnIsSuppressed(initializer, world));
        resetScope.close();
    }

    @Test
    void configuredDragonTrueAllowsExistingDragonWithoutSpawningOrRemovingOne() {
        TestDragon dragon = enderDragon(true);
        DragonScenario scenario = dragonScenario(List.of(List.of(dragon.entity())));
        ControllableScheduler scheduler = new ControllableScheduler();
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null, scheduler);
        FarmworldResetConfig config = config(
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(true))
                )
        );
        initializer.synchronizeDragonSpawnGuards(List.of(config));

        PostResetResult result = initializer.apply(
                config,
                scenario.world(),
                ResetOptions.defaults()
        ).join();
        CreatureSpawnEvent delayedSpawn = dragonSpawnEvent(scenario.world());
        initializer.preventSuppressedEnderDragonSpawn(delayedSpawn);

        assertTrue(result.successful());
        assertFalse(scenario.previouslyKilled().get());
        assertFalse(scenario.endPortalGenerated().get());
        assertEquals(0, scenario.battleLookups().get());
        assertEquals(0, scenario.entityLookups().get());
        assertEquals(0, scheduler.regionExecutions());
        assertTrue(scheduler.delays().isEmpty());
        assertFalse(dragon.removed().get());
        assertFalse(delayedSpawn.isCancelled());
        assertTrue(config.postReset().end().orElseThrow().dragon());
    }

    private static FarmworldResetConfig dragonDisabledConfig() {
        return dragonConfig(true, false);
    }

    private static FarmworldResetConfig dragonConfig(boolean enabled, boolean dragon) {
        return new FarmworldResetConfig(
                "end",
                "endfarm",
                enabled,
                Duration.ofDays(30),
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(dragon))
                )
        );
    }

    private static boolean spawnIsSuppressed(
            BukkitFarmworldPostResetInitializer initializer,
            World world
    ) {
        CreatureSpawnEvent event = dragonSpawnEvent(world);
        initializer.preventSuppressedEnderDragonSpawn(event);
        return event.isCancelled();
    }

    private static CompletableFuture<PostResetResult> applyWithConfiguredGuard(
            BukkitFarmworldPostResetInitializer initializer,
            FarmworldResetConfig config,
            World world,
            ResetOptions options
    ) {
        initializer.synchronizeDragonSpawnGuards(List.of(config));
        return initializer.apply(config, world, options);
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
        return dragonScenario(checks, true, null);
    }

    private static DragonScenario dragonScenario(
            List<List<EnderDragon>> checks,
            boolean battleStatePersists
    ) {
        return dragonScenario(checks, battleStatePersists, null);
    }

    private static DragonScenario dragonScenario(
            List<List<EnderDragon>> checks,
            boolean battleStatePersists,
            RuntimeException portalFailure
    ) {
        AtomicInteger checkIndex = new AtomicInteger();
        AtomicInteger battleLookups = new AtomicInteger();
        AtomicInteger entityLookups = new AtomicInteger();
        AtomicBoolean previouslyKilled = new AtomicBoolean();
        AtomicBoolean endPortalGenerated = new AtomicBoolean();

        DragonBattle battle = (DragonBattle) Proxy.newProxyInstance(
                DragonBattle.class.getClassLoader(),
                new Class<?>[]{DragonBattle.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "generateEndPortal" -> {
                        if (portalFailure != null) {
                            throw portalFailure;
                        }
                        endPortalGenerated.set((Boolean) arguments[0]);
                        yield true;
                    }
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
                endPortalGenerated,
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
        public <T> CompletableFuture<T> runRegion(
                World world,
                int chunkX,
                int chunkZ,
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
        private int regionExecutions;
        private World regionWorld;
        private int regionChunkX;
        private int regionChunkZ;

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
        public <T> CompletableFuture<T> runRegion(
                World world,
                int chunkX,
                int chunkZ,
                CheckedSupplier<T> operation
        ) {
            regionExecutions++;
            regionWorld = world;
            regionChunkX = chunkX;
            regionChunkZ = chunkZ;
            return execute(operation);
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

        private int regionExecutions() {
            return regionExecutions;
        }

        private World regionWorld() {
            return regionWorld;
        }

        private int regionChunkX() {
            return regionChunkX;
        }

        private int regionChunkZ() {
            return regionChunkZ;
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
            AtomicBoolean endPortalGenerated,
            AtomicInteger battleLookups,
            AtomicInteger entityLookups
    ) {
    }
}
