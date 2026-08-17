package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.World;

/** API-derived metadata for a configured, currently loaded Bukkit world. */
public record WorldInspection(
        Optional<World> loadedWorld,
        Optional<FarmworldType> loadedWorldType,
        boolean protectedMainWorld
) {

    public WorldInspection {
        loadedWorld = Objects.requireNonNull(loadedWorld, "loadedWorld");
        loadedWorldType = Objects.requireNonNull(loadedWorldType, "loadedWorldType");
        if (loadedWorld.isPresent() != loadedWorldType.isPresent()) {
            throw new IllegalArgumentException("Welt und Dimension müssen gemeinsam vorhanden sein.");
        }
        if (loadedWorld.isEmpty() && protectedMainWorld) {
            throw new IllegalArgumentException("Eine ungeladene Welt kann keine geschützte Hauptwelt sein.");
        }
    }

    public boolean loaded() {
        return loadedWorld.isPresent();
    }

    public static WorldInspection unloaded() {
        return new WorldInspection(Optional.empty(), Optional.empty(), false);
    }

    public static WorldInspection loaded(
            World world,
            FarmworldType worldType,
            boolean protectedMainWorld
    ) {
        return new WorldInspection(
                Optional.of(Objects.requireNonNull(world, "world")),
                Optional.of(Objects.requireNonNull(worldType, "worldType")),
                protectedMainWorld
        );
    }
}
