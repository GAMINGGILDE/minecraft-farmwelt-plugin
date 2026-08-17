package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.FarmweltPlugin;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/** Sends command responses in a Folia-safe scheduler context. */
final class BukkitCommandAudience implements AdminCommandAudience {

    private final FarmweltPlugin plugin;
    private final CommandSender sender;

    BukkitCommandAudience(FarmweltPlugin plugin, CommandSender sender) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission(permission);
    }

    @Override
    public String name() {
        return sender instanceof ConsoleCommandSender ? "CONSOLE" : sender.getName();
    }

    @Override
    public void sendMessages(List<String> messages) {
        List<String> immutableMessages = List.copyOf(messages);
        Runnable sendOperation = () -> immutableMessages.forEach(sender::sendMessage);
        if (sender instanceof Player player) {
            player.getScheduler().execute(plugin, sendOperation, () -> { }, 1L);
            return;
        }

        plugin.getServer().getGlobalRegionScheduler().execute(plugin, sendOperation);
    }
}
