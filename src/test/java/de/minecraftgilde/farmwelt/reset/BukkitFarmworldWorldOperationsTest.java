package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BukkitFarmworldWorldOperationsTest {

    @Test
    void reportsOnlyPlayersWhoseTeleportActuallySucceeded() {
        TestWorld resetWorld = new TestWorld(
                "farmwelt",
                NamespacedKey.fromString("worlds:farmwelt")
        );
        TestWorld safeWorld = new TestWorld(
                "world",
                NamespacedKey.minecraft("overworld")
        );
        TestPlayer first = new TestPlayer("Alex", resetWorld.world, true, true);
        TestPlayer second = new TestPlayer("Steve", resetWorld.world, true, true);
        TestPlayer failed = new TestPlayer("Sam", resetWorld.world, false, true);
        TestPlayer uninvolved = new TestPlayer("Robin", safeWorld.world, true, true);
        resetWorld.players.addAll(List.of(first, second, failed, uninvolved));
        BukkitFarmworldWorldOperations operations = operations(resetWorld, safeWorld);

        FarmworldEvacuationResult result = operations.evacuatePlayers(resetWorld.world).join();

        assertFalse(result.successful());
        assertEquals(List.of(first.player, second.player), result.evacuatedPlayers());
        assertEquals(1, first.teleportAttempts);
        assertEquals(1, second.teleportAttempts);
        assertEquals(1, failed.teleportAttempts);
        assertEquals(0, uninvolved.teleportAttempts);
    }

    @Test
    void playerWhoLeavesBeforeEntityTaskIsNotReportedAsEvacuated() {
        TestWorld resetWorld = new TestWorld(
                "farmwelt",
                NamespacedKey.fromString("worlds:farmwelt")
        );
        TestWorld safeWorld = new TestWorld(
                "world",
                NamespacedKey.minecraft("overworld")
        );
        TestPlayer player = new TestPlayer("Alex", resetWorld.world, true, false);
        resetWorld.players.add(player);
        BukkitFarmworldWorldOperations operations = operations(resetWorld, safeWorld);

        CompletableFuture<FarmworldEvacuationResult> evacuation =
                operations.evacuatePlayers(resetWorld.world);
        player.currentWorld = safeWorld.world;
        player.runScheduledTask();
        FarmworldEvacuationResult result = evacuation.join();

        assertTrue(result.successful());
        assertEquals(List.of(), result.evacuatedPlayers());
        assertEquals(0, player.teleportAttempts);
    }

    @Test
    void retiredPlayerSchedulerFailsEvacuationWithoutCrashing() {
        TestWorld resetWorld = new TestWorld(
                "farmwelt",
                NamespacedKey.fromString("worlds:farmwelt")
        );
        TestWorld safeWorld = new TestWorld(
                "world",
                NamespacedKey.minecraft("overworld")
        );
        TestPlayer player = new TestPlayer("Alex", resetWorld.world, true, false);
        resetWorld.players.add(player);
        BukkitFarmworldWorldOperations operations = operations(resetWorld, safeWorld);

        CompletableFuture<FarmworldEvacuationResult> evacuation =
                operations.evacuatePlayers(resetWorld.world);
        player.runRetiredTask();
        player.runScheduledTask();
        FarmworldEvacuationResult result = evacuation.join();

        assertFalse(result.successful());
        assertEquals(List.of(), result.evacuatedPlayers());
        assertEquals(0, player.teleportAttempts);
    }

    @Test
    void synchronousTeleportFailureInDeferredEntityCallbackFinishesEvacuation() {
        var farm = new TestWorld("farmwelt", NamespacedKey.fromString("worlds:farmwelt"));
        var safe = new TestWorld("world", NamespacedKey.minecraft("overworld"));
        var player = new TestPlayer("Alex", farm.world, true, false);
        farm.players.add(player);
        player.teleportFailure = new IllegalStateException("Teleport abgelehnt");
        var evacuation = operations(farm, safe).evacuatePlayers(farm.world);
        player.runScheduledTask();
        assertTrue(evacuation.isDone());
        assertEquals(player.teleportFailure, evacuation.join().failure().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "cancelled", "null", "open", "rejected"})
    void independentEntityAndTeleportOutcomes(String outcome) {
        var farm = new TestWorld("farmwelt", NamespacedKey.fromString("worlds:farmwelt"));
        var safe = new TestWorld("world", NamespacedKey.minecraft("overworld"));
        var player = new TestPlayer("Alex", farm.world, true, false);
        farm.players.add(player);
        var external = new CompletableFuture<Boolean>();
        player.teleportResult = outcome.equals("null") ? () -> null : () -> external;
        player.accepted = !outcome.equals("rejected");
        var result = operations(farm, safe).evacuatePlayers(farm.world);
        if (player.accepted) player.runScheduledTask();
        switch (outcome) {
            case "open" -> {
                assertFalse(result.isDone());
                player.runRetiredTask();
                assertFalse(result.isDone());
                assertFalse(external.isCancelled());
                external.complete(true);
            }
            case "exception" -> external.completeExceptionally(new IllegalStateException("Teleportfehler"));
            case "cancelled" -> external.cancel(false);
        }
        assertTrue(result.isDone());
        assertEquals(outcome.equals("open"), result.join().successful());
        assertEquals(outcome.equals("rejected") ? 0 : 1, player.teleportAttempts);
        assertEquals(outcome.equals("cancelled") || outcome.equals("null"),
                result.join().failure().orElse(null) instanceof FarmworldEvacuationResult.UnconfirmedTeleportException);
    }

    private static BukkitFarmworldWorldOperations operations(
            TestWorld resetWorld,
            TestWorld safeWorld
    ) {
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorlds" -> List.of(safeWorld.world, resetWorld.world);
                    case "toString" -> "FakeServer";
                    default -> defaultValue(method.getReturnType());
                }
        );
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getName" -> "FarmweltTest";
                    case "toString" -> "FarmweltTestPlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        return new BukkitFarmworldWorldOperations(
                plugin,
                () -> java.util.Set.of(resetWorld.name)
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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

    private static final class TestWorld {

        private final String name;
        private final List<TestPlayer> players = new ArrayList<>();
        private final World world;

        private TestWorld(String name, NamespacedKey key) {
            this.name = name;
            this.world = (World) Proxy.newProxyInstance(
                    World.class.getClassLoader(),
                    new Class<?>[]{World.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getKey" -> key;
                        case "getEnvironment" -> World.Environment.NORMAL;
                        case "getSpawnLocation" -> new Location((World) proxy, 0.5, 64, 0.5);
                        case "getPlayers" -> players.stream()
                                .filter(player -> player.currentWorld == proxy)
                                .map(player -> player.player)
                                .toList();
                        case "toString" -> "FakeWorld[" + name + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static final class TestPlayer {

        private final Player player;
        private World currentWorld;
        private Runnable scheduledTask;
        private Runnable retiredTask;
        private RuntimeException teleportFailure;
        private boolean accepted = true;
        private java.util.function.Supplier<CompletableFuture<Boolean>> teleportResult;
        private int teleportAttempts;

        private TestPlayer(
                String name,
                World currentWorld,
                boolean teleportSuccessful,
                boolean runImmediately
        ) {
            this.currentWorld = currentWorld;
            EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
                    EntityScheduler.class.getClassLoader(),
                    new Class<?>[]{EntityScheduler.class},
                    (proxy, method, arguments) -> {
                        if ("execute".equals(method.getName())) {
                            scheduledTask = (Runnable) arguments[1];
                            retiredTask = (Runnable) arguments[2];
                            if (runImmediately) {
                                scheduledTask.run();
                            }
                            return accepted;
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
            this.player = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getWorld" -> this.currentWorld;
                        case "getScheduler" -> scheduler;
                        case "teleportAsync" -> {
                            teleportAttempts++;
                            if (teleportFailure != null) throw teleportFailure;
                            if (teleportResult != null) yield teleportResult.get();
                            if (teleportSuccessful) {
                                this.currentWorld = ((Location) arguments[0]).getWorld();
                            }
                            yield CompletableFuture.completedFuture(teleportSuccessful);
                        }
                        case "toString" -> "FakePlayer[" + name + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private void runScheduledTask() {
            scheduledTask.run();
        }

        private void runRetiredTask() {
            retiredTask.run();
        }
    }
}
