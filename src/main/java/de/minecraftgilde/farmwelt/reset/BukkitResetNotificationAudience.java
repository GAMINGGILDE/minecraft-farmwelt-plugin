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

    private final FoliaFarmweltScheduler scheduler;

    public BukkitResetNotificationAudience(JavaPlugin plugin, FoliaFarmweltScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void broadcast(String message) {
        Component component = LEGACY_AMPERSAND.deserialize(
                Objects.requireNonNull(message, "message")
        );
        scheduler.runGlobal(() -> {
            for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                scheduler.runEntity(player, () -> {
                    player.sendMessage(component);
                    return null;
                }, accepted -> null).whenComplete((ignored, failure) -> reportFailure(failure));
            }
            return null;
        }).whenComplete((ignored, failure) -> reportFailure(failure));
    }

    private void reportFailure(Throwable failure) {
        if (failure != null && plugin.isEnabled()) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Reset-Broadcast konnte nicht zugestellt werden.", failure);
        }
    }
}
