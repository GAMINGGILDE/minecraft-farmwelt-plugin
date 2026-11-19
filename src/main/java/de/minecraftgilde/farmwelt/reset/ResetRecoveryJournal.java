package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Je Dimension ein dauerhafter Platz. Nur die erste Installation darf freie Plätze anlegen. */
final class ResetRecoveryJournal {
    private final Path directory;
    private final Map<FarmworldType, ResetAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<FarmworldType, String> errors = new ConcurrentHashMap<>();

    ResetRecoveryJournal(Path directory) {
        this.directory = directory;
    }

    void load(Map<String, FarmworldResetState> schedules) {
        Path marker = directory.resolve("reset-recovery.version");
        try {
            if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
                // Ein Restbestand ist keine Neuinstallation, auch wenn der Versionsmarker fehlt.
                for (FarmworldType type : FarmworldType.values()) {
                    if (!Files.notExists(file(type), LinkOption.NOFOLLOW_LINKS)
                            || !Files.notExists(temporaryFile(type), LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Recovery-Versionsmarker fehlt bei vorhandenen Journalen.");
                    }
                }
                if (schedules.values().stream().anyMatch(s -> s.attemptId().isPresent())) {
                    throw new IOException("Recovery-Dateien fehlen zu einem bestätigten Reset.");
                }
                Files.writeString(marker, "1\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        StandardOpenOption.SYNC);
                for (FarmworldType type : FarmworldType.values()) {
                    try {
                        Files.writeString(file(type), "version=1\nphase=READY\n", StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                    } catch (IOException e) {
                        errors.put(type, e.toString());
                    }
                }
                DurableResetFiles.syncDirectory(directory);
            }
            verifyVersion(marker);
        } catch (IOException | RuntimeException e) {
            for (FarmworldType type : FarmworldType.values()) {
                errors.put(type, e.toString());
            }
            return;
        }
        for (FarmworldType type : FarmworldType.values()) {
            try {
                ResetAttempt entry = read(type);
                if (entry != null) {
                    attempts.put(type, entry);
                }
            } catch (IOException | RuntimeException e) {
                errors.put(type, e.toString());
            }
        }
    }

    Optional<String> problem(FarmworldResetConfig config, Map<String, FarmworldResetState> schedules) {
        FarmworldType type = config.farmworldType();
        if (errors.containsKey(type)) {
            return Optional.of("Recovery-Journal für " + type + " ungeklärt. " + errors.get(type));
        }
        for (var entry : attempts.entrySet()) {
            ResetAttempt other = entry.getValue();
            if (entry.getKey() != type && other.world().equalsIgnoreCase(config.worldName())
                    && !other.confirmedBy(schedules.get(other.key()))) {
                return Optional.of(other.diagnosis());
            }
        }
        ResetAttempt attempt = attempts.get(type);
        if (attempt != null) {
            return attempt.confirmedBy(schedules.get(config.farmworldKey())) ? Optional.empty()
                    : Optional.of(attempt.diagnosis());
        }
        boolean missing = schedules.values().stream().anyMatch(state -> state.attemptId().isPresent()
                && FarmworldType.fromFarmworldKey(state.farmworldKey()).orElse(null) == type);
        return missing ? Optional.of("Journal meldet READY trotz vorhandenem Abschlussbeleg.") : Optional.empty();
    }

    void replace(FarmworldType type, ResetAttempt expected, ResetAttempt update) throws IOException {
        try {
            verifyVersion(directory.resolve("reset-recovery.version"));
            if (errors.containsKey(type) || !Objects.equals(attempts.get(type), expected)
                    || !Objects.equals(read(type), expected)) {
                throw new IOException("Versuch oder Journal stimmt vor dem Phasenwechsel nicht überein.");
            }
            Path target = file(type);
            Path tmp = temporaryFile(type);
            // Ein alter temporärer Stand hat nie eine Freigabe erteilt. Nur der atomare Replace zählt.
            Files.writeString(tmp, encode(update), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC, LinkOption.NOFOLLOW_LINKS);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DurableResetFiles.syncDirectory(directory);
            attempts.put(type, update);
        } catch (IOException | RuntimeException e) {
            errors.put(type, (expected == null ? "" : expected.diagnosis() + " ") + e);
            throw e;
        }
    }

    ResetAttempt current(FarmworldType type) {
        return attempts.get(type);
    }

    private static void verifyVersion(Path marker) throws IOException {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !Files.readString(marker).strip().equals("1")) {
            throw new IOException("Recovery-Versionsmarker fehlt oder enthält eine unbekannte Version.");
        }
    }

    private Path file(FarmworldType type) {
        return directory.resolve("reset-recovery-" + type.name().toLowerCase(Locale.ROOT) + ".journal");
    }

    private Path temporaryFile(FarmworldType type) {
        Path target = file(type);
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    private ResetAttempt read(FarmworldType type) throws IOException {
        if (!Files.isRegularFile(file(type), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Journal fehlt oder ist keine reguläre Datei: " + file(type));
        }
        List<String> lines = Files.readAllLines(file(type));
        if (lines.equals(List.of("version=1", "phase=READY"))) {
            return null;
        }
        if (lines.size() != 8 || !lines.getFirst().equals("version=1")) {
            throw new IOException("Unbekanntes oder unvollständiges Recovery-Format in " + file(type));
        }
        String key = field(lines, 3, "key");
        if (FarmworldType.fromFarmworldKey(key).orElse(null) != type) {
            throw new IOException("Farmwelt-ID passt nicht zum Journal " + file(type));
        }
        String completed = field(lines, 6, "completed");
        String next = field(lines, 7, "next");
        return new ResetAttempt(ResetAttempt.parseId(field(lines, 2, "id")), key, field(lines, 4, "world"),
                Instant.parse(field(lines, 5, "started")), ResetAttempt.Phase.valueOf(field(lines, 1, "phase")),
                completed.isEmpty() ? null : Instant.parse(completed), next.isEmpty() ? null : Instant.parse(next));
    }

    private static String field(List<String> lines, int index, String name) throws IOException {
        String prefix = name + "=";
        if (!lines.get(index).startsWith(prefix)) {
            throw new IOException("Pflichtfeld '" + name + "' fehlt im Journal.");
        }
        return lines.get(index).substring(prefix.length());
    }

    private static String encode(ResetAttempt a) {
        return "version=1\nphase=" + a.phase() + "\nid=" + a.id() + "\nkey=" + a.key() + "\nworld=" + a.world()
                + "\nstarted=" + a.started() + "\ncompleted=" + Objects.toString(a.completed(), "")
                + "\nnext=" + Objects.toString(a.next(), "") + "\n";
    }
}
