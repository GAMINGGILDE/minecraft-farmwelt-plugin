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

    private final Plugin plugin;

    public BukkitResetPlayerNotificationAudience(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CompletableFuture<Void> send(Player player, String message) {
        Objects.requireNonNull(player, "player");
        Component component = LEGACY_AMPERSAND.deserialize(
                Objects.requireNonNull(message, "message")
        );
        CompletableFuture<Void> delivery = new CompletableFuture<>();
        try {
            boolean scheduled = player.getScheduler().execute(
                    plugin,
                    () -> deliver(player, component, delivery),
                    () -> delivery.complete(null),
                    1L
            );
            if (!scheduled) {
                delivery.completeExceptionally(new IllegalStateException(
                        "Der Entity-Scheduler hat die Spielernachricht abgelehnt."
                ));
            }
        } catch (RuntimeException exception) {
            delivery.completeExceptionally(exception);
        }
        return delivery;
    }

    private void deliver(
            Player player,
            Component component,
            CompletableFuture<Void> delivery
    ) {
        try {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
            delivery.complete(null);
        } catch (RuntimeException exception) {
            delivery.completeExceptionally(exception);
        }
    }
}
