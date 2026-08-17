package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureWorldDirectoryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsDirectUnprotectedChildOfWorldContainer() {
        Path worldContainer = temporaryDirectory.resolve("worlds");
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(worldContainer, Set.of());

        assertEquals(
                worldContainer.resolve("farmwelt").toAbsolutePath().normalize(),
                service.resolveWorldDirectory("farmwelt")
        );
    }

    @Test
    void onlyRecognizesDirectoryWithRealLevelDatAsWorld() throws IOException {
        Path worldContainer = Files.createDirectories(temporaryDirectory.resolve("worlds"));
        Path worldDirectory = Files.createDirectories(worldContainer.resolve("farmwelt"));
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(worldContainer, Set.of());

        assertFalse(service.exists(worldDirectory));
        Files.writeString(worldDirectory.resolve("level.dat"), "level");
        assertTrue(service.exists(worldDirectory));
    }

    @Test
    void rejectsTraversalContainerAndProtectedWorldNames() {
        Path worldContainer = temporaryDirectory.resolve("worlds");
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                worldContainer,
                Set.of("survival")
        );

        for (String worldName : Set.of(
                ".",
                "..",
                "../plugins",
                "../../",
                "sub/world",
                "sub\\world",
                "world",
                "world_nether",
                "world_the_end",
                "survival"
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.resolveWorldDirectory(worldName),
                    worldName
            );
        }

        assertThrows(IllegalArgumentException.class, () -> service.resolveWorldDirectory(""));
        assertThrows(IllegalArgumentException.class, () -> service.exists(worldContainer));
        assertThrows(IllegalArgumentException.class, () -> service.exists(worldContainer.resolve("..")));
        assertThrows(IllegalArgumentException.class, () -> service.exists(temporaryDirectory.resolve("plugins")));
        assertThrows(IllegalArgumentException.class, () -> service.exists(temporaryDirectory.getRoot()));
    }

    @Test
    void deletesCompleteWorldDirectoryRecursively() throws IOException {
        Path worldContainer = Files.createDirectories(temporaryDirectory.resolve("worlds"));
        Path worldDirectory = Files.createDirectories(worldContainer.resolve("farmwelt"));
        Files.writeString(worldDirectory.resolve("level.dat"), "level");
        Path region = Files.createDirectories(worldDirectory.resolve("region"));
        Files.writeString(region.resolve("r.0.0.mca"), "region");
        Path data = Files.createDirectories(worldDirectory.resolve("data"));
        Files.writeString(data.resolve("example.dat"), "data");
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(worldContainer, Set.of());

        service.deleteRecursively(service.resolveWorldDirectory("farmwelt"));

        assertFalse(Files.exists(worldDirectory));
    }

    @Test
    void doesNotFollowSymbolicLinksDuringDeletion() throws IOException {
        Path worldContainer = Files.createDirectories(temporaryDirectory.resolve("worlds"));
        Path worldDirectory = Files.createDirectories(worldContainer.resolve("farmwelt"));
        Files.writeString(worldDirectory.resolve("level.dat"), "level");
        Path externalDirectory = Files.createDirectories(temporaryDirectory.resolve("external"));
        Path externalFile = Files.writeString(externalDirectory.resolve("keep.txt"), "important");
        try {
            Files.createSymbolicLink(worldDirectory.resolve("external-link"), externalDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolische Links werden in dieser Testumgebung nicht unterstützt.");
        }
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(worldContainer, Set.of());

        service.deleteRecursively(service.resolveWorldDirectory("farmwelt"));

        assertFalse(Files.exists(worldDirectory));
        assertTrue(Files.exists(externalFile));
        assertEquals("important", Files.readString(externalFile));
    }
}
