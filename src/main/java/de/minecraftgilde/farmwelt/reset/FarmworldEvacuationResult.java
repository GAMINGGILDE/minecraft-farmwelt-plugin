package de.minecraftgilde.farmwelt.reset;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;

/** Ergebnis der Evakuierung mit den durch diesen Reset tatsächlich teleportierten Spielern. */
public record FarmworldEvacuationResult(
        boolean successful,
        List<Player> evacuatedPlayers,
        Optional<Throwable> failure
) {

    public FarmworldEvacuationResult {
        Objects.requireNonNull(evacuatedPlayers, "evacuatedPlayers");
        Objects.requireNonNull(failure, "failure");
        evacuatedPlayers = List.copyOf(new LinkedHashSet<>(evacuatedPlayers));
        if (successful && failure.isPresent()) {
            throw new IllegalArgumentException(
                    "Eine erfolgreiche Evakuierung darf keinen Fehler enthalten."
            );
        }
    }

    public static FarmworldEvacuationResult completed(
            Collection<? extends Player> evacuatedPlayers
    ) {
        return new FarmworldEvacuationResult(
                true,
                List.copyOf(Objects.requireNonNull(evacuatedPlayers, "evacuatedPlayers")),
                Optional.empty()
        );
    }

    public static FarmworldEvacuationResult failed(
            Collection<? extends Player> evacuatedPlayers,
            Throwable failure
    ) {
        return new FarmworldEvacuationResult(
                false,
                List.copyOf(Objects.requireNonNull(evacuatedPlayers, "evacuatedPlayers")),
                Optional.ofNullable(failure)
        );
    }
}
