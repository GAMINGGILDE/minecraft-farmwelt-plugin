package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
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

        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED);

        assertTrue(staged.hadFightData());
        Path backup = staged.backup().orElseThrow();
        assertTrue(Files.isRegularFile(backup));
        assertEquals("original", Files.readString(backup));
        assertNotEquals((byte) 'o', Files.readAllBytes(fightData)[0]);

        store.commit(staged);

        assertFalse(Files.exists(backup));
        assertTrue(Files.isRegularFile(fightData));
    }

    @Test
    void rollbackRestoresBackedUpFightData(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);
        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED);

        store.rollback(staged);

        assertEquals("original", Files.readString(fightData));
        assertFalse(Files.exists(staged.backup().orElseThrow()));
    }

    @Test
    void rollbackRejectsManipulatedPreparedFile(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);
        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED);
        Files.writeString(fightData, "manipulated");

        IOException exception = assertThrows(IOException.class, () -> store.rollback(staged));

        assertTrue(exception.getMessage().contains("ver\u00e4ndert"));
        assertEquals("manipulated", Files.readString(fightData));
        assertTrue(Files.exists(staged.backup().orElseThrow()));
    }

    @Test
    void rollbackFailsClosedWhenBackupIsMissing(@TempDir Path worldFolder) throws Exception {
        Path fightData = writeFightData(worldFolder, "original");
        EndDragonFightDataStore store = new EndDragonFightDataStore(4790);
        EndDragonFightDataStore.StagedData staged = store.stage(worldFolder, SUPPRESSED);
        Files.delete(staged.backup().orElseThrow());

        IOException exception = assertThrows(IOException.class, () -> store.rollback(staged));

        assertTrue(exception.getMessage().contains("fehlt"));
        assertFalse(Files.exists(fightData));
    }

    @Test
    void stageRejectsMissingWorldFolderInsteadOfCreatingIt(@TempDir Path tempDir) {
        Path missingWorld = tempDir.resolve("missing-world");

        assertThrows(
                IOException.class,
                () -> new EndDragonFightDataStore(4790).stage(missingWorld, SUPPRESSED)
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
                () -> new EndDragonFightDataStore(4790).stage(worldFolder, SUPPRESSED)
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
                () -> new EndDragonFightDataStore(4790).stage(worldFolder, SUPPRESSED)
        );
        assertFalse(Files.exists(externalData.resolve("minecraft")));
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
