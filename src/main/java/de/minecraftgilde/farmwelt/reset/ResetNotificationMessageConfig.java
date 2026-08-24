package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;

/** Immutable Konfiguration einer einzelnen, zukünftig versendbaren Reset-Nachricht. */
public record ResetNotificationMessageConfig(boolean enabled, String message) {

    public ResetNotificationMessageConfig {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message darf nicht leer sein.");
        }
    }
}
