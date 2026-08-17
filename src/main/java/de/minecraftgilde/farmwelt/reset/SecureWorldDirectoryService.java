package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
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

    private final Path worldContainer;
    private final Supplier<Set<String>> additionalProtectedWorlds;

    public SecureWorldDirectoryService(Path worldContainer, Set<String> additionalProtectedWorlds) {
        this(worldContainer, () -> additionalProtectedWorlds);
    }

    public SecureWorldDirectoryService(
            Path worldContainer,
            Supplier<Set<String>> additionalProtectedWorlds
    ) {
        this.worldContainer = Objects.requireNonNull(worldContainer, "worldContainer")
                .toAbsolutePath()
                .normalize();
        this.additionalProtectedWorlds = Objects.requireNonNull(
                additionalProtectedWorlds,
                "additionalProtectedWorlds"
        );
    }

    @Override
    public Path resolveWorldDirectory(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Der Weltname darf nicht leer sein.");
        }
        if (!worldName.equals(worldName.trim()) || ".".equals(worldName) || "..".equals(worldName)) {
            throw new IllegalArgumentException("Der Weltname ist kein sicherer Verzeichnisname.");
        }
        if (worldName.indexOf('/') >= 0 || worldName.indexOf('\\') >= 0 || isProtected(worldName)) {
            throw new IllegalArgumentException("Die Welt '" + worldName + "' ist vor Resets geschützt.");
        }

        final Path relativeWorldPath;
        try {
            relativeWorldPath = Path.of(worldName);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Der Weltname ist kein gültiger Pfad.", exception);
        }

        if (relativeWorldPath.isAbsolute()
                || relativeWorldPath.getNameCount() != 1
                || !relativeWorldPath.getFileName().toString().equals(worldName)) {
            throw new IllegalArgumentException("Der Weltname muss aus genau einem Verzeichnisnamen bestehen.");
        }

        Path worldDirectory = worldContainer.resolve(relativeWorldPath).normalize();
        validateResolvedPath(worldDirectory);
        return worldDirectory;
    }

    @Override
    public boolean exists(Path worldDirectory) {
        validateResolvedPath(worldDirectory);
        return Files.isDirectory(worldDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(
                        worldDirectory.resolve("level.dat"),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS
                );
    }

    @Override
    public void deleteRecursively(Path worldDirectory) throws IOException {
        validateResolvedPath(worldDirectory);
        if (Files.isSymbolicLink(worldDirectory)) {
            throw new IOException("Der Weltordner selbst darf kein symbolischer Link sein.");
        }
        if (!Files.exists(worldDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Der Weltordner existiert nicht: " + worldDirectory);
        }
        if (!Files.isRegularFile(
                worldDirectory.resolve("level.dat"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS
        )) {
            throw new IOException("Der Zielordner ist kein eindeutig identifizierbarer Minecraft-Weltordner.");
        }

        Files.walkFileTree(worldDirectory, new SimpleFileVisitor<>() {
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

    private void validateResolvedPath(Path worldDirectory) {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        Path normalizedDirectory = worldDirectory.toAbsolutePath().normalize();
        if (normalizedDirectory.equals(worldContainer) || !normalizedDirectory.startsWith(worldContainer)) {
            throw new IllegalArgumentException("Der Weltordner liegt außerhalb des Welt-Containers.");
        }
        Path relative = worldContainer.relativize(normalizedDirectory);
        if (relative.getNameCount() != 1 || ".".equals(relative.toString()) || "..".equals(relative.toString())) {
            throw new IllegalArgumentException("Der Weltordner ist kein direktes Kind des Welt-Containers.");
        }
        if (isProtected(relative.getFileName().toString())) {
            throw new IllegalArgumentException("Der Weltordner ist vor Resets geschützt.");
        }
    }
}
