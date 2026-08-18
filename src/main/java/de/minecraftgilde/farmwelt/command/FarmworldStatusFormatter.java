package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import de.minecraftgilde.farmwelt.reset.FarmworldType;
import de.minecraftgilde.farmwelt.reset.ResetDueState;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FarmworldStatusFormatter {

    private static final String VALUE = "§f";
    private static final String LABEL = "§7";

    private final DateTimeFormatter instantFormatter;
    private final GermanDurationFormatter durationFormatter;

    public FarmworldStatusFormatter(ZoneId zoneId) {
        this(zoneId, new GermanDurationFormatter());
    }

    FarmworldStatusFormatter(
            ZoneId zoneId,
            GermanDurationFormatter durationFormatter
    ) {
        this.instantFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(Objects.requireNonNull(zoneId, "zoneId"));
        this.durationFormatter = Objects.requireNonNull(durationFormatter, "durationFormatter");
    }

    List<String> formatOverview(List<FarmworldResetStatusSnapshot> snapshots) {
        List<String> lines = new ArrayList<>();
        lines.add("§6Farmwelt Reset-Status");
        if (snapshots.isEmpty()) {
            lines.add("§7Es sind keine Reset-Farmwelten konfiguriert.");
            return List.copyOf(lines);
        }

        for (FarmworldResetStatusSnapshot snapshot : snapshots) {
            FarmworldResetConfig config = snapshot.config();
            lines.add("");
            lines.add("§e" + config.farmworldKey() + " §7(" + config.worldName() + ")");
            lines.add(LABEL + "Status: " + status(snapshot));
            lines.add(LABEL + "Reset aktiviert: " + yesNo(config.enabled()));
            lines.add(LABEL + "Reset läuft: " + running(snapshot.resetRunning()));
            lines.add(LABEL + "Letzter Reset: " + formatLastReset(snapshot.state()));
            lines.add(LABEL + "Nächster Reset: " + formatNextReset(snapshot.state()));
            lines.add(LABEL + "Verbleibend: " + formatRemaining(snapshot));
            lines.add(LABEL + "Intervall: " + VALUE + durationFormatter.format(config.interval()));
        }
        return List.copyOf(lines);
    }

    List<String> formatDetails(FarmworldResetStatusSnapshot snapshot) {
        FarmworldResetConfig config = snapshot.config();
        List<String> lines = new ArrayList<>();
        lines.add("§6Reset-Status: §e" + config.farmworldKey());
        lines.add("");
        lines.add(LABEL + "Status: " + status(snapshot));
        lines.add(LABEL + "Weltname: " + VALUE + config.worldName());
        lines.add(LABEL + "Typ: " + VALUE + typeName(config.farmworldType()));
        lines.add(LABEL + "Reset aktiviert: " + yesNo(config.enabled()));
        lines.add(LABEL + "Reset läuft: " + running(snapshot.resetRunning()));
        lines.add(LABEL + "Intervall: " + VALUE + durationFormatter.format(config.interval()));
        lines.add(LABEL + "Letzter Reset: " + formatLastReset(snapshot.state()));
        lines.add(LABEL + "Nächster Reset: " + formatNextReset(snapshot.state()));
        lines.add(LABEL + "Verbleibend: " + formatRemaining(snapshot));
        return List.copyOf(lines);
    }

    String formatLastReset(Optional<FarmworldResetState> state) {
        return state.flatMap(FarmworldResetState::lastReset)
                .map(instant -> VALUE + instantFormatter.format(instant))
                .orElseGet(() -> state.isPresent() ? VALUE + "Noch nie" : VALUE + "-");
    }

    String formatNextReset(Optional<FarmworldResetState> state) {
        return state.map(FarmworldResetState::nextReset)
                .map(instant -> VALUE + instantFormatter.format(instant))
                .orElse(VALUE + "-");
    }

    String formatRemaining(FarmworldResetStatusSnapshot snapshot) {
        if (!snapshot.config().enabled()
                || snapshot.state().isEmpty()
                || snapshot.dueState() == ResetDueState.DISABLED) {
            return VALUE + "-";
        }

        if (snapshot.dueState() == ResetDueState.DUE) {
            return "§cÜberfällig";
        }
        return VALUE + durationFormatter.format(Duration.between(
                snapshot.evaluatedAt(),
                snapshot.state().orElseThrow().nextReset()
        ));
    }

    private String status(FarmworldResetStatusSnapshot snapshot) {
        if (snapshot.resetRunning()) {
            return "§eLäuft";
        }
        if (!snapshot.config().enabled()) {
            return "§cDeaktiviert";
        }
        if (snapshot.state().isEmpty()
                || snapshot.dueState() == ResetDueState.DISABLED) {
            return "§eKein Zeitplan";
        }
        return switch (snapshot.dueState()) {
            case DUE -> "§cÜberfällig";
            case NOT_DUE -> "§aGeplant";
            case DISABLED -> throw new IllegalStateException("DISABLED wurde bereits behandelt.");
        };
    }

    private String yesNo(boolean value) {
        return value ? "§aJa" : "§cNein";
    }

    private String running(boolean value) {
        return value ? "§cJa" : "§aNein";
    }

    private String typeName(FarmworldType farmworldType) {
        return switch (farmworldType) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case END -> "End";
        };
    }
}
