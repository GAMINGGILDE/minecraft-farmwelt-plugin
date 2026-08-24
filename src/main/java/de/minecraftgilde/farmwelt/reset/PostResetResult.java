package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.Optional;

/** Outcome of applying post-reset settings to an already regenerated world. */
public record PostResetResult(
        boolean successful,
        String message,
        Optional<Throwable> cause
) {

    public PostResetResult {
        message = Objects.requireNonNull(message, "message");
        cause = Objects.requireNonNull(cause, "cause");
    }

    public static PostResetResult success() {
        return new PostResetResult(true, "Post-Reset-Initialisierung abgeschlossen.", Optional.empty());
    }

    public static PostResetResult failure(String message, Throwable cause) {
        return new PostResetResult(false, message, Optional.ofNullable(cause));
    }
}
