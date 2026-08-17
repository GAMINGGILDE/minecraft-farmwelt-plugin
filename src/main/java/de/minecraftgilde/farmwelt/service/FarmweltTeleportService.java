package de.minecraftgilde.farmwelt.service;

import de.minecraftgilde.farmwelt.gui.FarmweltMenuItem;
import de.minecraftgilde.farmwelt.gui.TeleportAction;
import de.minecraftgilde.farmwelt.reset.FarmworldAvailabilityService;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarmweltTeleportService {

    private static final String TYPE_COMMAND = "command";
    private static final String SENDER_PLAYER = "player";
    private static final String SENDER_CONSOLE = "console";
    private static final String RESET_BLOCK_MESSAGE = "Diese Farmwelt wird derzeit zurückgesetzt.";

    private final JavaPlugin plugin;
    private final FarmworldAvailabilityService availabilityService;

    public FarmweltTeleportService(
            JavaPlugin plugin,
            FarmworldAvailabilityService availabilityService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.availabilityService = Objects.requireNonNull(availabilityService, "availabilityService");
    }

    public void teleport(Player player, FarmweltMenuItem menuItem) {
        if (!availabilityService.isFarmworldAvailable(menuItem.id())) {
            player.sendMessage(RESET_BLOCK_MESSAGE);
            return;
        }

        TeleportAction teleportAction = menuItem.teleportAction();
        if (!TYPE_COMMAND.equalsIgnoreCase(teleportAction.type())) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + menuItem.id()
                    + "' nutzt einen nicht unterstützten Teleport-Typ: " + teleportAction.type());
            return;
        }

        boolean scheduled = player.getScheduler().execute(plugin, () -> executeCommand(player, menuItem), null, 1L);
        if (!scheduled) {
            plugin.getLogger().warning("Teleport-Befehl für Farmwelt-Eintrag '" + menuItem.id()
                    + "' konnte nicht im Spieler-Kontext geplant werden.");
        }
    }

    private void executeCommand(Player player, FarmweltMenuItem menuItem) {
        player.closeInventory();

        // Recheck in the entity context so a reset starting after the GUI click still blocks entry.
        if (!availabilityService.isFarmworldAvailable(menuItem.id())) {
            player.sendMessage(RESET_BLOCK_MESSAGE);
            return;
        }

        TeleportAction teleportAction = menuItem.teleportAction();
        String command = normalizeCommand(replacePlaceholders(teleportAction.command(), player, menuItem));
        if (command.isBlank()) {
            player.sendMessage("Der Teleport-Befehl ist leer. Bitte melde dich beim Team.");
            plugin.getLogger().warning("Farmwelt-Eintrag '" + menuItem.id() + "' hat nach Platzhalter-Ersetzung keinen Befehl.");
            return;
        }

        player.sendMessage("Du wirst in die Farmwelt geschickt...");

        String sender = teleportAction.sender().toLowerCase(Locale.ROOT);
        try {
            boolean executed = switch (sender) {
                case SENDER_PLAYER -> player.performCommand(command);
                case SENDER_CONSOLE -> dispatchConsoleCommand(command);
                default -> {
                    plugin.getLogger().warning("Farmwelt-Eintrag '" + menuItem.id()
                            + "' nutzt einen nicht unterstützten Teleport-Absender: " + teleportAction.sender());
                    yield false;
                }
            };

            if (!executed) {
                player.sendMessage("Der Teleport-Befehl konnte nicht ausgeführt werden. Bitte melde dich beim Team.");
                plugin.getLogger().warning("Teleport-Befehl für Farmwelt-Eintrag '" + menuItem.id()
                        + "' konnte nicht ausgeführt werden: " + command);
            }
        } catch (RuntimeException exception) {
            player.sendMessage("Der Teleport-Befehl ist fehlgeschlagen. Bitte melde dich beim Team.");
            plugin.getLogger().log(Level.WARNING, "Fehler beim Ausführen des Teleport-Befehls für Farmwelt-Eintrag '"
                    + menuItem.id() + "'.", exception);
        }
    }

    private boolean dispatchConsoleCommand(String command) {
        Server server = plugin.getServer();
        return server.dispatchCommand(server.getConsoleSender(), command);
    }

    private String replacePlaceholders(String command, Player player, FarmweltMenuItem menuItem) {
        return command
                .replace("{player}", player.getName())
                .replace("{world}", menuItem.displayName())
                .replace("{id}", menuItem.id())
                .replace("{display-name}", menuItem.displayName());
    }

    private String normalizeCommand(String command) {
        String normalized = command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }
}
