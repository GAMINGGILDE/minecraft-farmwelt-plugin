# Farmwelt Admin Guide

Diese Anleitung ist das vollständige Betriebs- und Konfigurationshandbuch für Farmwelt V2. Die technische Implementierung und ihre Threading-Verträge stehen getrennt in der [Architekturdokumentation](ARCHITECTURE.md); reale Abnahmeszenarien stehen in der [Black-Box-Teststrategie](testing/black-box-testing.md).

## Begriffe

Farmwelt verwendet drei Werte, die nicht verwechselt werden dürfen:

| Begriff | Beispiel | Verwendung |
| --- | --- | --- |
| Logische Farmwelt-ID | `overworld`, `nether`, `end` | Config-Pfad, Status- und Reset-Commands, Reset-Lock |
| Display-Name | `Farmwelt`, `Netherfarm`, `Endfarm` | GUI und nutzersichtbare Reset-Nachrichten |
| Bukkit-Weltname | `farmwelt`, `netherfarm`, `endfarm` | Geladene Weltinstanz und Worlds-Regeneration |

Bei `/farmwelt status <welt>` und `/farmwelt reset force <welt>` ist `<welt>` immer die logische Farmwelt-ID. Der Bukkit-Weltname wird ausschließlich aus `farmworlds.<id>.reset.world` gelesen.

## Voraussetzungen und Installation

- Java 25.
- Minecraft/Paper/Folia 26.1.2; der Build verwendet aktuell `paper-api:26.1.2.build.74-stable`.
- Worlds 4.4.0 als harte Runtime-Abhängigkeit.
- Optional BetterRTP für die ausgelieferten Teleportbefehle.
- Optional GriefPrevention für Claim-Ausnahmen.

Installation:

1. Worlds und dessen Servervoraussetzungen installieren.
2. Farmwelt-JAR in `plugins/` ablegen und den Server starten.
3. `plugins/Farmwelt/config.yml` an Server-Weltnamen und Teleportziele anpassen.
4. Falls Claim-Ausnahmen genutzt werden, GriefPrevention und `resource-monitor.claim-protection` prüfen.
5. Server neu starten oder `/farmwelt reload` ausführen.
6. `/farmwelt info`, `/farmwelt status`, `/farmwelt debug claim` und `/farmwelt debug monitor` prüfen.
7. Ressourcenmonitor zunächst mit `mode: audit` betreiben.

Ohne nutzbare Worlds-API deaktiviert sich Farmwelt. Farmwelt entlädt, löscht oder erzeugt Welten nicht selbst über Bukkit.

## Commands

| Befehl | Beschreibung | Permission | Sender |
| --- | --- | --- | --- |
| `/farmwelt` | Öffnet das 45-Slot-Farmwelt-Menü. | `farmwelt.use` | Spieler |
| `/farmwelt status` | Zeigt den Reset-Status aller konfigurierten Reset-Farmwelten. | `farmwelt.admin.status` | Spieler, Konsole |
| `/farmwelt status <welt>` | Zeigt Weltname, Typ, Intervall, letzten/nächsten Reset und Restzeit. | `farmwelt.admin.status` | Spieler, Konsole |
| `/farmwelt info` | Zeigt Plugin-Version, Menüeinträge, Monitor, Hooks, BetterRTP, GriefPrevention und Jail-Modus. | `farmwelt.admin` | Spieler, Konsole |
| `/farmwelt reload` | Validiert YAML und lädt die Farmwelt-Konfiguration neu. | `farmwelt.admin.reload` | Spieler, Konsole |
| `/farmwelt reset force <welt>` | Startet sofort die vollständige Reset-Pipeline. | `farmwelt.admin.reset` | Spieler, Konsole |
| `/farmwelt reset force end --dragon` | Erzwingt nur für diesen End-Reset einen frischen Vanilla-Erstkampf. | `farmwelt.admin.reset` | Spieler, Konsole |
| `/farmwelt debug claim` | Zeigt Claim-Provider, Hook-Status und Claim-Status der Spielerposition. | `farmwelt.admin` | Spieler |
| `/farmwelt debug monitor` | Schaltet einen Rechtsklick-Debugmodus für Ressourcenentscheidungen um. | `farmwelt.admin` | Spieler |
| `/farmwelt debug violations [spieler]` | Zeigt Violation-, Blockier-, Schwellen- und Jail-Status; Zielspieler muss online sein. | `farmwelt.admin` | Spieler |

Tab-Completion bietet nur Commands an, für die der Sender die jeweilige Permission besitzt. Async-Abschlussmeldungen eines Resets werden auch bei Commands aus der Konsole im passenden Folia-Kontext zugestellt.

## Permissions

Die folgende Tabelle ist gegen [`paper-plugin.yml`](../src/main/resources/paper-plugin.yml) abgeglichen:

| Permission | Eingebauter Default | Bedeutung |
| --- | --- | --- |
| `farmwelt.use` | `true` | Darf das Farmwelt-Menü öffnen. |
| `farmwelt.bypass` | `op` | Wird vom Ressourcenmonitor ignoriert. |
| `farmwelt.notify` | `op` | Erhält Audit- und schwellenbasierte Staff-Meldungen. |
| `farmwelt.admin` | `op` | Darf `info` und Debug-Befehle verwenden; besitzt als Children außerdem die drei getrennten Admin-Permissions. |
| `farmwelt.admin.status` | `op` | Darf Reset-Status anzeigen. |
| `farmwelt.admin.reload` | `op` | Darf die Konfiguration neu laden. |
| `farmwelt.admin.reset` | `op` | Darf sofortige manuelle Resets starten. |

