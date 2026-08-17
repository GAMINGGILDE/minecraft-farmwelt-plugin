package de.minecraftgilde.farmwelt.reset;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable settings applied to a newly regenerated farmworld. */
public record PostResetConfig(
        Map<String, Object> gamerules,
        Optional<WorldBorderConfig> worldBorder,
        Optional<EndPostResetConfig> end
) {

    private static final PostResetConfig NONE = new PostResetConfig(
            Map.of(),
            Optional.empty(),
            Optional.empty()
    );

    public PostResetConfig {
        Objects.requireNonNull(gamerules, "gamerules");
        Objects.requireNonNull(worldBorder, "worldBorder");
        Objects.requireNonNull(end, "end");
        gamerules = Collections.unmodifiableMap(new LinkedHashMap<>(gamerules));
    }

    public static PostResetConfig none() {
        return NONE;
    }
}
