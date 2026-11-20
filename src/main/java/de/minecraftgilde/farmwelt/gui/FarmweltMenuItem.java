package de.minecraftgilde.farmwelt.gui;

import de.minecraftgilde.farmwelt.reset.FarmworldType;
import java.util.List;
import org.bukkit.Material;

public record FarmweltMenuItem(
        String id,
        String displayName,
        Material icon,
        int slot,
        List<String> lore,
        TeleportAction teleportAction
) {
    public FarmweltMenuItem {
        id = FarmworldType.canonicalKey(id);
    }
}