`resource-monitor.bypass-permission` und `resource-monitor.notify-permission` können die beiden Monitor-Permissionnamen ändern. Die Admin-Permissions sind nicht konfigurierbar.

## Config-Referenz

Die kommentierte [Default-Config](../src/main/resources/config.yml) ist die vollständige Source of Truth für die ausgelieferten Materiallisten und Nachrichtentexte. Die folgenden Tabellen beschreiben die tatsächlich gelesenen V2-Schlüssel. „Auslieferung“ bezeichnet die mit einer neuen Installation erzeugte Config; ein Parser-Fallback greift nur, wenn ein Schlüssel fehlt.

### Farmwelt-Menü

Pfad: `farmworlds.<id>`

| Schlüssel | Auslieferung | Vertrag |
| --- | --- | --- |
| `enabled` | `true` | Schaltet den GUI-Eintrag ein. Fehlt der Schlüssel, gilt ebenfalls `true`. |
| `display-name` | je ID gesetzt | Erforderlicher Anzeigename des GUI-Eintrags; wird auch für Reset-Nachrichten verwendet. |
| `icon` | je ID gesetzt | Bukkit-`Material`, das ein Item sein muss. |
| `slot` | `11`, `13`, `15` | Inhalts-Slot `0` bis `26`; das Menü selbst hat 45 Slots. |
| `lore` | je ID gesetzt | Optionale String-Liste unter dem Icon; Reset-Anzeige und Teleport-Hinweis werden automatisch darunter ergänzt. |
| `reset` | je ID gesetzt | Reset-Plan; siehe nächster Abschnitt. |
| `teleport` | je ID gesetzt | Befehlsbasierte Teleportaktion. |

Ein aktivierter GUI-Eintrag ohne gültigen Anzeigenamen, Item-Icon, Inhalts-Slot oder Teleportbefehl wird beim Laden übersprungen. GUI-Einträge dürfen eigene IDs haben; Reset-Pläne werden dagegen nur für `overworld`, `nether` und `end` geladen.

Beim Öffnen von `/farmwelt` erhält jedes Welt-Item unter seiner konfigurierten Beschreibung den veröffentlichten nächsten Reset-Termin und die ungefähre Restzeit, beispielsweise:

```text
Nächster Reset: 09.09.2026, 18:00 Uhr
Verbleibend: ca. 3 Tage, 4 Stunden

Klicken zum Teleportieren
```

Datum und Uhrzeit verwenden dieselbe Server-Zeitzone wie `/farmwelt status` und die Reset-Nachrichten. Der Termin stammt aus dem gespeicherten Zeitplan; eine Intervalländerung beim Reload verschiebt ihn nicht. Es ist der geplante Termin, den der Scheduler im 60-Sekunden-Takt prüft. Ein geöffnetes Menü bleibt eine Momentaufnahme; erneutes Öffnen aktualisiert Datum, Restzeit und Status. Die Anzeige benötigt nur `farmwelt.use`, keine Admin-Permission und keine zusätzlichen Config-Schlüssel. Sie ist auch bei ausgeschalteten Reset-Notifications sichtbar.

Die beiden Reset-Zeilen sind normalerweise grau, bei höchstens einer Stunde Restzeit gelb und bei höchstens fünf Minuten rot. Unter einer Minute steht `Verbleibend: unter 1 Minute`. Sonderzustände ersetzen Datum und Restzeit:

| Zustand | Anzeige |
| --- | --- |
| Reset läuft, auch nach zwischenzeitlicher Deaktivierung beim Reload | `Reset läuft` und `Teleport vorübergehend gesperrt`; kein Klickhinweis. |
| Termin erreicht oder überschritten, Reset läuft noch nicht | `Reset fällig`; die bestehende Teleport-Sperre greift erst bei einem laufenden Reset. |
| Reset-Konfiguration deaktiviert | `Automatischer Reset deaktiviert`; ein historischer Termin wird nicht angeboten. |
| Kein nutzbarer Zeitplan, etwa bei eigener GUI-ID ohne Reset-Konfiguration | `Termin nicht verfügbar`. |

Der Info-Kompass erklärt zusätzlich, dass beim Erneuern der Farmwelten dort platzierte Blöcke und gelagerte Items verloren gehen, und verweist auf den Termin beim jeweiligen Welt-Item.

### Reset-Plan

Unter `farmworlds` erhält jede logische ID genau einen geschachtelten YAML-Abschnitt. Das gilt auch für die ID unter `worlds` im Zeitplan. In einzelnen Schlüssel geschriebenen Punktpfaden wie `worlds.OVERWORLD.next-reset` lässt sich die Abschnittsgrenze nicht eindeutig entnehmen. Groß- und Kleinschreibung ist bei `overworld`, `nether` und `end` frei. Menü, Status, Countdown und Force verwenden dieselbe ID. Der Anzeigename bleibt frei wählbar. Bei `reset.world` zählt die genaue Schreibweise des registrierten Bukkit-Namens.

Zwei Abschnitte für dieselbe ID oder zwei Pläne mit demselben Zielnamen führen zur Ablehnung der gesamten neuen Reset-Konfiguration. Das gilt auch bei deaktivierten Einträgen und bei Zielnamen wie `Farm` und `farm`. Beim Reload läuft der bisherige gültige Plan weiter. Beim ersten Start verhindert der Fehler den Start des Reset-Systems. Die Fehlermeldung nennt die betroffenen Einträge.

