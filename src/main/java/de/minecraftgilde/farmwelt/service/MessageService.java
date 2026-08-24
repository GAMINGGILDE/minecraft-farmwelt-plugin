package de.minecraftgilde.farmwelt.service;

import de.minecraftgilde.farmwelt.config.ConfigManager;
import de.minecraftgilde.farmwelt.model.ResourceMatch;
import de.minecraftgilde.farmwelt.model.ViolationSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class MessageService {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final ConfigManager configManager;

    public MessageService(Plugin plugin, ConfigManager configManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configManager = Objects.requireNonNull(configManager, "configManager");
    }

    public void sendResourceAudit(Player player, Block block, ResourceMatch match) {
        if (configManager.isAuditLogToConsole()) {
            plugin.getLogger().info(createConsoleAuditMessage(player, block, match));
        }

        if (!configManager.isAuditNotifyStaff()) {
            return;
        }

        String notifyPermission = configManager.getNotifyPermission();
        String message = replaceAuditPlaceholders(configManager.getStaffMessage(), player, block, match);
        scheduleStaffNotification(
                plugin,
                notifyPermission,
                LEGACY_AMPERSAND.deserialize(message)
        );
    }

    public void logResourceAudit(Player player, Block block, ResourceMatch match) {
        if (configManager.isAuditLogToConsole()) {
            plugin.getLogger().info(createConsoleAuditMessage(player, block, match));
        }
    }

    public void sendViolationWarning(Player player, ViolationSnapshot snapshot, String message, int windowSeconds) {
        if (message == null || message.isBlank()) {
            return;
        }

        player.sendMessage(LEGACY_AMPERSAND.deserialize(replaceViolationPlaceholders(message, player, snapshot, windowSeconds)));
    }

    public void sendViolationStaffNotification(Player player, ViolationSnapshot snapshot, String message, int windowSeconds) {
        if (message == null || message.isBlank()) {
            return;
        }

        String notifyPermission = configManager.getNotifyPermission();
        String formattedMessage = replaceViolationPlaceholders(message, player, snapshot, windowSeconds);
        scheduleStaffNotification(
                plugin,
                notifyPermission,
                LEGACY_AMPERSAND.deserialize(formattedMessage)
        );
    }

    public void sendJailStaffNotification(Player player, ViolationSnapshot snapshot, String message, int windowSeconds) {
        sendViolationStaffNotification(player, snapshot, message, windowSeconds);
    }

    public void sendJailPlayerMessage(Player player, ViolationSnapshot snapshot, String message, int windowSeconds) {
        if (message == null || message.isBlank()) {
            return;
        }

        player.sendMessage(LEGACY_AMPERSAND.deserialize(replaceViolationPlaceholders(message, player, snapshot, windowSeconds)));
    }

    public void sendViolationCancelBreak(
            Player player,
            ViolationSnapshot snapshot,
            String message,
            String actionbarMessage,
            int windowSeconds
    ) {
        if (message != null && !message.isBlank()) {
            player.sendMessage(LEGACY_AMPERSAND.deserialize(replaceViolationPlaceholders(message, player, snapshot, windowSeconds)));
        }

        if (actionbarMessage != null && !actionbarMessage.isBlank()) {
            player.sendActionBar(LEGACY_AMPERSAND.deserialize(replaceViolationPlaceholders(actionbarMessage, player, snapshot, windowSeconds)));
        }
    }

    private String createConsoleAuditMessage(Player player, Block block, ResourceMatch match) {
        return "[Audit] Spieler " + player.getName()
                + " (" + player.getUniqueId() + ") hat "
                + match.material().name()
                + " in " + block.getWorld().getName()
                + " bei x=" + block.getX()
                + " y=" + block.getY()
                + " z=" + block.getZ()
                + " abgebaut. Kategorie: " + match.category();
    }

    private String replaceAuditPlaceholders(String message, Player player, Block block, ResourceMatch match) {
        return message
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{world}", block.getWorld().getName())
                .replace("{x}", Integer.toString(block.getX()))
                .replace("{y}", Integer.toString(block.getY()))
                .replace("{z}", Integer.toString(block.getZ()))
                .replace("{block}", match.material().name())
                .replace("{category}", match.category());
    }

    public String replaceViolationPlaceholders(String message, Player player, ViolationSnapshot snapshot, int windowSeconds) {
        return message
                .replace("{player}", player.getName())
                .replace("{uuid}", snapshot.playerId().toString())
                .replace("{world}", snapshot.latestWorld())
                .replace("{x}", Integer.toString(snapshot.latestX()))
                .replace("{y}", Integer.toString(snapshot.latestY()))
                .replace("{z}", Integer.toString(snapshot.latestZ()))
                .replace("{block}", snapshot.latestBlock().name())
                .replace("{category}", snapshot.latestCategory())
                .replace("{count}", Integer.toString(snapshot.currentCount()))
                .replace("{blocked-count}", Integer.toString(snapshot.blockedCount()))
                .replace("{window-seconds}", Integer.toString(windowSeconds));
    }

    static void scheduleStaffNotification(
            Plugin plugin,
            String notifyPermission,
            Component message
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(notifyPermission, "notifyPermission");
        Objects.requireNonNull(message, "message");
        for (Player onlinePlayer : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            try {
                boolean scheduled = onlinePlayer.getScheduler().execute(
                        plugin,
                        () -> {
                            if (notifyPermission.isBlank()
                                    || onlinePlayer.hasPermission(notifyPermission)) {
                                onlinePlayer.sendMessage(message);
                            }
                        },
                        () -> { },
                        1L
                );
                if (!scheduled) {
                    plugin.getLogger().warning(
                            "Staff-Nachricht konnte nicht im Entity-Kontext geplant werden."
                    );
                }
            } catch (RuntimeException exception) {
                // Ein einzelner ungültiger Entity-Scheduler darf andere Empfänger nicht auslassen.
                plugin.getLogger().log(
                        Level.WARNING,
                        "Staff-Nachricht konnte nicht im Entity-Kontext geplant werden.",
                        exception
                );
            }
        }
    }
}
