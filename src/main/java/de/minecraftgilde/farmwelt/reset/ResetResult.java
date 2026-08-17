package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record ResetResult(
        String farmworldKey,
        String worldName,
        ResetStatus status,
        String message,
        @Nullable Throwable cause
) {

    public ResetResult {
        farmworldKey = Objects.requireNonNull(farmworldKey, "farmworldKey");
        worldName = Objects.requireNonNull(worldName, "worldName");
        status = Objects.requireNonNull(status, "status");
        message = Objects.requireNonNull(message, "message");
    }

    public boolean successful() {
        return status == ResetStatus.SUCCESS;
    }
}
