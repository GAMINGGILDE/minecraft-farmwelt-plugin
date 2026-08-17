package de.minecraftgilde.farmwelt.reset;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record WorldInspection(
        boolean loaded,
        Optional<Path> loadedWorldDirectory,
        Optional<FarmworldType> loadedWorldType
) {

    public WorldInspection {
        loadedWorldDirectory = Objects.requireNonNull(loadedWorldDirectory, "loadedWorldDirectory");
        loadedWorldType = Objects.requireNonNull(loadedWorldType, "loadedWorldType");
        if (loaded && (loadedWorldDirectory.isEmpty() || loadedWorldType.isEmpty())) {
            throw new IllegalArgumentException("Eine geladene Welt benötigt Pfad und Dimension.");
        }
        if (!loaded && (loadedWorldDirectory.isPresent() || loadedWorldType.isPresent())) {
            throw new IllegalArgumentException("Eine ungeladene Welt darf keine geladenen Metadaten enthalten.");
        }
    }

    public static WorldInspection unloaded() {
        return new WorldInspection(false, Optional.empty(), Optional.empty());
    }

    public static WorldInspection loaded(Path worldDirectory, FarmworldType worldType) {
        return new WorldInspection(true, Optional.of(worldDirectory), Optional.of(worldType));
    }
}
