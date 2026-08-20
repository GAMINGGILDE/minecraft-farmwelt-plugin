package de.minecraftgilde.farmwelt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class MessageServiceTest {

    @Test
    void staffPermissionAndMessageAreHandledInRecipientEntityContext() {
        TestPlayer permittedPlayer = new TestPlayer(true);
        TestPlayer deniedPlayer = new TestPlayer(false);
        Plugin plugin = plugin(List.of(permittedPlayer.player, deniedPlayer.player));

        MessageService.scheduleStaffNotification(
                plugin,
                "farmwelt.notify",
                Component.text("Test")
        );

        assertEquals(0, permittedPlayer.permissionChecks.get());
        assertEquals(0, permittedPlayer.messages.get());
        assertEquals(0, deniedPlayer.permissionChecks.get());
        assertEquals(0, deniedPlayer.messages.get());

        permittedPlayer.runScheduledTask();
        deniedPlayer.runScheduledTask();

        assertEquals(1, permittedPlayer.permissionChecks.get());
        assertEquals(1, permittedPlayer.messages.get());
        assertEquals(1, deniedPlayer.permissionChecks.get());
        assertEquals(0, deniedPlayer.messages.get());
    }

    private static Plugin plugin(List<Player> players) {
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getOnlinePlayers" -> players;
                    case "toString" -> "TestServer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        Logger logger = Logger.getLogger(MessageServiceTest.class.getName());
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> logger;
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
        private final AtomicInteger permissionChecks = new AtomicInteger();
        private final AtomicInteger messages = new AtomicInteger();
        private Runnable scheduledTask;

        private TestPlayer(boolean permitted) {
            EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
                    EntityScheduler.class.getClassLoader(),
                    new Class<?>[]{EntityScheduler.class},
                    (proxy, method, arguments) -> {
                        if ("execute".equals(method.getName())) {
                            scheduledTask = (Runnable) arguments[1];
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
                        case "hasPermission" -> {
                            permissionChecks.incrementAndGet();
                            yield permitted;
                        }
                        case "sendMessage" -> {
                            messages.incrementAndGet();
                            yield null;
                        }
                        case "toString" -> "TestPlayer";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private void runScheduledTask() {
            scheduledTask.run();
        }
    }
}