Ein laufender Reset reserviert seine ID und den ursprünglichen Zielnamen bis zum Abschluss der Bereinigung. Umbenennen, Entfernen, Deaktivieren oder Neuzuordnen im Reload gibt diese Reservierung nicht frei. Unabhängige Ziele können parallel zurückgesetzt werden.

Pfad: `farmworlds.<id>.reset`

| Schlüssel | Auslieferung | Vertrag |
| --- | --- | --- |
| `enabled` | `true` | Wirksam nur zusammen mit `farmworlds.<id>.enabled: true`; Parser-Fallback ist `false`. |
| `world` | `farmwelt`, `netherfarm`, `endfarm` | Erforderlicher tatsächlicher Bukkit-Weltname. |
| `interval` | `30d`, `30d`, `60d` | Positive Ganzzahl mit `m`, `h` oder `d`; keine Dezimal-, Cron- oder ISO-Duration. |
| `notifications` | vorhanden | Countdown-, Lifecycle- und Evakuierungsmeldungen. |
| `post-reset` | vorhanden | Optionale Gamerules, WorldBorder und End-Policy. Fehlt der Bereich, verändert Farmwelt diese Werte nicht. |

Ein Reset-Plan ohne gültige ID, Weltname, Intervall oder Post-Reset-Konfiguration wird sicher nicht in den Laufzeit-Snapshot übernommen. `force` kann einen solchen oder deaktivierten Plan nicht umgehen.

### Reset-Notifications

Pfad: `farmworlds.<id>.reset.notifications`

| Schlüssel | Auslieferung und Parser-Default | Wirkung |
| --- | --- | --- |
| `enabled` | `true` | Hauptschalter für sämtliche Reset-Nachrichten dieser Farmwelt. |
| `warnings` | `1h`, `30m`, `10m`, `5m`, `1m` | Countdown-Schwellen automatischer Resets. Eine leere Liste deaktiviert nur Countdowns. |
| `warning-message` | siehe Default-Config | Globaler Countdown-Text. |
| `reset-start.enabled` | `true` | Globale Meldung nach akzeptiertem Pipeline-Start. |
| `reset-start.message` | siehe Default-Config | Text der Startmeldung. |
| `reset-success.enabled` | `true` | Globale Meldung ausschließlich nach vollständigem `SUCCESS`. |
| `reset-success.message` | siehe Default-Config | Text der Erfolgsmeldung. |
| `reset-failure.enabled` | `false` | Optionale globale Meldung für einen tatsächlich gestarteten, fehlgeschlagenen Reset. |
| `reset-failure.message` | siehe Default-Config | Bewusst allgemeiner Fehlertext ohne technische Details. |
| `evacuation.enabled` | `true` | Persönliche Meldung nach bestätigtem erfolgreichem Evakuierungsteleport. |
| `evacuation.message` | siehe Default-Config | Text der persönlichen Evakuierungsmeldung. |

Warning-Dauern verwenden dieselbe `m`-/`h`-/`d`-Syntax wie Reset-Intervalle. Ungültige Listeneinträge werden einzeln ignoriert, Duplikate entfernt und gültige Schwellen absteigend sortiert. Fehlende oder ungültige Nachrichtentexte fallen auf die ausgelieferten Standardtexte zurück.

Reset-Notifications kennen ausschließlich diese Platzhalter:

| Platzhalter | Verwendbar in | Wert |
| --- | --- | --- |
| `{world}` | alle Reset-Nachrichten | `display-name`, nicht Farmwelt-ID oder Bukkit-Weltname |
| `{time}` | `warning-message` | konfigurierte Countdown-Dauer in deutscher Schreibweise |
| `{next-reset}` | alle Reset-Nachrichten | veröffentlichter Termin im lokalen Format `dd.MM.yyyy HH:mm`; sonst `unbekannt` |

Klassische `&`-Farbcodes werden unterstützt. Countdown-Warnungen gelten nur für automatische Pläne, nicht für Force-Resets. Jede Schwelle wird pro `nextReset`-Termin höchstens einmal verwendet; nach einem Neustart oder einer relevanten Reload-Änderung wird höchstens die zeitlich nächste bereits erreichte Schwelle nachgeholt, niemals eine Serie alter Meldungen.

Start-, Erfolgs-, Fehler- und Evakuierungsmeldungen laufen bei automatischen, beim Start nachgeholten und manuellen Resets über denselben Engine-Pfad. Abgewiesene Aufrufe wie `DISABLED` oder `ALREADY_RUNNING` erzeugen keine Lifecycle-Meldung. Die Erfolgsmeldung erscheint erst nach erfolgreicher State-Persistenz; `STATE_SAVE_FAILED` erzeugt bei aktivem Einzelschalter eine Fehler-, aber keine Erfolgsmeldung. Die Evakuierungsmeldung ist kein Broadcast und geht pro Reset genau einmal an jeden Spieler, dessen Teleport aus der Zielwelt bestätigt wurde; ein Disconnect kann ihre Zustellung entfallen lassen.

Alle Notification-Typen sind Best-Effort: Formatter-, Scheduler- oder Versandfehler werden geloggt, dürfen aber Evakuierung und sicheren Reset weder abbrechen noch in einen anderen `ResetResult` umwandeln.

### Post-Reset

Pfad: `farmworlds.<id>.reset.post-reset`

```yaml
post-reset:
  gamerules:
    players_sleeping_percentage: 50
    show_advancement_messages: false
  world-border:
    size: 20000
  end:
    dragon: false
```

