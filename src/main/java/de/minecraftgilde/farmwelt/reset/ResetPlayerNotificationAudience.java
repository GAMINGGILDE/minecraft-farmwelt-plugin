package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** Kleine Ausgabeschnittstelle für personenbezogene Reset-Nachrichten. */
@FunctionalInterface
public interface ResetPlayerNotificationAudience {

    CompletableFuture<Void> send(Player player, String message);

    static ResetPlayerNotificationAudience noop() {
        return (player, message) -> CompletableFuture.completedFuture(null);
    }
}
