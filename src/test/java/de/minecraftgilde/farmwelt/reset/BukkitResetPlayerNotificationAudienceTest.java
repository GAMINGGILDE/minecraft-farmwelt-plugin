package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class BukkitResetPlayerNotificationAudienceTest {

    @Test
    void deliversMessageThroughPlayerEntityScheduler() {
        TestPlayer player = new TestPlayer(true);
        BukkitResetPlayerNotificationAudience audience =
                new BukkitResetPlayerNotificationAudience(plugin());

        CompletableFuture<Void> delivery = audience.send(player.player, "&eTest");
        player.runScheduledTask();

        assertDoesNotThrow(delivery::join);
        assertEquals(1, player.messages);
    }

    @Test
    void disconnectedPlayerMaySkipMessageWithoutFailure() {
        TestPlayer player = new TestPlayer(false);
        BukkitResetPlayerNotificationAudience audience =
                new BukkitResetPlayerNotificationAudience(plugin());

        CompletableFuture<Void> delivery = audience.send(player.player, "&eTest");
        player.runScheduledTask();

        assertDoesNotThrow(delivery::join);
        assertEquals(0, player.messages);
    }

    @Test
    void schedulerRejectionIsReportedAsNotificationFailureOnly() {
        TestPlayer player = new TestPlayer(true);
        player.schedulerAccepts = false;
        BukkitResetPlayerNotificationAudience audience =
                new BukkitResetPlayerNotificationAudience(plugin());

        CompletableFuture<Void> delivery = audience.send(player.player, "&eTest");

        assertThrows(CompletionException.class, delivery::join);
        assertEquals(0, player.messages);
    }

    @Test
    void retiredSchedulerTreatsDisconnectAsNormalMessageOmission() {
        TestPlayer player = new TestPlayer(true);
        BukkitResetPlayerNotificationAudience audience =
                new BukkitResetPlayerNotificationAudience(plugin());

        CompletableFuture<Void> delivery = audience.send(player.player, "&eTest");
        player.runRetiredTask();

        assertDoesNotThrow(delivery::join);
        assertEquals(0, player.messages);
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "FarmweltTest";
                    case "toString" -> "FarmweltTestPlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
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

    private static final class TestPlayer {

        private final Player player;
        private boolean schedulerAccepts = true;
        private Runnable scheduledTask;
        private Runnable retiredTask;
        private int messages;

        private TestPlayer(boolean online) {
            EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
                    EntityScheduler.class.getClassLoader(),
                    new Class<?>[]{EntityScheduler.class},
                    (proxy, method, arguments) -> {
                        if ("execute".equals(method.getName())) {
                            if (!schedulerAccepts) {
                                return false;
                            }
                            scheduledTask = (Runnable) arguments[1];
                            retiredTask = (Runnable) arguments[2];
                            return true;
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
            this.player = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getScheduler" -> scheduler;
                        case "isOnline" -> online;
                        case "sendMessage" -> {
                            messages++;
                            yield null;
                        }
                        case "toString" -> "FakePlayer";
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