- `gamerules` nimmt skalare Werte entgegen. Namen werden über die Bukkit-Registry aufgelöst und Werte passend zum tatsächlichen Boolean- oder Integer-Typ gesetzt. Unbekannte Regeln und unpassende Werte lassen die Post-Reset-Initialisierung fehlschlagen.
- `world-border.size` muss eine endliche Zahl von mindestens `1` sein.
- `end.dragon` ist ein Boolean und wird nur für die logische End-Farmwelt berücksichtigt.
- Fehlende Unterbereiche verändern die jeweilige Einstellung nicht.

Die ausgelieferte Config setzt `show_advancement_messages: false` in allen drei Farmwelten, zusätzlich `players_sleeping_percentage: 50` in der Overworld, eine Border-Größe von `20000` und für die Endfarm `dragon: false`.

### Teleport

Pfad: `farmworlds.<id>.teleport`

| Schlüssel | Vertrag |
| --- | --- |
| `type` | Nur `command` wird unterstützt. |
| `sender` | `player` oder `console`; Fallback ist `player`. |
| `command` | Erforderlicher Befehl; ein führender Slash wird entfernt. |

Teleport-Platzhalter sind `{player}`, `{id}`, `{world}` und `{display-name}`. `{id}` ist die Config-ID; `{world}` und `{display-name}` sind beide der GUI-Anzeigename. Einen technischen Zielweltnamen deshalb direkt in den Befehl schreiben. Farmwelt implementiert keine eigene Random-Teleport-Logik.

Vor der Planung und nochmals im Entity-Kontext des Spielers wird geprüft, ob die logische Farmwelt gerade zurückgesetzt wird. Während des Locks wird kein Teleportbefehl ausgeführt. Befehle mit `sender: console` werden zur eigentlichen Ausführung Folia-sicher auf den Global-Region-Scheduler übergeben.

### Ressourcenmonitor

Pfad: `resource-monitor`

| Schlüssel | Auslieferung | Vertrag |
| --- | --- | --- |
| `enabled` | `true` | Hauptschalter; Parser-Fallback ohne Schlüssel ist `false`. |
| `mode` | `audit` | Gültig sind `audit`, `warn`, `enforce`. |
| `monitored-worlds` | `world`, `world_nether`, `world_the_end` | Nur diese Bukkit-Weltnamen werden geprüft. |
| `ignored-worlds` | `farmwelt`, `netherfarm`, `endfarm` | Diese Welten bleiben auch bei Überschneidung ausgenommen. |
| `bypass-permission` | `farmwelt.bypass` | Permission für vollständigen Monitor-Bypass. |
| `notify-permission` | `farmwelt.notify` | Empfänger der Staff-Meldungen. |
| `violation-window-seconds` | `600` | Fenster des normalen Verstoßzählers; mindestens eine Sekunde. |

Modi:

- `audit` beobachtet, loggt und kann Staff mit eigenem Cooldown informieren. Es warnt oder blockiert Spieler nicht.
- `warn` zählt Verstöße und führt `actions.warning` sowie `actions.notify-staff` ab Schwelle und Cooldown aus.
- `enforce` ergänzt den sichtbaren Blockabbruch ab `cancel-break.after-blocks`, schützt konfigurierte Item-Frame-Loots und entfernt erkannte Ressourcen aus Explosions-Blocklisten. Die Explosion selbst wird nicht abgebrochen.

`resource-monitor.audit` enthält `notify-staff: true`, `log-to-console: true`, `staff-message` und `log-cooldown-seconds: 120`. Audit-Notify und `actions.notify-staff` sind getrennte Mechanismen.

Unter `resource-monitor.actions` werden ausgeliefert:

| Bereich | `enabled` | Schwelle | Cooldown | Inhalt |
| --- | --- | --- | --- | --- |
| `warning` | `true` | `after-blocks: 5` | `cooldown-seconds: 60` | `message` an Spieler |
| `notify-staff` | `true` | `after-blocks: 10` | `cooldown-seconds: 60` | `message` an Notify-Permission |
| `cancel-break` | `true` | `after-blocks: 15` | `cooldown-seconds: 10` | `message` und `actionbar-message` |

Der Cooldown von `cancel-break` begrenzt nur die Meldung. Nach erreichter Schwelle bleibt jeder relevante Versuch im Modus `enforce` blockiert.

`resource-monitor.actions.jail` ist standardmäßig deaktiviert:

| Schlüssel | Auslieferung | Wirkung |
| --- | --- | --- |
| `enabled` | `false` | Hauptschalter der Jail-Eskalation. |
| `mode` | `notify-only` | `disabled`, `notify-only` oder `execute-command`. |
| `after-blocked-attempts` | `20` | Schwelle des getrennten `blockedCount`. |
| `cooldown-minutes` | `20` | Mindestabstand zwischen Jail-Aktionen desselben Spielers. |
| `execute-once-per-window` | `true` | Höchstens eine Jail-Aktion je Violation-Fenster. |
| `command` | `jail {player} mgpd 30min` | Konsolenbefehl für `execute-command`, ohne führenden Slash. |
| `notify-staff` | `true` | Sendet bei Auslösung eine Staff-Meldung. |
| `staff-message` | siehe Default-Config | Text für `notify-only` und `execute-command`. |
| `player-message` | siehe Default-Config | Text nach erfolgreich ausgeführtem Konsolenbefehl. |

Werden `cooldown-minutes` oder `command` entfernt, lauten die internen Parser-Fallbacks abweichend `60` beziehungsweise `jail {player} farmwelt`; für reproduzierbaren Betrieb sollten beide Schlüssel deshalb explizit gesetzt bleiben.

