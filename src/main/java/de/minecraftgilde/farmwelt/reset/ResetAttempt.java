package de.minecraftgilde.farmwelt.reset;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Der Abschlussbeleg gehört immer genau zu diesem Versuch und seinem Zeitplan. */
record ResetAttempt(UUID id, String key, String world, Instant started, Phase phase,
                    Instant completed, Instant next) {
    enum Phase {
        WORLDS_PENDING("Übergabe abgesichert, Ergebnis von Worlds offen"),
        WORLDS_RETURNED("Worlds lieferte eine neue Instanz, Validierung oder Initialisierung offen"),
        POST_RESET_DONE("Validierung und Post-Reset bestätigt, passender Zeitplanbeleg fehlt");

        final String description;

        Phase(String description) {
            this.description = description;
        }
    }

    ResetAttempt {
        if (!singleLineName(key) || !singleLineName(world)) {
            throw new IllegalArgumentException("Ungültiger Farmwelt- oder Bukkit-Name im Journal.");
        }
        if (phase == Phase.POST_RESET_DONE ? completed == null || next == null || !next.isAfter(completed)
                : completed != null || next != null) {
            throw new IllegalArgumentException("Phase und Abschlusszeiten widersprechen sich.");
        }
    }

    private static boolean singleLineName(String name) {
        return !name.isBlank() && !name.contains("\n") && !name.contains("\r");
    }

    static UUID parseId(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Unvollständige Versuchs-UUID.");
        }
        return id;
    }

    ResetAttempt worldsReturned() {
        if (phase != Phase.WORLDS_PENDING) {
            throw new IllegalStateException("Worlds-Ergebnis ohne vorbereiteten Versuch.");
        }
        return new ResetAttempt(id, key, world, started, Phase.WORLDS_RETURNED, null, null);
    }

    ResetAttempt postResetDone(Instant at, Instant nextReset) {
        if (phase != Phase.WORLDS_RETURNED) {
            throw new IllegalStateException("Post-Reset ohne bestätigtes Worlds-Ergebnis.");
        }
        return new ResetAttempt(id, key, world, started, Phase.POST_RESET_DONE, at, nextReset);
    }

    boolean confirmedBy(FarmworldResetState state) {
        return phase == Phase.POST_RESET_DONE && state != null && key.equals(state.farmworldKey())
                && state.attemptId().equals(Optional.of(id)) && state.lastReset().equals(Optional.of(completed))
                && next.equals(state.nextReset());
    }

    String diagnosis() {
        return key + " (" + world + "), Versuch " + id + " seit " + started + ", " + phase
                + " – " + phase.description + ".";
    }
}
