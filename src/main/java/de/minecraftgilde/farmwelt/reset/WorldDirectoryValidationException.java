package de.minecraftgilde.farmwelt.reset;

/** Describes why a Bukkit-provided world directory was rejected. */
public final class WorldDirectoryValidationException extends IllegalArgumentException {

    public enum Reason {
        INVALID_WORLD_NAME,
        WORLD_NOT_FOUND,
        PROTECTED_WORLD,
        UNSAFE_PATH
    }

    private final Reason reason;

    public WorldDirectoryValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WorldDirectoryValidationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