Jail basiert ausschließlich auf `blockedCount`, also tatsächlich durch `enforce` abgebrochenen Versuchen. Warning, Staff-Notify und Blockierschwelle basieren auf dem normalen Verstoßzähler. Es gibt keinen Kick-Mechanismus. Bei `execute-command` läuft der Befehl als Konsole; die anschließende Spielernachricht wird im Entity-Kontext zugestellt.

Nachrichten des Ressourcenmonitors und der Jail-Stufe unterstützen die in der Default-Config verwendeten Platzhalter `{player}`, `{uuid}`, `{world}`, `{x}`, `{y}`, `{z}`, `{block}`, `{category}`, `{count}`, `{blocked-count}` und `{window-seconds}`.

### Weltregeln und Claims

Pfad: `resource-monitor.world-rules.<Bukkit-Weltname>`

| Schlüssel | Vertrag |
| --- | --- |
| `type` | `overworld`, `nether` oder `end` |
| `resources` | Liste gültiger Block-`Material`-Namen; nur diese Blöcke werden erkannt. |
| `protected-items` | Optionale Item-`Material`-Liste, standardmäßig `ELYTRA` für Item Frames im End. |

Es gibt bewusst keine Höhenprüfung. Die Default-Config enthält breite, versionssensitive Ressourcenlisten; ihre vollständige Liste wird hier nicht dupliziert. Die alten Overworld-Schlüssel `surface-resources` und `underground-resources` werden nur noch als Kompatibilitätsfallback zu einer gemeinsamen Ressourcenliste zusammengeführt; neue Konfigurationen sollen `resources` verwenden.

Pfad: `resource-monitor.claim-protection`

| Schlüssel | Auslieferung | Vertrag |
| --- | --- | --- |
| `enabled` | `true` | Aktiviert die Claim-Prüfung. |
| `provider` | `GriefPrevention` | Einziger unterstützter Provider. |
| `skip-inside-claims` | `true` | Ignoriert Ressourcen-, Item-Frame- und Explosionsfälle im Claim. |
| `fail-mode` | `disable-monitor` | Deaktiviert den Ressourcenmonitor sicher bei fehlendem/unbrauchbarem Provider. |
| `ignore-height` | `true` | Wird an die GriefPrevention-Abfrage weitergegeben. |

Bei Events zählt die Position des betroffenen Blocks oder Entities, nicht die Spielerposition. Nur `/farmwelt debug claim` prüft absichtlich die aktuelle Spielerposition.

## Reset-Betrieb

Phase 2.2 verwendet Java 25, Folia 26.3 Build 26 und Worlds 4.5.1. Für die Prüfung von Overworld und Nether ausschließlich private Wegwerfwelten auf einem isolierten Runner verwenden. Der Serverbenutzer darf keine produktiven Daten oder Backups erreichen. Vor jedem destruktiven Versuch die Weltpfade einschließlich aller Vorfahren und Unterverzeichnisse auf Umleitungen prüfen. FW-004 und die ausstehende Produktionsfreigabe sind im [Migrationsbericht](testing/phase-2.2.md) dokumentiert.

### Statuswerte

Der Statusbefehl zeigt ausschließlich tatsächlich implementierte Zustände:

| Anzeige | Bedeutung |
| --- | --- |
| `Recovery-Sperre` | Automatik und Force warten auf die Klärung eines früheren Versuchs. Status und Log nennen die letzte belegte Phase oder den Lesefehler. |
| `Geplant` | Aktiver State mit zukünftigem `nextReset`. |
| `Überfällig` | `nextReset` ist erreicht oder liegt in der Vergangenheit. |
| `Läuft` | Für diese logische Farmwelt ist bereits eine Reset-Pipeline aktiv; dieser Status hat Vorrang. |
| `Deaktiviert` | Der konfigurierte Reset-Plan ist ausgeschaltet. |
| `Kein Zeitplan` | Aktivierter Plan ohne nutzbaren State. |

Bei deaktivierten Plänen oder fehlendem State zeigt `Verbleibend` `-`. Der Statusbefehl verändert den State nicht.

### Manueller Force-Reset

`/farmwelt reset force <welt>` überspringt ausschließlich einen zukünftigen Termin. Folgende Verträge bleiben aktiv:

- Farmwelt- und Reset-Schalter müssen aktiviert sein.
- Weltname, Dimension, geladene Instanz und Hauptweltschutz werden geprüft.
- Pro logischer Farmwelt läuft höchstens ein Reset gleichzeitig.
- Spieler werden in eine sichere Overworld evakuiert; anschließend muss die Zielwelt leer sein.
- Teleports in die gelockte Farmwelt bleiben bis zum Abschluss blockiert.
- Worlds regeneriert die Welt; anschließend werden neue Bukkit-Weltinstanz und Post-Reset-Zustand geprüft.
- Erst nach erfolgreicher State-Persistenz lautet das Ergebnis `SUCCESS`.

`--dragon` ist ausschließlich für die logische ID `end` zulässig und ändert `config.yml` nicht. Der Override gilt nur für diesen Reset.

### Automatischer Scheduler

Farmwelt verwendet den persistenten `nextReset`-Termin. Nach abgeschlossenem Startup-Catch-up prüft ein globaler Folia-Task alle 60 Sekunden zuerst Countdown-Warnungen und danach die Fälligkeit:

