package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Stellt eine persönliche Nachricht Folia-sicher im Entity-Kontext des Spielers zu. */
public final class BukkitResetPlayerNotificationAudience
        implements ResetPlayerNotificationAudience {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND =
            LegacyComponentSerializer.legacyAmpersand();

    private final FoliaFarmweltScheduler scheduler;

    public BukkitResetPlayerNotificationAudience(Plugin plugin) {
        this(new FoliaFarmweltScheduler(plugin));
    }

    public BukkitResetPlayerNotificationAudience(FoliaFarmweltScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    @Override
    public CompletableFuture<Void> send(Player player, String message) {
        Objects.requireNonNull(player, "player");
        Component component = LEGACY_AMPERSAND.deserialize(
                Objects.requireNonNull(message, "message")
        );
        return scheduler.runEntity(player, () -> {
            if (player.isOnline()) player.sendMessage(component);
            return null;
        }, accepted -> {
            if (!accepted) throw new IllegalStateException("Der Entity-Scheduler hat die Spielernachricht abgelehnt.");
            return null;
        });
    }
}
