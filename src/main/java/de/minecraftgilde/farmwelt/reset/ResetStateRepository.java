package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.util.Map;

public interface ResetStateRepository {

    Map<String, FarmworldResetState> load() throws IOException;

    /** Enthält die beim letzten Lesen gesperrten YAML-Identitäten. */
    default Map<FarmworldType, String> identityConflicts() {
        return Map.of();
    }

    default boolean hasConflictingReceipt() {
        return false;
    }

    void save(Map<String, FarmworldResetState> states) throws IOException;
}