- Vor `nextReset` startet kein automatischer Reset.
- Bei `now >= nextReset` wird die normale Pipeline ohne Dragon-Override angestoßen.
- Der Scheduler blockiert nicht auf Reset-Futures und besitzt keinen zweiten Lock.
- Kehrt während der Journal-Sicherung ein Spieler zurück oder hat sich die Zielwelt geändert, bricht die letzte Prüfung vor Worlds ab. Nach erfolgreicher Rücknahme des vorbereiteten Versuchs gilt der bisherige Zeitplan. Scheitert die Rücknahme, zeigt der Status die Recovery-Sperre. Bei einem Neustart bleibt ein vorgefundenes `WORLDS_PENDING` gesperrt.
- Ein neuer `lastReset`/`nextReset` wird nur nach Regeneration, Validierung, Post-Reset und erfolgreichem Speichern veröffentlicht.

### Startup-Catch-up

Nach dem vollständigen Pluginstart wartet Farmwelt einmalig 60 Sekunden. Danach werden aktuell fällige Welten in stabiler Config-Reihenfolge zunächst nacheinander verarbeitet. Vor jeder Welt wird erneut geprüft, ob sie noch fällig ist. Nach einem bestätigten Fehler setzt die Nachholung bei der nächsten Farmwelt an.

Ein sehr alter Termin erzeugt keinen Reset pro verpasstem Intervall. Es gibt während des Startup-Catch-up höchstens einen Versuch je gefundener fälliger Farmwelt. Nach der Sequenz übernimmt genau ein regulärer 60-Sekunden-Scheduler.

**Wenn eine Farmwelt länger wartet.** Pro ausstehendem Startup-Versuch plant Farmwelt eine Beobachtung nach 12 000 globalen Ticks (bei 20 TPS zehn Minuten). Danach laufen Nachholung und periodische Fälligkeitsprüfung weiter. Eine überschrittene Beobachtungsfrist beendet keine Worlds-Regeneration und gibt keine Reset-Reservierung frei.

Bei einem ausstehenden Teleport kann der Nether bereits nachgeholt werden. Wartet der Startup-Versuch auf Worlds, bleiben weitere Regenerationen zurückgestellt. Der Scheduler bewertet den Nether alle 60 Sekunden und holt ihn nach, sobald der Konflikt entfällt. Auch eine außerhalb des Starts ungewöhnlich lange laufende Worlds-Übergabe schützt Farmwelt so.

Mit `/farmwelt status <welt>` Ziel, Versuch, Laufzeit, Phase und Recovery-Meldung prüfen. Die Konsole warnt einmal pro ungewöhnlich langem Versuch. Bei einem extern abgebrochenen Teleport-Future oder fehlendem Rückgabewert ist das Teleportende ungeklärt. Das Ziel bleibt dann bis zum Serverneustart reserviert. Ein offenes Worlds-Journal überlebt den Neustart.

Beim Stop verwirft Farmwelt noch wartende eigene Aufgaben. Begonnene Dateioperationen dürfen ihren Beleg fertigschreiben. Meldet Worlds erst nach der Deaktivierung Erfolg, entfällt der Post-Reset und der letzte dauerhafte Journalstand entscheidet beim nächsten Start.

Meldet der Status **„Worlds-Abschluss ungeklärt“**, hat Farmwelt den Abschluss einer bereits übernommenen Regeneration verloren. Weitere Worlds-Übergaben warten dann sofort, auch für andere Welten und bei Force oder Reload. Den Server vollständig stoppen und Journal, Zeitplan, Serverlog und Weltstand nach dem Recovery-Verfahren unten prüfen. Beim nächsten Serverstart schützt das offene Journal weiter die betroffene Farmwelt. Meldet Worlds einen abgeschlossenen Fehler, etwa ein abgelehntes Regenerate-Event, können unabhängige Farmwelten sofort weiterlaufen.

### Reload

`/farmwelt reload` validiert die YAML-Datei zuerst und lädt anschließend GUI, Reset-Konfiguration, Notifications, Post-Reset-/Dragon-Guards, Ressourcenmonitor, Claim-Hook und Violation-Schwellen neu.

- Ein vorhandener `nextReset` bleibt unverändert.
- Eine Intervalländerung verschiebt den bestehenden Termin nicht rückwirkend.
- Das neue Intervall gilt für den Folgetermin nach dem nächsten erfolgreichen Reset.
- Ein laufender Reset behält seinen beim Start erfassten unveränderlichen Config-Snapshot, einschließlich Intervall, Notifications und Post-Reset-Policy.
- Laufende Locks und Worlds-Futures bleiben erhalten; die Pipeline wird nicht per Cancel oder Interrupt abgebrochen.
- Zukünftige Scheduler- und Command-Entscheidungen verwenden die neue Config.
- Reload startet keine zweite Startup-Nachholung und keinen zweiten periodischen Scheduler.

## `reset-state.yml`

`plugins/Farmwelt/reset-state.yml` ist Runtime-State und kein zweiter Config-Bereich. Das Plugin verwaltet dort den persistenten Zeitplan in ISO-8601-Instants:

```yaml
version: 1
worlds:
  overworld:
    last-reset: "2026-08-20T12:00:00Z"
    next-reset: "2026-09-19T12:00:00Z"
```

Bei einem noch nie erfolgreich zurückgesetzten Plan kann `last-reset` fehlen. Intern heißen die Werte `lastReset` und `nextReset`; in YAML heißen sie `last-reset` und `next-reset`.

