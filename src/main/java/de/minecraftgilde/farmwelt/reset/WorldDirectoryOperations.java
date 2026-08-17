package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.Path;

/** Filesystem boundary. The engine invokes existence checks and deletion on the async scheduler. */
public interface WorldDirectoryOperations {

    Path resolveWorldDirectory(String worldName);

    /** Returns whether the path is a real, non-symlink Minecraft world directory. */
    boolean exists(Path worldDirectory);

    void deleteRecursively(Path worldDirectory) throws IOException;
}
