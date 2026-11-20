package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class DurableResetFiles {
    private DurableResetFiles() {
    }

    static void syncDirectory(Path directory) throws IOException {
        // Der Windows-NIO-Provider unterstützt keine Verzeichnis-Handles für FileChannel.force.
        if (FileSystems.getDefault().getSeparator().equals("\\")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
