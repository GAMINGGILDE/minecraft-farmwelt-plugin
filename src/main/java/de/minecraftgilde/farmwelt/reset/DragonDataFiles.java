package de.minecraftgilde.farmwelt.reset;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Begrenzte Dateizugriffe während des synchronen Unload-Events. */
class DragonDataFiles {
    private static final int MAX_BYTES = 1024 * 1024;

    static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    }

    static boolean present(Path path) throws IOException {
        try {
            attributes(path);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    static void directory(Path path) throws IOException {
        if (!attributes(path).isDirectory() || !path.toRealPath().equals(path.toRealPath(NOFOLLOW_LINKS))) {
            throw new IOException("Umgeleiteter oder ungültiger Verzeichnispfad: " + path);
        }
    }

    record Identity(Object key, FileTime created) {
        static Identity of(BasicFileAttributes attributes) {
            return new Identity(attributes.fileKey(), attributes.creationTime());
        }
    }

    record Stamp(Identity identity, long size, FileTime modified) {
        static Stamp of(BasicFileAttributes attributes) {
            return new Stamp(Identity.of(attributes), attributes.size(), attributes.lastModifiedTime());
        }
    }

    record Fingerprint(Stamp stamp, String sha256) {}

    Fingerprint fingerprint(Path path) throws IOException {
        return fingerprint(path, MAX_BYTES);
    }

    Fingerprint fingerprint(Path path, long limit) throws IOException {
        var before = attributes(path);
        if (!before.isRegularFile() || before.size() > limit) {
            throw new IOException("DragonBattle-Dateityp oder Größe verändert bzw. unzulässig: " + path);
        }
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count = 0;
            for (int n; (n = input.read(buffer)) != -1;) {
                count += n;
                if (count > limit) throw new IOException("Datei wächst während der Prüfung: " + path);
                digest.update(buffer, 0, n);
            }
            var after = attributes(path);
            if (count != before.size() || after.size() != count || !after.isRegularFile()
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.creationTime().equals(after.creationTime())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException("Datei während der Prüfung verändert: " + path);
            }
        }
        return new Fingerprint(Stamp.of(before), HexFormat.of().formatHex(digest.digest()));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void createPrepared(Path temp, byte[] bytes) throws IOException {
        try (var file = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
            write(file, ByteBuffer.wrap(bytes));
            flush(file);
        }
    }

    void write(FileChannel file, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) file.write(buffer);
    }

    void flush(FileChannel file) throws IOException {
        file.force(true);
    }

    void moveAside(Path source, Path slot) throws IOException {
        if (present(slot)) throw new IOException("Versuchsdatei bereits belegt: " + slot);
        Files.move(source, slot, StandardCopyOption.ATOMIC_MOVE);
    }

    void link(Path target, Path source) throws IOException {
        Files.createLink(target, source);
    }

    void delete(Path path) throws IOException {
        Files.delete(path);
    }
}
