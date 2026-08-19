package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.Optional;

/**
 * Zentraler, zustandsloser Zugriff auf die aktuell geladenen Notification-Snapshots.
 * Nachrichten werden in dieser Ausbaustufe bewusst nicht versendet.
 */
public final class ResetNotificationService {

    private final FarmworldResetService resetService;

    public ResetNotificationService(FarmworldResetService resetService) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
    }

    public Optional<ResetNotificationConfig> getConfig(String farmworldKey) {
        return resetService.getConfig(farmworldKey).map(FarmworldResetConfig::notifications);
    }
}