- Ein bestehender `nextReset` überlebt Serverneustart und Reload.
- Eine Intervalländerung berechnet den aktuellen Termin nicht neu.
- Nach vollständigem Erfolg werden `last-reset` und der neue `next-reset` anhand des Config-Snapshots dieses Resets gemeinsam gespeichert.
- Bei jedem Resetfehler bleibt der bestehende veröffentlichte State erhalten.
- Bei `STATE_SAVE_FAILED` sind Regeneration und Initialisierung bestätigt. Der zuvor veröffentlichte Plan bleibt im Speicher. Beim Neustart prüft Farmwelt, ob der Dateiersatz bereits einen passenden vollständigen Abschlussbeleg hinterlassen hat.
- Fehlende States aktivierter, gültiger Pläne werden nur bei freiem Recovery-Platz mit `now + interval` initialisiert.

Die Datei niemals während eines laufenden Servers manuell bearbeiten. Für isolierte Tests nur bei vollständig gestopptem Server, nach Backup und ausschließlich am gezielt benötigten Weltabschnitt arbeiten. Die Datei nicht pauschal löschen: Dadurch gingen die persistenten Termine aller Farmwelten verloren und würden neu initialisiert.

### Recovery nach einem unterbrochenen Reset

Ein einzelner älterer Abschnitt wie `worlds.OVERWORLD` gilt für `overworld`. Der nächste reguläre Schreibvorgang speichert eindeutige IDs kleingeschrieben. Termine und Versuchs-UUID bleiben erhalten. Ein Journal mit `key=OVERWORLD` kann den Abschluss mit demselben Beleg bestätigen. Dazu müssen Phase, Versuchs-UUID, Abschlusszeit und Folgetermin übereinstimmen. Eine Migration der Journaldateien ist dafür entbehrlich.

Bei mehreren Zeitplanabschnitten derselben ID zeigt der Status eine Recovery-Sperre. Farmwelt bewahrt sämtliche betroffenen Einträge auch beim Speichern gesunder Welten auf. Zur Klärung den Server stoppen und den unten beschriebenen Sicherungssatz anlegen. Anhand von Journal, Logs und Sicherung den gültigen Beleg bestimmen und genau einen passenden Abschnitt wiederherstellen. Die Reihenfolge der Abschnitte und das Alter eines Termins sind keine Abschlussbelege. Bei einem mehrfachen übergeordneten `worlds`-Bereich ist die Zuordnung der gesamten Datei ungeklärt.

**Ein ungeklärter Reset-Zustand ist ein Sicherheitszustand. Er bleibt über das nächste Zeitintervall, Reload und Serverneustart hinweg gesperrt.** Ein Force-Aufruf respektiert die Sperre ebenfalls. Andere Farmwelten mit eigenem, eindeutigem Zustand können ihren Plan abarbeiten, sobald die Laufzeitkoordination weitere Worlds-Übergaben zulässt. Die GUI zeigt „Reset bis zur Klärung gesperrt“. Der Spielerteleport folgt weiterhin dem Laufzeit-Lock.

1. Mit `/farmwelt status <welt>` logische ID, Bukkit-Name, UUID, Startzeit und letzte bestätigte Phase festhalten. Bei unlesbarem Journal kann nur der betroffene Platz und der Lesefehler angezeigt werden. Im Log die erste Fehlermeldung zu dieser Welt sichern.
2. Den Reset-Plan der betroffenen Welt für die Wartung deaktivieren. Vor Dateiarbeiten den Server vollständig stoppen und Weltdaten, Worlds-Metadaten, Config, Log, `reset-state.yml`, `reset-recovery.version` und alle drei `reset-recovery-*.journal` gemeinsam sichern. Die Kontrollen an einer isolierten Kopie vornehmen.
3. Bei `WORLDS_PENDING` kann Worlds bereits entladen, gelöscht oder neu erstellt haben. Bei `WORLDS_RETURNED` hat Worlds eine neue Instanz geliefert. Ihre Validierung oder Initialisierung ist offen. Weltdaten, Dimension, Seed, Spielerfortschritt und Worlds-Log vergleichen. In beiden Fällen bleibt der Reset gesperrt, solange ein vollständiger Stand fehlt. Ein konsistentes Backup lässt sich in einer isolierten Kopie überprüfen. Eine Rücksicherung kann neueren Spielerfortschritt verwerfen und erfordert eine ausdrückliche Betreiberentscheidung.
4. Bei `POST_RESET_DONE` sind Validierung und Initialisierung bestätigt. Weltidentität, Zeitstempel, Log und Versuch zusammen prüfen. Falls ausschließlich der Zeitplan fehlt, Schreibursache beheben und bei gestopptem Server im Abschnitt `worlds.<key>` des Zeitplans **genau** `attempt-id` aus `id`, `last-reset` aus `completed` und `next-reset` aus `next` übernehmen. Alle drei Werte als Strings in Anführungszeichen eintragen. Das Journal aufbewahren. Bei abweichenden Belegen zuerst deren Herkunft klären.
5. Mit deaktiviertem Plan starten und Status, Welt und Log prüfen. Einen bereits überfälligen bestätigten Folgetermin vor dem Wiedereinschalten ausdrücklich bewerten. Bei weiterhin unklarem Stand bleibt der Plan deaktiviert. Es gibt keine automatische Wiederholung der Worlds- oder Post-Reset-Arbeiten.

**Backup und Versionswechsel.** Zum konsistenten Sicherungssatz gehören Welt und beide Persistenzarten. Beim ersten Start dieser Version legt Farmwelt den gemeinsamen Versionsmarker und drei freie Journale an. Vorhandene gültige Zeitpläne behalten ihre Termine. Nach dem ersten erfolgreichen Reset enthält der betreffende Zeitplan zusätzlich die Versuchs-ID. Ein Journal bleibt bis zum nächsten beabsichtigten Reset erhalten. Einzeln fehlende Journale, unbekannte Versionen und widersprüchliche Belege erfordern eine Überprüfung. Eine ältere Plugin-Version kennt die Sperre nicht. Vor einem Downgrade alle Resets deaktivieren, offene Versuche klären und eine konsistente Sicherung erstellen. Ein Downgrade ist kein Wiederherstellungsweg.

