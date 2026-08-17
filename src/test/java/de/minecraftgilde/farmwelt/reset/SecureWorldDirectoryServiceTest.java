package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureWorldDirectoryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsDirectLegacyWorldOnlyWhenFolderMatchesBukkitName() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Path worldDirectory = Files.createDirectories(serverDirectory.resolve("farmwelt"));
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(serverDirectory, Set.of());

        assertEquals(
                worldDirectory.toAbsolutePath().normalize(),
                service.validateWorldDirectory("farmwelt", worldDirectory)
        );
        assertReason(
                WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                () -> service.validateWorldDirectory("other_name", worldDirectory)
        );
    }

    @Test
    void acceptsNestedMinecraft2612WorldFoldersWithoutLevelDat() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Set<Path> protectedMainWorlds = createProtectedMainWorlds(serverDirectory);
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                serverDirectory,
                Set.of(),
                protectedMainWorlds
        );

        for (String farmworld : Set.of("farmwelt", "netherfarm", "endfarm")) {
            Path actualWorldDirectory = Files.createDirectories(
                    serverDirectory.resolve("world/dimensions/worlds").resolve(farmworld)
            );

            assertEquals(
                    actualWorldDirectory.toAbsolutePath().normalize(),
                    service.validateWorldDirectory("worlds_" + farmworld, actualWorldDirectory)
            );
            assertFalse(Files.exists(actualWorldDirectory.resolve("level.dat")));
        }
    }

    @Test
    void rejectsTraversalRootsAndServerInfrastructure() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Set<Path> protectedMainWorlds = createProtectedMainWorlds(serverDirectory);
        Path pluginsDirectory = Files.createDirectories(serverDirectory.resolve("plugins"));
        Path nestedPluginsDirectory = Files.createDirectories(
                pluginsDirectory.resolve("worlds/farmwelt")
        );
        Path externalPlugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                serverDirectory,
                Set.of(),
                protectedMainWorlds
        );

        for (Path unsafePath : List.of(
                serverDirectory,
                serverDirectory.resolve("world"),
                pluginsDirectory,
                nestedPluginsDirectory,
                serverDirectory.resolve("../plugins"),
                serverDirectory.resolve("../../"),
                externalPlugins,
                serverDirectory.getRoot()
        )) {
            assertThrows(
                    WorldDirectoryValidationException.class,
                    () -> service.validateWorldDirectory("worlds_farmwelt", unsafePath),
                    unsafePath.toString()
            );
        }

        for (String unsafeWorldName : Set.of(
                ".",
                "..",
                "../plugins",
                "../../",
                "sub/world",
                "sub\\world",
                "plugins"
        )) {
            assertThrows(
                    WorldDirectoryValidationException.class,
                    () -> service.validateWorldDirectory(unsafeWorldName, pluginsDirectory),
                    unsafeWorldName
            );
        }
    }

    @Test
    void rejectsVanillaMainDimensionPathsAndTheirParents() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Set<Path> protectedMainWorlds = createProtectedMainWorlds(serverDirectory);
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                serverDirectory,
                Set.of(),
                protectedMainWorlds
        );

        for (Path protectedPath : protectedMainWorlds) {
            assertReason(
                    WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                    () -> service.validateWorldDirectory("worlds_farmwelt", protectedPath)
            );
        }
        assertReason(
                WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                () -> service.validateWorldDirectory(
                        "worlds_farmwelt",
                        serverDirectory.resolve("world/dimensions/minecraft")
                )
        );
        assertReason(
                WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                () -> service.validateWorldDirectory("world", protectedMainWorlds.iterator().next())
        );
    }

    @Test
    void keepsAdditionalProtectedWorldNameCheck() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Path survivalDirectory = Files.createDirectories(serverDirectory.resolve("survival"));
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                serverDirectory,
                Set.of("survival")
        );

        assertReason(
                WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                () -> service.validateWorldDirectory("survival", survivalDirectory)
        );
    }

    @Test
    void deletesNestedDimensionDirectoryWithoutFollowingInternalSymbolicLinks() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Set<Path> protectedMainWorlds = createProtectedMainWorlds(serverDirectory);
        Path worldDirectory = Files.createDirectories(
                serverDirectory.resolve("world/dimensions/worlds/farmwelt")
        );
        Path region = Files.createDirectories(worldDirectory.resolve("region"));
        Files.writeString(region.resolve("r.0.0.mca"), "region");
        Path externalDirectory = Files.createDirectories(temporaryDirectory.resolve("external"));
        Path externalFile = Files.writeString(externalDirectory.resolve("keep.txt"), "important");
        try {
            Files.createSymbolicLink(worldDirectory.resolve("external-link"), externalDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolische Links werden in dieser Testumgebung nicht unterstützt.");
        }
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                serverDirectory,
                Set.of(),
                protectedMainWorlds
        );

        Path validatedDirectory = service.validateWorldDirectory("worlds_farmwelt", worldDirectory);
        service.deleteRecursively(validatedDirectory);

        assertFalse(Files.exists(worldDirectory));
        assertTrue(Files.exists(externalFile));
        assertEquals("important", Files.readString(externalFile));
    }

    @Test
    void rejectsSymbolicLinkAsWorldRoot() throws IOException {
        Path serverDirectory = Files.createDirectories(temporaryDirectory.resolve("server"));
        Set<Path> protectedMainWorlds = createProtectedMainWorlds(serverDirectory);
        Path externalWorld = Files.createDirectories(temporaryDirectory.resolve("external-world"));
        Path symbolicWorld = serverDirectory.resolve("world/dimensions/worlds/farmwelt");
        Files.createDirectories(symbolicWorld.getParent());
        try {
            Files.createSymbolicLink(symbolicWorld, externalWorld);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolische Links werden in dieser Testumgebung nicht unterstützt.");
        }
        SecureWorldDirectoryService service = new SecureWorldDirectoryService(
                serverDirectory,
                Set.of(),
                protectedMainWorlds
        );

        assertReason(
                WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                () -> service.validateWorldDirectory("worlds_farmwelt", symbolicWorld)
        );
    }

    private Set<Path> createProtectedMainWorlds(Path serverDirectory) throws IOException {
        Set<Path> protectedWorlds = new LinkedHashSet<>();
        for (String dimension : Set.of("overworld", "the_nether", "the_end")) {
            protectedWorlds.add(Files.createDirectories(
                    serverDirectory.resolve("world/dimensions/minecraft").resolve(dimension)
            ));
        }
        return Set.copyOf(protectedWorlds);
    }

    private void assertReason(
            WorldDirectoryValidationException.Reason expectedReason,
            ThrowingOperation operation
    ) {
        WorldDirectoryValidationException exception = assertThrows(
                WorldDirectoryValidationException.class,
                operation::run
        );
        assertEquals(expectedReason, exception.reason());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
