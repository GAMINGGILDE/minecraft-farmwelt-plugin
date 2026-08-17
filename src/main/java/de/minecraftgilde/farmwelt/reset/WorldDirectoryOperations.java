package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.Path;

/** Filesystem boundary. The engine invokes validation and deletion on the async scheduler. */
public interface WorldDirectoryOperations {

    /**
     * Validates the directory reported by Bukkit for the loaded world. Implementations must not
     * derive a storage path from {@code worldName}.
     */
    Path validateWorldDirectory(String worldName, Path actualWorldDirectory);

    void deleteRecursively(Path worldDirectory) throws IOException;
}
