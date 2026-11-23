package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class EndDragonFightDataStoreTest {

    private static final Path FIGHT_DATA =
            Path.of("data", "minecraft", "ender_dragon_fight.dat");
    private static final FarmworldRegenerationOptions.EndDragonFightDataMode SUPPRESSED =
            FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED;

    @Test
    void stageBacksUpExistingDataAndCommitRemovesOnlyBackup(@TempDir Path worldFolder)
            throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);

        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED, UUID.randomUUID());

        assertTrue(staged.hadFightData());
        Path backup = staged.backup().orElseThrow();
        assertTrue(Files.isRegularFile(backup));
        assertEquals("original", Files.readString(backup));
        assertNotEquals((byte) 'o', Files.readAllBytes(fightData)[0]);

        store.commit(staged, worldFolder);

        assertFalse(Files.exists(backup));
        assertTrue(Files.isRegularFile(fightData));
    }

    @Test
    void rollbackRestoresBackedUpFightData(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);
        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED, UUID.randomUUID());

        rollback(store, staged);

        assertEquals("original", Files.readString(fightData));
        assertFalse(Files.exists(staged.backup().orElseThrow()));
    }

    @Test
    void rollbackRejectsManipulatedPreparedFile(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);
        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED, UUID.randomUUID());
        Files.writeString(fightData, "manipulated");

        IOException exception = assertThrows(IOException.class, () -> rollback(store, staged));

        assertTrue(exception.getMessage().contains("ver\u00e4ndert"));
        assertEquals("manipulated", Files.readString(fightData));
        assertTrue(Files.exists(staged.backup().orElseThrow()));
    }

    @Test
    void rollbackFailsClosedWhenBackupIsMissing(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);
        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED, UUID.randomUUID());
        Files.delete(staged.backup().orElseThrow());
        byte[] prepared = Files.readAllBytes(fightData);

        IOException exception = assertThrows(IOException.class, () -> rollback(store, staged));

        assertTrue(exception.getMessage().contains("fehlt"));
        assertArrayEquals(prepared, Files.readAllBytes(fightData));
    }

    @Test
    void rejectsChangedBackupWithoutReplacingPreparedData(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        var store = new EndDragonFightDataStore(4790);
        var staged = store.stage(worldFolder, SUPPRESSED, UUID.randomUUID());
        byte[] prepared = Files.readAllBytes(fightData);
        Files.writeString(staged.backup().orElseThrow(), "fremd");
        assertThrows(IOException.class, () -> rollback(store, staged));
        assertArrayEquals(prepared, Files.readAllBytes(fightData));
        assertEquals("fremd", Files.readString(staged.backup().orElseThrow()));
    }

    @Test
    void refusesToStageOverLegacyArtifacts(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "neue Generation");
        Path orphan = Files.writeString(fightData.resolveSibling(
                fightData.getFileName() + ".farmwelt-reset-alt.bak"), "alter Kampf");
        assertThrows(IOException.class, () -> new EndDragonFightDataStore(4790).stage(worldFolder, SUPPRESSED, UUID.randomUUID()));
        assertEquals("neue Generation", Files.readString(fightData));
        assertEquals("alter Kampf", Files.readString(orphan));
    }

    @Test
    void stageRejectsMissingWorldFolderInsteadOfCreatingIt(@TempDir Path tempDir) {
        Path missingWorld = tempDir.resolve("missing-world");

        assertThrows(
                IOException.class,
                () -> new EndDragonFightDataStore(4790).stage(missingWorld, SUPPRESSED, UUID.randomUUID())
        );
        assertFalse(Files.exists(missingWorld));
    }

    @Test
    void stageRejectsFightDataSymlinkWithoutTouchingTarget(@TempDir Path worldFolder)
            throws Exception {
        Path external = worldFolder.resolve("external-fight-data");
        Files.writeString(external, "external");
        Path fightData = worldFolder.resolve(FIGHT_DATA);
        Files.createDirectories(fightData.getParent());
        createSymbolicLinkOrSkip(fightData, external);

        assertThrows(
                IOException.class,
                () -> new EndDragonFightDataStore(4790).stage(worldFolder, SUPPRESSED, UUID.randomUUID())
        );
        assertEquals("external", Files.readString(external));
        assertTrue(Files.isSymbolicLink(fightData));
    }

    @Test
    void stageRejectsSymlinkInFightDataDirectoryPath(@TempDir Path worldFolder)
            throws Exception {
        Path externalData = Files.createDirectory(worldFolder.resolve("external-data"));
        Path dataLink = worldFolder.resolve("data");
        createSymbolicLinkOrSkip(dataLink, externalData);

        assertThrows(
                IOException.class,
                () -> new EndDragonFightDataStore(4790).stage(worldFolder, SUPPRESSED, UUID.randomUUID())
        );
        assertFalse(Files.exists(externalData.resolve("minecraft")));
    }

    @Test
    void stageWithoutOriginalAndRollbackAreBoundToPreparedInode(@TempDir Path world) throws Exception {
        var store = new EndDragonFightDataStore(4790);
        var staged = store.stage(world, SUPPRESSED, UUID.randomUUID());
        assertFalse(staged.hadFightData());
        assertTrue(Files.isRegularFile(world.resolve(FIGHT_DATA)));
        rollback(store, staged);
        store.finishRollback(staged);
        assertThrows(IOException.class, () -> store.commit(staged, world));
        assertFalse(Files.exists(world.resolve(FIGHT_DATA)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "symlink", "otherAttempt", "missingBoth", "missingActive"})
    void rejectsInvalidPairWithoutTouchingRemainingEntries(String kind, @TempDir Path world) throws Exception {
        var store = new EndDragonFightDataStore(4790);
        Path active = writeFightData(world, "Original");
        var staged = store.stage(world, SUPPRESSED, UUID.randomUUID());
        Path backup = staged.backup().orElseThrow();
        byte[] prepared = Files.readAllBytes(active);
        Path foreign = Files.writeString(world.resolve("foreign"), "Original");
        if (!kind.equals("missingActive")) Files.delete(backup);
        switch (kind) {
            case "directory" -> Files.createDirectory(backup);
            case "symlink" -> createSymbolicLinkOrSkip(backup, foreign);
            case "otherAttempt" -> Files.createLink(backup, foreign);
            default -> Files.delete(active);
        }
        assertThrows(IOException.class, () -> rollback(store, staged));
        if (kind.startsWith("missing")) assertFalse(Files.exists(active));
        else assertArrayEquals(prepared, Files.readAllBytes(active));
        assertEquals("Original", Files.readString(foreign));
        if (kind.equals("directory")) assertTrue(Files.isDirectory(backup));
        if (kind.equals("symlink")) assertTrue(Files.isSymbolicLink(backup));
        if (kind.equals("missingActive")) assertEquals("Original", Files.readString(backup));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "write", "force", "close", "hash", "backup", "atomic", "afterMove",
            "publish", "foreignTarget", "afterPublish", "recoveryFailure"})
    void stageErrorsPreserveOriginalOrProvenBackup(String fault, @TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        UUID id = UUID.randomUUID();
        Path work = active.resolveSibling(active.getFileName() + ".farmwelt-stage");
        Path backup = work.resolve(id + ".bak");
        Path temp = work.resolve(id + ".tmp");
        IOException injected = new IOException(fault);
        var files = new DragonDataFiles() {
            @Override void createPrepared(Path path, byte[] bytes) throws IOException {
                if (fault.equals("create")) throw injected;
                super.createPrepared(path, bytes);
                if (fault.equals("close")) throw injected;
            }
            @Override void write(FileChannel file, ByteBuffer bytes) throws IOException {
                if (fault.equals("write")) {
                    file.write(ByteBuffer.wrap(new byte[]{42}));
                    throw injected;
                }
                super.write(file, bytes);
            }
            @Override void flush(FileChannel file) throws IOException {
                if (fault.equals("force")) throw injected;
                super.flush(file);
            }
            @Override Fingerprint fingerprint(Path path) throws IOException {
                if (fault.equals("hash") && path.equals(temp)) throw injected;
                return super.fingerprint(path);
            }
            @Override void moveAside(Path source, Path slot) throws IOException {
                if (fault.equals("atomic")) throw new AtomicMoveNotSupportedException(source.toString(), slot.toString(), fault);
                super.moveAside(source, slot);
                if (fault.equals("afterMove")) throw injected;
            }
            @Override void link(Path target, Path source) throws IOException {
                if (fault.equals("backup") && target.equals(backup)) throw injected;
                if (target.equals(active)) {
                    if (fault.equals("recoveryFailure") && !source.equals(temp)) throw new IOException("Rücknahme");
                    if (fault.equals("foreignTarget") && source.equals(temp)) Files.writeString(target, "fremd");
                    if ((fault.equals("publish") || fault.equals("recoveryFailure")) && source.equals(temp)) throw injected;
                }
                super.link(target, source);
                if (fault.equals("afterPublish") && target.equals(active)) throw injected;
            }
        };
        var store = new EndDragonFightDataStore(4790, files);
        IOException error = assertThrows(IOException.class, () -> store.stage(world, SUPPRESSED, id));
        if (fault.equals("recoveryFailure")) {
            assertEquals(injected, error);
            assertEquals("Rücknahme", error.getSuppressed()[0].getMessage());
            assertFalse(Files.exists(active));
        } else if (fault.equals("foreignTarget")) {
            assertEquals("fremd", Files.readString(active));
        } else if (fault.equals("afterPublish")) {
            assertTrue(Files.isSameFile(active, temp));
        } else {
            assertEquals("Original", Files.readString(active));
        }
        if (Files.exists(backup) || !Files.exists(active) || fault.equals("foreignTarget") || fault.equals("afterPublish")) {
            assertEquals("Original", Files.readString(backup));
        }
        if (fault.equals("write")) assertArrayEquals(new byte[]{42}, Files.readAllBytes(temp));
        if (fault.equals("atomic")) assertTrue(error instanceof AtomicMoveNotSupportedException);
        assertThrows(IOException.class, () -> store.stage(world, SUPPRESSED, UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "activeRace", "backupRace", "foreignPublication", "delete"})
    void rollbackInterruptionPreservesFiles(String fault, @TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        UUID id = UUID.randomUUID();
        var files = new DragonDataFiles() {
            @Override void moveAside(Path source, Path slot) throws IOException {
                if (slot.toString().endsWith(".withdrawn")) {
                    if (fault.equals("move")) throw new IOException("Move");
                    if (fault.equals("activeRace")) {
                        Files.delete(source);
                        Files.writeString(source, "fremdes Ziel");
                    }
                    if (fault.equals("backupRace")) Files.writeString(slot.resolveSibling(id + ".bak"), "fremdes Backup");
                }
                super.moveAside(source, slot);
            }
            @Override void link(Path target, Path source) throws IOException {
                if (fault.equals("foreignPublication") && source.toString().endsWith(".bak")) {
                    Files.writeString(target, "fremdes Ziel");
                }
                super.link(target, source);
            }
            @Override void delete(Path path) throws IOException {
                if (fault.equals("delete")) throw new IOException("Delete");
                super.delete(path);
            }
        };
        var store = new EndDragonFightDataStore(4790, files);
        var staged = store.stage(world, SUPPRESSED, id);
        byte[] prepared = Files.readAllBytes(active);
        assertThrows(IOException.class, () -> rollback(store, staged));
        switch (fault) {
            case "delete" -> assertEquals("Original", Files.readString(active));
            case "activeRace", "foreignPublication" -> assertEquals("fremdes Ziel", Files.readString(active));
            default -> assertArrayEquals(prepared, Files.readAllBytes(active));
        }
        Path backup = staged.backup().orElseThrow();
        assertEquals(fault.equals("backupRace") ? "fremdes Backup" : "Original", Files.readString(backup));
        assertArrayEquals(prepared, Files.readAllBytes(backup.resolveSibling(id + ".tmp")));
        assertThrows(IOException.class, () -> rollback(store, staged));
        assertThrows(IOException.class, () -> store.commit(staged, world));
    }

    @Test
    void commitRefusesForeignBackupAndNeverRetriesDeletion(@TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        var store = new EndDragonFightDataStore(4790);
        var staged = store.stage(world, SUPPRESSED, UUID.randomUUID());
        byte[] prepared = Files.readAllBytes(active);
        Path backup = staged.backup().orElseThrow();
        Files.delete(backup);
        Files.writeString(backup, "Original");
        assertThrows(IOException.class, () -> store.commit(staged, world));
        assertThrows(IOException.class, () -> store.commit(staged, world));
        assertEquals("Original", Files.readString(backup));
        assertArrayEquals(prepared, Files.readAllBytes(active));
    }

    @ParameterizedTest
    @ValueSource(strings = {"backupOnly", "backupAndTemp", "backupAndActive", "multiple", "tempOnly", "newGeneration"})
    void freshStorePreservesCrashEvidence(String crash, @TempDir Path world) throws Exception {
        Path active = world.resolve(FIGHT_DATA);
        Files.createDirectories(active.getParent());
        Path work = Files.createDirectory(active.resolveSibling(active.getFileName() + ".farmwelt-stage"));
        Path backup = work.resolve(UUID.randomUUID() + ".bak");
        if (!crash.equals("tempOnly")) Files.writeString(backup, "alte Generation");
        if (crash.equals("backupAndTemp") || crash.equals("tempOnly")) Files.writeString(work.resolve("versuch.tmp"), "NBT");
        if (crash.equals("multiple")) Files.writeString(work.resolve(UUID.randomUUID() + ".bak"), "anderer Versuch");
        boolean hasActive = crash.equals("newGeneration") || crash.equals("backupAndActive");
        if (hasActive) Files.writeString(active, "aktueller Stand");
        long count;
        try (var entries = Files.list(work)) { count = entries.count(); }
        assertThrows(IOException.class, () -> new EndDragonFightDataStore(4790).stage(world, SUPPRESSED, UUID.randomUUID()));
        if (hasActive) assertEquals("aktueller Stand", Files.readString(active));
        else assertFalse(Files.exists(active));
        if (!crash.equals("tempOnly")) assertEquals("alte Generation", Files.readString(backup));
        try (var entries = Files.list(work)) { assertEquals(count, entries.count()); }
    }

    @Test
    void concurrentCommitAndRollbackHaveOneOwner(@TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        var store = new EndDragonFightDataStore(4790);
        var data = store.stage(world, SUPPRESSED, UUID.randomUUID());
        assertThrows(IOException.class, () -> store.stage(world, SUPPRESSED, UUID.randomUUID()));
        byte[] prepared = Files.readAllBytes(active);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CyclicBarrier(2);
            var commit = workers.submit(() -> {
                gate.await();
                try { store.commit(data, world); return true; } catch (IOException rejected) { return false; }
            });
            var restore = workers.submit(() -> {
                gate.await();
                try { rollback(store, data); return true; } catch (IOException rejected) { return false; }
            });
            assertNotEquals(commit.get(), restore.get());
            if (commit.get()) {
                store.commit(data, world);
                assertArrayEquals(prepared, Files.readAllBytes(active));
            } else {
                store.finishRollback(data);
                assertEquals("Original", Files.readString(active));
            }
            assertFalse(Files.exists(data.backup().orElseThrow()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherFolder", "missingActive", "changedActive", "otherInode"})
    void cleanupRequiresTheConfirmedGenerationAndActiveFile(String fault, @TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        var store = new EndDragonFightDataStore(4790);
        var data = store.stage(world, SUPPRESSED, UUID.randomUUID());
        byte[] prepared = Files.readAllBytes(active);
        if (fault.equals("missingActive") || fault.equals("otherInode")) Files.delete(active);
        if (fault.equals("otherInode")) Files.write(active, prepared);
        if (fault.equals("changedActive")) Files.writeString(active, "neuer Kampf");
        Path confirmed = fault.equals("otherFolder") ? world.resolve("andere Generation") : world;
        assertThrows(IOException.class, () -> store.commit(data, confirmed));
        assertEquals("Original", Files.readString(data.backup().orElseThrow()));
        if (fault.equals("missingActive")) assertFalse(Files.exists(active));
        else if (fault.equals("changedActive")) assertEquals("neuer Kampf", Files.readString(active));
        else assertArrayEquals(prepared, Files.readAllBytes(active));
    }

    @Test
    void vetoPreservesLargeOriginalForOfflineRecovery(@TempDir Path world) throws Exception {
        String original = "x".repeat(4097);
        Path active = writeFightData(world, original);
        var store = new EndDragonFightDataStore(4790);
        var data = store.stage(world, SUPPRESSED, UUID.randomUUID());
        byte[] prepared = Files.readAllBytes(active);
        assertThrows(IOException.class, () -> rollback(store, data));
        assertEquals(original, Files.readString(data.backup().orElseThrow()));
        assertArrayEquals(prepared, Files.readAllBytes(active));
    }

    @Test
    void rechecksHashAcrossSchedulerHandoffEvenWithIdenticalMetadata(@TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        var store = new EndDragonFightDataStore(4790);
        var data = store.stage(world, SUPPRESSED, UUID.randomUUID());
        store.prepareRollback(data);
        var modified = Files.getLastModifiedTime(active);
        byte[] changed = Files.readAllBytes(active);
        changed[0] ^= 1;
        Files.write(active, changed);
        Files.setLastModifiedTime(active, modified);
        assertThrows(IOException.class, () -> store.restoreVerified(data));
        assertEquals("Original", Files.readString(data.backup().orElseThrow()));
        assertArrayEquals(changed, Files.readAllBytes(active));
    }

    @Test
    void rejectsExternalHardlinksWhereLinkCountIsAvailable(@TempDir Path world) throws Exception {
        Path active = writeFightData(world, "Original");
        Assumptions.assumeTrue(active.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path alias = Files.createLink(world.resolve("alias"), active);
        assertThrows(IOException.class, () -> new EndDragonFightDataStore(4790).stage(world, SUPPRESSED, UUID.randomUUID()));
        assertEquals("Original", Files.readString(alias));
        assertTrue(Files.isSameFile(alias, active));
    }

    private static void rollback(EndDragonFightDataStore store, EndDragonFightDataStore.StagedData data) throws IOException {
        store.prepareRollback(data);
        store.restoreVerified(data);
        store.finishRollback(data);
    }

    private static Path writeFightData(Path worldFolder, String content) throws IOException {
        Path fightData = worldFolder.resolve(FIGHT_DATA);
        Files.createDirectories(fightData.getParent());
        Files.writeString(fightData, content);
        return fightData;
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("Symbolische Links sind in dieser Testumgebung nicht verf\u00fcgbar.");
        }
    }
}
