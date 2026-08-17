package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.EnderDragon;
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
    void removesEnderDragonForNormalResetButOneTimeOverrideLeavesItAlone() {
        AtomicBoolean removed = new AtomicBoolean();
        AtomicInteger entityLookups = new AtomicInteger();
        EnderDragon dragon = enderDragon(removed);
        FarmworldResetConfig config = config(
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(false))
                )
        );
        BukkitFarmworldPostResetInitializer initializer = initializer(name -> null);

        assertTrue(initializer.apply(
                config,
                world("endfarm", null, List.of(dragon), entityLookups),
                ResetOptions.defaults()
        ).join().successful());
        assertTrue(removed.get());

        removed.set(false);
        entityLookups.set(0);
        assertTrue(initializer.apply(
                config,
                world("endfarm", null, List.of(dragon), entityLookups),
                ResetOptions.allowingEnderDragon()
        ).join().successful());
        assertFalse(removed.get());
        assertEquals(0, entityLookups.get());
        assertFalse(config.postReset().end().orElseThrow().dragon());
    }

    @Test
    void configuredDragonTrueAllowsExistingDragonWithoutSpawningOrRemovingOne() {
        AtomicInteger entityLookups = new AtomicInteger();
        FarmworldResetConfig config = config(
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(true))
                )
        );

        PostResetResult result = initializer(name -> null).apply(
                config,
                world("endfarm", null, List.of(), entityLookups),
                ResetOptions.defaults()
        ).join();

        assertTrue(result.successful());
        assertEquals(0, entityLookups.get());
        assertTrue(config.postReset().end().orElseThrow().dragon());
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
        return new BukkitFarmworldPostResetInitializer(
                proxy(Plugin.class),
                new DirectScheduler(),
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

    private static EnderDragon enderDragon(AtomicBoolean removed) {
        EntityScheduler entityScheduler = (EntityScheduler) Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class},
                (proxy, method, arguments) -> {
                    if ("execute".equals(method.getName())) {
                        ((Runnable) arguments[1]).run();
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        return (EnderDragon) Proxy.newProxyInstance(
                EnderDragon.class.getClassLoader(),
                new Class<?>[]{EnderDragon.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getScheduler" -> entityScheduler;
                    case "remove" -> {
                        removed.set(true);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
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
        public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
            return runGlobal(operation);
        }
    }
}
