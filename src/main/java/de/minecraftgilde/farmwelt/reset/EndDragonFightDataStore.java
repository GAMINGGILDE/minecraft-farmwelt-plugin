package de.minecraftgilde.farmwelt.reset;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Safely writes Minecraft-internal DragonFight saved data while Worlds replaces an End world.
 *
 * <p>This file preparation is deliberately retained alongside the runtime {@code DragonBattle}
 * API and the {@code CreatureSpawnEvent} guard. For Folia/Minecraft 26.1.2 it ensures that a
 * regenerated End starts without the initial DragonBattle state and its unwanted boss bar before
 * Bukkit can expose the newly loaded world. The runtime API subsequently verifies the loaded
 * battle state, while the event guard prevents a delayed dragon spawn.</p>
 *
 * <p>The NBT layout and the tags written by this class are Minecraft-version-sensitive internal
 * details. Minecraft version, DataVersion, and DragonFight saved-data changes must be reviewed
 * before enabling this writer for any server upgrade. Compatibility is therefore checked by
 * {@link EndDragonFightCompatibility} before the lifecycle service invokes this store.</p>
 */
final class EndDragonFightDataStore {

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_INT = 3;
    private static final byte TAG_COMPOUND = 10;
    private static final Path FIGHT_DATA_RELATIVE_PATH =
            Path.of("data", "minecraft", "ender_dragon_fight.dat");

    private final int dataVersion;

    EndDragonFightDataStore(int dataVersion) {
        if (dataVersion < 0) {
            throw new IllegalArgumentException("dataVersion darf nicht negativ sein.");
        }
        this.dataVersion = dataVersion;
    }

    StagedData stage(Path worldFolder) throws IOException {
        Objects.requireNonNull(worldFolder, "worldFolder");
        Path normalizedWorldFolder = worldFolder.toAbsolutePath().normalize();
        Path fightData = normalizedWorldFolder.resolve(FIGHT_DATA_RELATIVE_PATH).normalize();
        if (!fightData.startsWith(normalizedWorldFolder)) {
            throw new IOException("DragonBattle-Datei liegt außerhalb des Weltordners.");
        }

        validateWorldFolder(normalizedWorldFolder);
        prepareFightDataParent(normalizedWorldFolder, fightData.getParent());
        validateExistingFightData(fightData);
        Optional<Path> backup = backupExistingFightData(fightData);
        Path temporary = fightData.resolveSibling(
                fightData.getFileName() + ".farmwelt-new-" + UUID.randomUUID() + ".tmp"
        );

        try {
            writePreparedFightData(temporary);
            move(temporary, fightData);
            return new StagedData(fightData, backup, sha256(fightData));
        } catch (IOException | RuntimeException exception) {
            rollbackFailedStage(fightData, backup, temporary, exception);
            throw exception;
        }
    }

    void commit(StagedData stagedData) throws IOException {
        Objects.requireNonNull(stagedData, "stagedData");
        if (stagedData.backup().isPresent()) {
            Files.deleteIfExists(stagedData.backup().orElseThrow());
        }
    }

    void rollback(StagedData stagedData) throws IOException {
        Objects.requireNonNull(stagedData, "stagedData");
        Path fightData = stagedData.fightData();
        if (Files.exists(fightData, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(fightData, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(fightData).equals(stagedData.preparedSha256())) {
                throw new IOException(
                        "Vorbereitete DragonBattle-Datei wurde zwischenzeitlich verändert: "
                                + fightData
                );
            }
            Files.delete(fightData);
        }

        if (stagedData.backup().isPresent()) {
            Path backup = stagedData.backup().orElseThrow();
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Sicherung der DragonBattle-Datei fehlt: " + backup);
            }
            if (Files.isSymbolicLink(backup)
                    || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Sicherung der DragonBattle-Datei ist keine regul\u00e4re Datei: " + backup
                );
            }
            move(backup, fightData);
        }
    }

    private void validateWorldFolder(Path worldFolder) throws IOException {
        if (Files.isSymbolicLink(worldFolder)
                || !Files.isDirectory(worldFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Weltordner muss ein regul\u00e4res Verzeichnis ohne symbolischen Link sein: "
                            + worldFolder
            );
        }
    }

    private void prepareFightDataParent(Path worldFolder, Path fightDataParent)
            throws IOException {
        Path current = worldFolder;
        for (Path segment : worldFolder.relativize(fightDataParent)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
                continue;
            }
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "DragonBattle-Verzeichnispfad darf keine symbolischen Links oder "
                                + "Nicht-Verzeichnisse enthalten: " + current
                );
            }
        }
    }

    private void validateExistingFightData(Path fightData) throws IOException {
        if (!Files.exists(fightData, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(fightData)) {
            throw new IOException("DragonBattle-Datei darf kein symbolischer Link sein: " + fightData);
        }
        if (!Files.isRegularFile(fightData, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("DragonBattle-Pfad ist keine reguläre Datei: " + fightData);
        }
    }

    private Optional<Path> backupExistingFightData(Path fightData) throws IOException {
        if (!Files.exists(fightData, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path backup = fightData.resolveSibling(
                fightData.getFileName() + ".farmwelt-reset-" + UUID.randomUUID() + ".bak"
        );
        move(fightData, backup);
        return Optional.of(backup);
    }

    private void writePreparedFightData(Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        ); GZIPOutputStream gzip = new GZIPOutputStream(output);
                DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeByte(TAG_COMPOUND);
            data.writeUTF("");

            data.writeByte(TAG_INT);
            data.writeUTF("DataVersion");
            data.writeInt(dataVersion);

            data.writeByte(TAG_COMPOUND);
            data.writeUTF("data");
            writeBoolean(data, "needs_state_scanning", false);
            writeBoolean(data, "dragon_killed", true);
            writeBoolean(data, "previously_killed", true);
            data.writeByte(TAG_END);

            data.writeByte(TAG_END);
        }
    }

    private void writeBoolean(DataOutputStream data, String name, boolean value)
            throws IOException {
        data.writeByte(TAG_BYTE);
        data.writeUTF(name);
        data.writeByte(value ? 1 : 0);
    }

    private void rollbackFailedStage(
            Path fightData,
            Optional<Path> backup,
            Path temporary,
            Throwable failure
    ) {
        try {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(fightData);
            if (backup.isPresent()) {
                move(backup.orElseThrow(), fightData);
            }
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 ist in dieser Java-Laufzeit nicht verfügbar.",
                    exception
            );
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    record StagedData(
            Path fightData,
            Optional<Path> backup,
            String preparedSha256
    ) {

        StagedData {
            Objects.requireNonNull(fightData, "fightData");
            Objects.requireNonNull(backup, "backup");
            Objects.requireNonNull(preparedSha256, "preparedSha256");
        }

        boolean hadFightData() {
            return backup.isPresent();
        }
    }
}
