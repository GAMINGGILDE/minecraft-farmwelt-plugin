package de.minecraftgilde.farmwelt.reset;

import static de.minecraftgilde.farmwelt.reset.DragonDataFiles.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Safely writes Minecraft-internal DragonFight saved data while Worlds replaces an End world.
 *
 * <p>This file preparation is deliberately retained alongside the runtime {@code DragonBattle}
 * API and the {@code CreatureSpawnEvent} guard. For Folia/Minecraft 26.1.2 it installs either a
 * suppressed battle or a fresh initial fight before Bukkit can expose the newly loaded world.
 * The runtime compatibility layer subsequently completes and verifies the loaded battle state,
 * while the event guard enforces the effective spawn policy.</p>
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
    // Zwischen Weltprüfung und Namenswechsel darf kein anderer Welthandoff liegen.
    // Größere Rücknahmen bleiben zur Offline-Prüfung, damit dieser Abschnitt kurz bleibt.
    private static final int MAX_VETO_RESTORE_BYTES = 4096;
    private static final Path FIGHT_DATA_RELATIVE_PATH =
            Path.of("data", "minecraft", "ender_dragon_fight.dat");

    private final int dataVersion;
    private final DragonDataFiles files;

    EndDragonFightDataStore(int dataVersion) {
        this(dataVersion, new DragonDataFiles());
    }

    EndDragonFightDataStore(int dataVersion, DragonDataFiles files) {
        if (dataVersion < 0) throw new IllegalArgumentException("dataVersion darf nicht negativ sein.");
        this.dataVersion = dataVersion;
        this.files = Objects.requireNonNull(files);
    }

    StagedData stage(Path world, FarmworldRegenerationOptions.EndDragonFightDataMode mode, UUID id) throws IOException {
        if (mode == FarmworldRegenerationOptions.EndDragonFightDataMode.PRESERVE) {
            throw new IllegalArgumentException("PRESERVE darf keine Fight-Daten ersetzen.");
        }
        for (Path part : world) {
            if (part.toString().equals("..")) throw new IOException("Mehrdeutiger Weltordner: " + world);
        }
        Path folder = world.toAbsolutePath().normalize();
        directory(folder);
        Path active = folder.resolve(FIGHT_DATA_RELATIVE_PATH);
        for (Path part : folder.relativize(active.getParent())) {
            Path parent = folder;
            folder = parent.resolve(part);
            if (!present(folder)) {
                Files.createDirectory(folder);
                DurableResetFiles.syncDirectory(parent);
            }
            directory(folder);
        }
        try (var entries = Files.newDirectoryStream(folder, active.getFileName() + ".farmwelt-*")) {
            var iterator = entries.iterator();
            if (iterator.hasNext()) {
                throw new IOException("Offene DragonBattle-Dateien; administrative Prüfung erforderlich: " + iterator.next());
            }
        }
        Objects.requireNonNull(id);
        Fingerprint original = present(active) ? files.fingerprint(active) : null;
        if (original != null && active.getFileSystem().supportedFileAttributeViews().contains("unix")
                && ((Number) Files.getAttribute(active, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue() != 1) {
            throw new IOException("DragonBattle-Original besitzt weitere Hardlinks: " + active);
        }
        var data = new StagedData(active, id, original);
        if (active.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
            Files.createDirectory(data.work, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions));
        } else {
            Files.createDirectory(data.work);
        }
        try {
            data.directoryId = Identity.of(attributes(data.work));
            DurableResetFiles.syncDirectory(folder);
            byte[] bytes = preparedBytes(Objects.requireNonNull(mode));
            files.createPrepared(data.temp, bytes);
            data.prepared = files.fingerprint(data.temp);
            if (!data.prepared.sha256().equals(HexFormat.of().formatHex(digest().digest(bytes)))) {
                throw new IOException("Vorbereitete Daten weichen vom erzeugten NBT ab: " + data.temp);
            }
            if (data.hadFightData()) {
                files.link(data.backup, active);
                data.verifyPair(data.backup, active, original);
                DurableResetFiles.syncDirectory(data.work);
                files.moveAside(active, data.saved);
                data.verifyPair(data.backup, data.saved, original);
            }
            DurableResetFiles.syncDirectory(data.work);
            files.link(active, data.temp);
            data.verifyPair(active, data.temp, data.prepared);
            DurableResetFiles.syncDirectory(folder);
            return data;
        } catch (IOException | RuntimeException failure) {
            // Ein fehlgeschlagener Systemaufruf beweist weder Erfolg noch Wirkungslosigkeit.
            try {
                data.checkDirectory();
                if (data.hadFightData() && !present(active) && present(data.saved)) {
                    data.verifyPair(data.backup, data.saved, original);
                    files.link(active, data.saved);
                    DurableResetFiles.syncDirectory(folder);
                }
            } catch (IOException | RuntimeException recovery) {
                failure.addSuppressed(recovery);
            }
            failure.addSuppressed(new IOException(data.diagnosis()));
            throw failure;
        }
    }

    private byte[] preparedBytes(FarmworldRegenerationOptions.EndDragonFightDataMode mode) throws IOException {
        boolean initialFight = mode
                == FarmworldRegenerationOptions.EndDragonFightDataMode.INITIAL_FIGHT;
        var output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output);
                DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeByte(TAG_COMPOUND);
            data.writeUTF("");

            data.writeByte(TAG_INT);
            data.writeUTF("DataVersion");
            data.writeInt(dataVersion);

            data.writeByte(TAG_COMPOUND);
            data.writeUTF("data");
            writeBoolean(data, "needs_state_scanning", initialFight);
            writeBoolean(data, "dragon_killed", !initialFight);
            writeBoolean(data, "previously_killed", !initialFight);
            data.writeByte(TAG_END);

            data.writeByte(TAG_END);
        }
        return output.toByteArray();
    }

    private void writeBoolean(DataOutputStream data, String name, boolean value)
            throws IOException {
        data.writeByte(TAG_BYTE);
        data.writeUTF(name);
        data.writeByte(value ? 1 : 0);
    }

    void prepareRollback(StagedData data) throws IOException {
        synchronized (data) {
            data.claimRollback();
            data.checkDirectory();
            if (data.hadFightData() && data.original.stamp().size() > MAX_VETO_RESTORE_BYTES) {
                throw new IOException("Original für die synchrone Abschlussprüfung zu groß: " + data.backup);
            }
            if (data.hadFightData()) data.verifyOriginal();
            data.verifyPair(data.active, data.temp, data.prepared);
            data.finished = Finish.VERIFIED;
        }
    }

    // Identitätsprüfung und Namenswechsel gehören in dieselbe Global-Aufgabe wie die Weltprüfung.
    void restoreVerified(StagedData data) throws IOException {
        synchronized (data) {
            if (data.finished != Finish.VERIFIED) throw new IOException("Rollback ohne gültige Dateiprüfung.");
            data.finished = Finish.CLAIMED;
            if (data.hadFightData()) data.verifyOriginal();
            data.checkPair(data.active, data.temp, data.prepared);
            // Der verschobene Eintrag bleibt bis zum erfolgreichen Restore als Beweis erhalten.
            try {
                files.moveAside(data.active, data.withdrawn);
                data.verifyPair(data.withdrawn, data.temp, data.prepared);
                if (data.hadFightData()) {
                    data.verifyOriginal();
                    files.link(data.active, data.backup);
                    data.checkPair(data.active, data.saved, data.original);
                }
            } catch (IOException | RuntimeException failure) {
                try {
                    data.checkDirectory();
                    if (!present(data.active) && present(data.withdrawn)) {
                        // Auch ein während des Rename ausgetauschter Eintrag bleibt erhalten.
                        if (attributes(data.withdrawn).isRegularFile()) files.link(data.active, data.withdrawn);
                    }
                } catch (IOException | RuntimeException recovery) {
                    failure.addSuppressed(recovery);
                }
                throw failure;
            }
            data.finished = Finish.RESTORED;
        }
    }

    void finishRollback(StagedData data) throws IOException {
        synchronized (data) {
            if (data.finished == Finish.ROLLBACK) return;
            if (data.finished != Finish.RESTORED) throw new IOException("Dateiwiederherstellung ungeklärt.");
            DurableResetFiles.syncDirectory(data.active.getParent());
            data.finish(Finish.ROLLBACK);
        }
    }

    void commit(StagedData data, Path confirmedWorldFolder) throws IOException {
        synchronized (data) {
            if (data.finished == Finish.COMMIT) return;
            if (data.finished != Finish.OPEN) throw new IOException("Dateiversuch bereits beansprucht: " + data.work);
            data.finished = Finish.CLAIMED;
            if (!data.active.equals(confirmedWorldFolder.toAbsolutePath().normalize().resolve(FIGHT_DATA_RELATIVE_PATH))) {
                throw new IOException("Bestätigte neue Welt liegt in einem anderen Ordner. " + data.diagnosis());
            }
            data.verifyPair(data.active, data.temp, data.prepared);
            data.finish(Finish.COMMIT);
        }
    }

    private enum Finish { OPEN, CLAIMED, VERIFIED, RESTORED, ROLLBACK, COMMIT }

    final class StagedData {
        private final Path active, work, backup, saved, temp, withdrawn;
        private final Fingerprint original;
        private Fingerprint prepared;
        private Identity directoryId;
        private Finish finished = Finish.OPEN;

        private StagedData(Path active, UUID id, Fingerprint original) {
            this.active = active;
            this.original = original;
            work = active.resolveSibling(active.getFileName() + ".farmwelt-stage");
            backup = work.resolve(id + ".bak");
            saved = work.resolve(id + ".original");
            temp = work.resolve(id + ".tmp");
            withdrawn = work.resolve(id + ".withdrawn");
        }

        boolean hadFightData() { return original != null; }
        Optional<Path> backup() { return hadFightData() ? Optional.of(backup) : Optional.empty(); }

        private void claimRollback() throws IOException {
            if (finished != Finish.OPEN) throw new IOException("Dateiversuch bereits beansprucht: " + work);
            finished = Finish.CLAIMED;
        }

        private void checkDirectory() throws IOException {
            directory(work);
            if (!Identity.of(attributes(work)).equals(directoryId)) throw new IOException("Arbeitsordner ersetzt: " + work);
        }

        private void verify(Path path, Fingerprint expected) throws IOException {
            checkDirectory();
            if (!present(path)) throw new IOException("DragonBattle-Datei fehlt: " + path);
            if (!files.fingerprint(path, expected.stamp().size()).equals(expected)) {
                throw new IOException("DragonBattle-Datei verändert: " + path);
            }
        }

        private void verifyPair(Path candidate, Path witness, Fingerprint expected) throws IOException {
            verify(candidate, expected);
            checkPair(candidate, witness, expected);
        }

        private void checkPair(Path candidate, Path witness, Fingerprint expected) throws IOException {
            checkDirectory();
            var current = attributes(candidate);
            if (!current.isRegularFile() || !Stamp.of(current).equals(expected.stamp())) {
                throw new IOException("DragonBattle-Datei verändert: " + candidate);
            }
            if (!attributes(witness).isRegularFile() || !Files.isSameFile(candidate, witness)) {
                throw new IOException("DragonBattle-Datei gehört nicht zum Versuch: " + candidate + " / " + witness);
            }
        }

        private void verifyOriginal() throws IOException {
            verifyPair(backup, saved, original);
        }

        private void finish(Finish operation) throws IOException {
            finished = Finish.CLAIMED;
            try {
                checkDirectory();
                if (hadFightData()) verifyOriginal();
                verify(temp, prepared);
                if (operation == Finish.ROLLBACK) {
                    verifyPair(withdrawn, temp, prepared);
                    files.delete(withdrawn);
                }
                files.delete(temp);
                if (hadFightData()) {
                    verifyOriginal();
                    files.delete(saved);
                    verify(backup, original);
                    files.delete(backup);
                }
                files.delete(work);
                DurableResetFiles.syncDirectory(active.getParent());
                finished = operation;
            } catch (IOException | RuntimeException failure) {
                throw new IOException("Datei-" + operation + " bestätigt, Cleanup unvollständig. " + diagnosis(), failure);
            }
        }

        String diagnosis() {
            return "Aktiv=" + describe(active) + ", Original bei Beginn=" + hadFightData()
                    + ", Sicherung=" + describe(backup) + ", Originalreferenz=" + describe(saved)
                    + ", vorbereitet=" + describe(temp) + ", zurückgestellt=" + describe(withdrawn)
                    + ". Keine automatische Wiederholung. Server stoppen und Sicherungssatz prüfen.";
        }

        private String describe(Path path) {
            return path + (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? " (vorhanden)"
                    : Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? " (fehlt)" : " (unbekannt)");
        }
    }
}
