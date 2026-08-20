package de.minecraftgilde.farmwelt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.minecraftgilde.farmwelt.gui.FarmweltMenuItem;
import de.minecraftgilde.farmwelt.gui.TeleportAction;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class FarmweltTeleportServiceTest {

    @Test
    void consoleCommandRunsGloballyAndFailureMessageReturnsToEntityContext() {
        TestPlayer player = new TestPlayer();
        RecordingGlobalRegionScheduler globalScheduler = new RecordingGlobalRegionScheduler();
        AtomicInteger dispatches = new AtomicInteger();
        Plugin plugin = plugin(globalScheduler, dispatches);
        FarmweltTeleportService service = new FarmweltTeleportService(plugin, key -> true);
        FarmweltMenuItem menuItem = new FarmweltMenuItem(
                "overworld",
                "Farmwelt",
                null,
                11,
                List.of(),
                new TeleportAction("command", "console", "say {player}")
        );

        service.teleport(player.player, menuItem);

        assertEquals(0, dispatches.get());
        assertEquals(1, player.scheduledTasks.size());
        player.runNextTask();

        assertEquals(0, dispatches.get());
        assertNotNull(globalScheduler.scheduledTask);
        assertEquals(1, player.messages.get());

        globalScheduler.runScheduledTask();

        assertEquals(1, dispatches.get());
        assertEquals(1, player.messages.get());
        assertEquals(1, player.scheduledTasks.size());

        player.runNextTask();

        assertEquals(2, player.messages.get());
    }

    private static Plugin plugin(
            GlobalRegionScheduler globalScheduler,
            AtomicInteger dispatches
    ) {
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getGlobalRegionScheduler" -> globalScheduler;
                    case "dispatchCommand" -> {
                        dispatches.incrementAndGet();
                        yield false;
                    }
                    case "toString" -> "TestServer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        Logger logger = Logger.getLogger(FarmweltTeleportServiceTest.class.getName());
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

        private final Queue<Runnable> scheduledTasks = new ArrayDeque<>();
        private final AtomicInteger messages = new AtomicInteger();
        private final Player player;

        private TestPlayer() {
            EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
                    EntityScheduler.class.getClassLoader(),
                    new Class<?>[]{EntityScheduler.class},
                    (proxy, method, arguments) -> {
                        if ("execute".equals(method.getName())) {
                            scheduledTasks.add((Runnable) arguments[1]);
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
                        case "getName" -> "Alex";
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

        private void runNextTask() {
            scheduledTasks.remove().run();
        }
    }

    private static final class RecordingGlobalRegionScheduler implements GlobalRegionScheduler {

        private Runnable scheduledTask;

        @Override
        public void execute(Plugin plugin, Runnable run) {
            scheduledTask = run;
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long delayTicks
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long initialDelayTicks,
                long periodTicks
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        private void runScheduledTask() {
            scheduledTask.run();
        }
    }
}