## Endfarm-Sonderlogik

Für die internen DragonBattle-Zugriffe ist ausschließlich Minecraft 26.1.2 freigegeben. Bei einer anderen Version endet ein Reset mit wirksamer Dragon-Policy bereits vor Evakuierung und Regeneration mit `UNSUPPORTED_DRAGON_VERSION`. Befehl und Serverlog nennen die erkannte und die freigegebene Version. Ein fälliger Termin bleibt bei Ablehnung fällig.

Fehlt `post-reset.end`, überlässt ein regulärer End-Reset den Kampf Vanilla. Ein ausdrückliches `--dragon` bereitet in diesem Fall auf der freigegebenen Version den Erstkampf in der neu geladenen Welt vor. Die DragonBattle-Datei wird dafür beim Entladen nicht ersetzt.

Auch ohne vorherigen Reset prüfen Spawn-Guard und Portalreparatur die konkrete geladene Endfarm. Die erste geladene Welt und die drei Vanilla-Dimensionskeys sind geschützt. Wird die End-Policy vor einer geplanten Portalreparatur geändert oder die Welt ersetzt, verfällt die Reparatur.

### `dragon: false`

- Direkt nach dem Reset existiert kein Enderdrache.
- Es bleibt keine aktive Bossbar.
- Das Exit-Portal ist aktiv und benutzbar; der Fight-State gilt als abgeschlossen.
- Verzögerte Vanilla-Erstspawns werden unterdrückt.
- Ein echter Vanilla-Respawn mit vier Endkristallen bleibt möglich und zählt als Wiederbeschwörung, nicht als Erstkampf.
- Nach dem Tod dieses Respawn-Drachens stellt Farmwelt das aktive Portal in der End-Ursprungsregion erneut sicher.

### `dragon: true` oder `--dragon`

- Ein frischer Vanilla-Erstkampf wird vorbereitet.
- Bossbar und Drache erscheinen wie beim Erstkampf.
- Das Exit-Portal ist zunächst inaktiv und nach dem Kampf aktiv.
- Das Drachenei-Erstkampfverhalten bleibt erhalten.
- `--dragon` verändert `dragon: false` in der Config nicht dauerhaft.
- Bei konfiguriertem `dragon: false` bleibt die einmalige Spawn-Freigabe nur bis zum tatsächlichen Vanilla-Spawn bestehen; danach greift wieder die konfigurierte Suppression.

Eine Freigabe für Folia 26.3 setzt die Prüfung der internen Zugriffe und die reale Abnahme beider Varianten einschließlich Bossbar, Portal, Kristall-Respawn und Drachenei voraus.

## Betrieb des Ressourcenmonitors

Empfohlene Einführung:

1. Mit `audit` echte Welt-, Material- und Claim-Fälle beobachten.
2. Breite Standardlisten an die Serverregeln anpassen und False Positives beseitigen.
3. Auf `warn` wechseln und Warning-/Staff-Schwellen beobachten.
4. Erst danach `enforce` einschließlich Item-Frame- und Explosionsschutz testen.
5. Jail bis zu einer eigenen Abnahme deaktiviert lassen.

Violation-, `blockedCount`-, Audit-Cooldown- und Monitor-Debug-Daten sind flüchtig und werden nicht in `reset-state.yml` oder einer Datenbank gespeichert.

## Diagnose und Wartung

Nach Installation oder Reload:

1. `/farmwelt info`
2. `/farmwelt status`
3. Als Spieler `/farmwelt` und einen Teleport testen.
4. `/farmwelt debug claim` innerhalb und außerhalb eines Claims testen.
5. `/farmwelt debug monitor` aktivieren und konfigurierte Ressourcen per Rechtsklick prüfen.
6. Serverlog auf Config-, Worlds-, Folia- und Claim-Hook-Fehler prüfen.

Typische Ursachen:

- Fehlender GUI-Eintrag: `enabled`, `display-name`, `icon`, Inhalts-`slot` und `teleport` prüfen.
- Reset-ID fehlt im Status: Nur `overworld`, `nether` und `end` sind Reset-IDs; `world`, `interval` und `post-reset` müssen valide sein.
- Teleport funktioniert nicht: Sender, Befehl, BetterRTP-Installation und Zielweltnamen prüfen.
- Monitor ist trotz `enabled: true` aus: Modus und `claim-protection.fail-mode` sowie GriefPrevention-Hook prüfen.
- Bei `Überfällig` das Reset-Ergebnis und das Log prüfen. Bei `Recovery-Sperre` sind auch spätere Ticks gesperrt.
- `STATE_SAVE_FAILED` führt zur Prüfung des Abschlussbelegs nach dem oben beschriebenen Recovery-Ablauf.

## Tests und Freigabe

Vor produktiver Aktivierung mindestens Gradle-Tests und Build ausführen. Der echte automatisierte Folia-/Worlds-Smoke-Test ist unter [`testing/blackbox/`](../testing/blackbox/README.md) dokumentiert und läuft in einem eigenen GitHub-Actions-Workflow. Er prüft eine reale Worlds-Regeneration, ersetzt aber nicht die vollständige manuelle [V2-Abnahme](testing/black-box-testing.md) mit der [BB-01-bis-BB-26-Abnahmevorlage](testing/v2-acceptance-template.md).
