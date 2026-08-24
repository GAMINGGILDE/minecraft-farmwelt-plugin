package de.minecraftgilde.farmwelt.reset;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Stellt Broadcasts Folia-sicher im jeweiligen Entity-Kontext der Spieler zu. */
public final class BukkitResetNotificationAudience implements ResetNotificationAudience {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND =
            LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;

    public BukkitResetNotificationAudience(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void broadcast(String message) {
        Component component = LEGACY_AMPERSAND.deserialize(
                Objects.requireNonNull(message, "message")
        );
        RuntimeException firstFailure = null;
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            try {
                player.getScheduler().execute(
                        plugin,
                        () -> player.sendMessage(component),
                        () -> { },
                        1L
                );
            } catch (RuntimeException exception) {
                // Ein einzelner ungültiger Entity-Scheduler darf andere Spieler nicht auslassen.
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
