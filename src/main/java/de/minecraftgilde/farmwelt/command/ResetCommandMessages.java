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
                    "§cDie Reset-Konfiguration oder die geladene Bukkit-Welt ist ungültig.",
                    "§cBitte Serverlog prüfen!"
            );
            case WORLD_NOT_FOUND -> List.of(
                    "§cDie konfigurierte Farmwelt wurde nicht gefunden."
            );
            case WORLD_NOT_LOADED -> List.of(
                    "§cDie Farmwelt ist derzeit nicht geladen und kann nicht sicher zurückgesetzt werden."
            );
            case PROTECTED_WORLD -> List.of(
                    "§cDie konfigurierte Welt ist als Hauptwelt geschützt und darf nicht zurückgesetzt werden."
            );
            case EVACUATION_FAILED -> List.of(
                    "§cDer Reset wurde abgebrochen, da nicht alle Spieler sicher evakuiert werden konnten."
            );
            case REGENERATE_FAILED -> List.of(
                    "§cDie Farmwelt konnte durch Worlds nicht neu generiert werden.",
                    "§cBitte Serverlog prüfen!"
            );
            case POST_RESET_FAILED -> List.of(
                    "§cDie Farmwelt wurde neu erstellt, konnte aber nicht vollständig initialisiert werden.",
                    "§cBitte Serverlog prüfen!"
            );
            case STATE_SAVE_FAILED -> List.of(
                    "§cDie Welt wurde erfolgreich regeneriert, aber der Reset-State konnte nicht gespeichert werden.",
                    "§cBitte reset-state.yml und Serverlog prüfen!"
            );
            case INTERNAL_ERROR -> List.of(
                    "§cReset der Farmwelt '" + farmworldKey + "' ist durch einen internen Fehler fehlgeschlagen.",
                    "§cBitte Serverlog prüfen!"
            );
        };
    }
}
