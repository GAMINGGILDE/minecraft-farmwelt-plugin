package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class SecureWorldDirectoryService implements WorldDirectoryOperations {

    private static final Set<String> STANDARD_PROTECTED_WORLDS = Set.of(
            "world",
            "world_nether",
            "world_the_end"
    );
    private static final Set<String> PROTECTED_SERVER_DIRECTORIES = Set.of(
            "plugins",
            "config",
            "logs",
            "libraries",
            "versions"
    );

    private final Path worldContainer;
    private final Supplier<Set<String>> additionalProtectedWorlds;
    private final Set<Path> protectedWorldDirectories;
    private final Set<Path> allowedStorageRoots;

    public SecureWorldDirectoryService(Path worldContainer, Set<String> additionalProtectedWorlds) {
        this(worldContainer, () -> additionalProtectedWorlds, Set.of());
    }

    public SecureWorldDirectoryService(
            Path worldContainer,
            Set<String> additionalProtectedWorlds,
            Set<Path> protectedWorldDirectories
    ) {
        this(worldContainer, () -> additionalProtectedWorlds, protectedWorldDirectories);
    }

    public SecureWorldDirectoryService(
            Path worldContainer,
            Supplier<Set<String>> additionalProtectedWorlds
    ) {
        this(worldContainer, additionalProtectedWorlds, Set.of());
    }

    public SecureWorldDirectoryService(
            Path worldContainer,
            Supplier<Set<String>> additionalProtectedWorlds,
            Set<Path> protectedWorldDirectories
    ) {
        this.worldContainer = Objects.requireNonNull(worldContainer, "worldContainer")
                .toAbsolutePath()
                .normalize();
        this.additionalProtectedWorlds = Objects.requireNonNull(
                additionalProtectedWorlds,
                "additionalProtectedWorlds"
        );
        this.protectedWorldDirectories = normalizePaths(protectedWorldDirectories);
        this.allowedStorageRoots = deriveStorageRoots(this.protectedWorldDirectories);
    }

    @Override
    public Path validateWorldDirectory(String worldName, Path actualWorldDirectory) {
        validateWorldName(worldName);
        Path normalizedDirectory = normalize(actualWorldDirectory);
        validateStructuralPath(normalizedDirectory, worldName);
        validateExistingDirectory(normalizedDirectory);
        return normalizedDirectory;
    }

    @Override
    public void deleteRecursively(Path worldDirectory) throws IOException {
        Path normalizedDirectory = normalize(worldDirectory);
        validateStructuralPath(normalizedDirectory, null);
        validateExistingDirectory(normalizedDirectory);

        Files.walkFileTree(normalizedDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.INVALID_WORLD_NAME,
                    "Der Weltname darf nicht leer sein."
            );
        }
        if (!worldName.equals(worldName.trim())
                || ".".equals(worldName)
                || "..".equals(worldName)
                || worldName.indexOf('/') >= 0
                || worldName.indexOf('\\') >= 0) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.INVALID_WORLD_NAME,
                    "Der Weltname ist kein sicherer Bukkit-Weltname."
            );
        }
        if (isProtected(worldName)) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                    "Die Welt '" + worldName + "' ist vor Resets geschützt."
            );
        }
    }

    private void validateStructuralPath(Path worldDirectory, String worldName) {
        if (worldDirectory.equals(worldContainer) || !worldDirectory.startsWith(worldContainer)) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                    "Der von Bukkit gemeldete Weltordner liegt außerhalb des Welt-Containers."
            );
        }

        for (Path protectedDirectory : protectedWorldDirectories) {
            if (worldDirectory.startsWith(protectedDirectory)
                    || protectedDirectory.startsWith(worldDirectory)) {
                throw validationFailure(
                        WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                        "Der Weltordner überschneidet sich mit einer geschützten Hauptwelt."
                );
            }
        }

        Path relativeDirectory = worldContainer.relativize(worldDirectory);
        if (relativeDirectory.getNameCount() == 1) {
            String directoryName = relativeDirectory.getFileName().toString();
            if (PROTECTED_SERVER_DIRECTORIES.contains(directoryName.toLowerCase(Locale.ROOT))) {
                throw validationFailure(
                        WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                        "Der Weltordner verweist auf ein geschütztes Serververzeichnis."
                );
            }
            if (worldName != null && isProtected(directoryName)) {
                throw validationFailure(
                        WorldDirectoryValidationException.Reason.PROTECTED_WORLD,
                        "Der Weltordner ist vor Resets geschützt."
                );
            }
            if (worldName != null && !directoryName.equals(worldName)) {
                throw validationFailure(
                        WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                        "Ein direkter Legacy-Weltordner muss dem Bukkit-Weltnamen entsprechen."
                );
            }
            return;
        }

        boolean insideKnownStorageRoot = allowedStorageRoots.stream()
                .anyMatch(storageRoot -> !worldDirectory.equals(storageRoot)
                        && worldDirectory.startsWith(storageRoot));
        if (!insideKnownStorageRoot) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                    "Der verschachtelte Weltordner liegt in keinem API-basiert ermittelten Storage-Bereich."
            );
        }
    }

    private void validateExistingDirectory(Path worldDirectory) {
        if (Files.isSymbolicLink(worldDirectory)) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                    "Der Weltordner selbst darf kein symbolischer Link sein."
            );
        }
        if (!Files.isDirectory(worldDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw validationFailure(
                    WorldDirectoryValidationException.Reason.WORLD_NOT_FOUND,
                    "Der von Bukkit gemeldete Weltordner existiert nicht."
            );
        }

        try {
            Path realContainer = worldContainer.toRealPath();
            Path realDirectory = worldDirectory.toRealPath();
            if (realDirectory.equals(realContainer) || !realDirectory.startsWith(realContainer)) {
                throw validationFailure(
                        WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                        "Der reale Weltordner liegt außerhalb des Welt-Containers."
                );
            }
        } catch (IOException exception) {
            throw new WorldDirectoryValidationException(
                    WorldDirectoryValidationException.Reason.UNSAFE_PATH,
                    "Der reale Weltordner konnte nicht sicher aufgelöst werden.",
                    exception
            );
        }
    }

    private boolean isProtected(String worldName) {
        String normalizedWorldName = worldName.toLowerCase(Locale.ROOT);
        if (STANDARD_PROTECTED_WORLDS.contains(normalizedWorldName)) {
            return true;
        }

        Set<String> configuredProtectedWorlds = additionalProtectedWorlds.get();
        if (configuredProtectedWorlds == null) {
            return false;
        }

        Set<String> normalizedProtectedWorlds = new HashSet<>();
        for (String protectedWorld : configuredProtectedWorlds) {
            if (protectedWorld != null) {
                normalizedProtectedWorlds.add(protectedWorld.toLowerCase(Locale.ROOT));
            }
        }
        return normalizedProtectedWorlds.contains(normalizedWorldName);
    }

    private Set<Path> normalizePaths(Set<Path> paths) {
        Objects.requireNonNull(paths, "protectedWorldDirectories");
        Set<Path> normalizedPaths = new LinkedHashSet<>();
        for (Path path : paths) {
            if (path != null) {
                normalizedPaths.add(path.toAbsolutePath().normalize());
            }
        }
        return Set.copyOf(normalizedPaths);
    }

    private Set<Path> deriveStorageRoots(Set<Path> protectedDirectories) {
        Set<Path> storageRoots = new LinkedHashSet<>();
        for (Path protectedDirectory : protectedDirectories) {
            if (protectedDirectory.equals(worldContainer)
                    || !protectedDirectory.startsWith(worldContainer)) {
                continue;
            }
            Path relativeDirectory = worldContainer.relativize(protectedDirectory);
            if (relativeDirectory.getNameCount() > 0) {
                storageRoots.add(worldContainer.resolve(relativeDirectory.getName(0)).normalize());
            }
        }
        return Set.copyOf(storageRoots);
    }

    private Path normalize(Path path) {
        return Objects.requireNonNull(path, "worldDirectory").toAbsolutePath().normalize();
    }

    private WorldDirectoryValidationException validationFailure(
            WorldDirectoryValidationException.Reason reason,
            String message
    ) {
        return new WorldDirectoryValidationException(reason, message);
    }
}
