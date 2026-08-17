package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.reset.ResetResult;
import java.util.List;

final class ResetCommandMessages {

    private ResetCommandMessages() {
    }

    static List<String> forResult(ResetResult result) {
        String farmworldKey = result.farmworldKey();
        return switch (result.status()) {
            case SUCCESS -> List.of("§aFarmwelt '" + farmworldKey + "' wurde erfolgreich zurückgesetzt.");
            case NOT_CONFIGURED -> List.of("§cDie Farmwelt '" + farmworldKey + "' ist nicht konfiguriert.");
            case DISABLED -> List.of("§cDer Reset ist für diese Farmwelt deaktiviert.");
            case ALREADY_RUNNING -> List.of("§eFür diese Farmwelt läuft bereits ein Reset.");
            case INVALID_CONFIGURATION -> List.of(
                    "§cDie Reset-Konfiguration oder der Weltpfad ist ungültig.",
                    "§cBitte Serverlog prüfen!"
            );
            case WORLD_NOT_FOUND -> List.of(
                    "§cDie konfigurierte Farmwelt oder ihr Weltordner wurde nicht gefunden."
            );
            case EVACUATION_FAILED -> List.of(
                    "§cDer Reset wurde abgebrochen, da nicht alle Spieler sicher evakuiert werden konnten."
            );
            case UNLOAD_FAILED -> List.of(
                    "§cDie Farmwelt konnte nicht entladen werden. Es wurden keine Weltdaten gelöscht."
            );
            case DELETE_FAILED -> List.of(
                    "§cDer Weltordner konnte nicht vollständig gelöscht werden."
            );
            case CREATE_FAILED -> List.of(
                    "§cDie alte Welt wurde entfernt, aber die neue Welt konnte nicht erstellt werden.",
                    "§cBitte Serverlog prüfen!"
            );
            case STATE_SAVE_FAILED -> List.of(
                    "§cDie Welt wurde erfolgreich neu erstellt, aber der Reset-State konnte nicht gespeichert werden.",
                    "§cBitte reset-state.yml und Serverlog prüfen!"
            );
            case INTERNAL_ERROR -> List.of(
                    "§cReset der Farmwelt '" + farmworldKey + "' ist durch einen internen Fehler fehlgeschlagen.",
                    "§cBitte Serverlog prüfen!"
            );
        };
    }
}
