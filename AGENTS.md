# Entwicklungsregeln

## Projektziel und Plattform

Dieses Repository enthält das Minecraft-Farmwelt-Plugin für Paper/Folia. Es führt Spieler über eine GUI in konfigurierbare Farmwelten, überwacht Ressourcenabbau in normalen Welten, unterstützt Moderation und Diagnose und setzt Overworld-, Nether- und End-Farmwelten sicher zurück. Es ist kein allgemeines Anti-Grief- oder Claim-Plugin.

Zielplattform sind aktuell Minecraft/Folia 26.1.2 und Java 25. Die technische Source of Truth für API-, Toolchain- und Dependency-Versionen ist `build.gradle.kts`; `paper-plugin.yml` definiert Plugin-Metadaten, Runtime-Dependencies und die eingebauten Permission-Defaults. Versionsangaben hier nur als Orientierung behandeln und bei Upgrades mit dem Build abgleichen.

## Architekturgrenzen

- `FarmweltPlugin` ist der Composition Root und verbindet Config, Commands, GUI, Listener, Services und Reset-Komponenten.
- `ConfigManager` liest und validiert Config einmal beim Start oder Reload. Keine YAML-Lookups in häufigen Event-Pfaden ergänzen.
- `command/` enthält `/farmwelt`, Admin-Routing und formatierte Antworten; `gui/` und `FarmweltGuiListener` besitzen das Inventory-Verhalten.
- `listener/`, `service/`, `claim/` und `model/` bilden Ressourcenmonitor, Claim-Hook, Meldungen, Violations und Jail-Eskalation ab.
- `reset/` besitzt Reset-Orchestrierung, Worlds-Adapter, Persistenz, Folia-Scheduler und Post-Reset-/Endfarm-Logik.
- Benutzer- und Betriebsdokumentation stehen in `README.md` und `docs/`; `AGENTS.md` hält die Entwicklungsverträge fest und ersetzt diese Dokumente nicht.

## Farmwelt-GUI und Teleport

- `/farmwelt` ist ein Spielerbefehl mit `farmwelt.use` und öffnet das konfigurierbare 45-Slot-Menü. Nur aktivierte, valide Einträge mit Anzeigename, Item-Icon, Inhalts-Slot und Teleport-Aktion erscheinen.
- GUI-Klicks und Drags bleiben gesperrt, damit keine Menüitems entnommen werden. Info- und Schließen-Item sind Teil des bestehenden Menüs.
- Farmwelt implementiert keine eigene Random-Teleport-Logik. Unterstützt wird der Config-Typ `command`, ausgeführt als `player` oder `console`; BetterRTP ist lediglich der optionale Standardbefehl.
- Teleport-Platzhalter `{player}`, `{world}`, `{display-name}` und `{id}` erhalten. Führende Slashes werden normalisiert.
- Vor dem Scheduling und nochmals im Spieler-Kontext prüfen, ob für die logische Farmwelt ein Reset läuft. Währenddessen darf kein GUI-Teleport in diese Welt starten.
- Logische Farmwelt-ID, GUI-Anzeigename und tatsächlicher Bukkit-Weltname sind verschiedene Werte und dürfen nicht vermischt werden.

## Ressourcenmonitor

- Der Monitor ist konfigurations- und eventgetrieben. Ein Treffer ist nur relevant, wenn der Monitor aktiv und sein Modus gültig ist, die Welt überwacht und nicht ignoriert wird, eine Weltregel existiert, kein Bypass greift und keine konfigurierte Claim-Ausnahme greift.
- `audit` beobachtet und kann Konsole/Staff mit eigenem Cooldown informieren, warnt oder blockiert Spieler aber nicht. `warn` zählt Verstöße und führt Warning-/Staff-Actions ab Schwellen und Cooldowns aus. `enforce` ergänzt sichtbaren Abbruch und optionale Jail-Eskalation.
- Weltregeln für `overworld`, `nether` und `end` erkennen ausschließlich explizit konfigurierte `resources`; es gibt bewusst keine Höhenprüfung. Die breiten Standard-Materiallisten sind ein versionssensitiver Server-Default und dürfen nicht beiläufig verkleinert werden.
- `protected-items` schützt Item-Frame-Loot wie Elytren. In `enforce` wird ein erkannter direkter Zugriff bei aktivem `cancel-break` sofort abgebrochen; passende Hanging-Break-Fälle bleiben ebenfalls geschützt.
- In `enforce` werden konfigurierte Ressourcen aus `BlockExplodeEvent`-/`EntityExplodeEvent`-Blocklisten entfernt. Nicht die gesamte Explosion abbrechen und Claim-Ausnahmen auch hier respektieren.
- Audit-Notify und schwellenbasiertes Action-Notify sind getrennte Mechanismen. Nachrichten, Actionbar, Permissions, Platzhalter und Cooldowns nicht zusammenlegen.

## Violations und Jail

- `ViolationService` hält thread-sichere In-Memory-Datensätze pro Spieler-UUID mit Zeitfenster, aktuellem Verstoßzähler, letztem Treffer, Action-Cooldowns und separatem `blockedCount`.
- Warning, Staff-Notify und die Blockierschwelle basieren auf dem normalen Verstoßzähler. `blockedCount` steigt ausschließlich, wenn `enforce` einen Abbauversuch tatsächlich abgebrochen hat.
- Jail basiert nur auf `blockedCount`, eigener Schwelle, Minuten-Cooldown und optional `execute-once-per-window`. Diese Trennung ist ein Sicherheitsvertrag.
- Jail ist standardmäßig deaktiviert. Die Modi `disabled`, `notify-only` und `execute-command` erhalten; keine härtere Sanktion oder Aktivierung als neuen Default einführen.
- Jail-Konsolenbefehle laufen über den Global-Region-Scheduler, die anschließende Spielernachricht über den Entity-Scheduler. Es gibt keinen Kick-Mechanismus.
- Violation-, Audit-Cooldown- und Monitor-Debug-Daten sind bewusst flüchtig und werden nicht in `reset-state.yml` persistiert.

## Claim-Integration

- GriefPrevention ist eine optionale Integration und wird über den vorhandenen Adapter/reflektiven Hook angesprochen. Farmwelt ersetzt GriefPrevention nicht.
- Für Ressourcen-, Item-Frame- und Explosionsprüfungen zählt die Position des betroffenen Blocks beziehungsweise Entities, nicht die Spielerposition. `/farmwelt debug claim` prüft dagegen absichtlich die aktuelle Spielerposition.
- `skip-inside-claims` und `ignore-height` erhalten. Bei aktivem `fail-mode: disable-monitor` muss ein fehlender oder nicht nutzbarer Provider den Ressourcenmonitor sicher deaktivieren.
- Keine zusätzliche harte Claim-Dependency oder einen anderen Provider ohne konkreten Auftrag einführen.

## Commands, Permissions und Reload

- Bestehende Oberfläche erhalten: `/farmwelt`, `status [welt]`, `info`, `reload`, `reset force <welt> [--dragon]`, `debug claim`, `debug monitor` und `debug violations [spieler]`.
- Permissions bleiben getrennt: `farmwelt.use`, `farmwelt.admin`, `farmwelt.admin.status`, `farmwelt.admin.reload`, `farmwelt.admin.reset`, sowie die konfigurierbaren Bypass-/Notify-Permissions. Tab-Completion darf keine unberechtigten Admin-Aktionen anbieten.
- Status, Reload und Force-Reset funktionieren auch für die Konsole; GUI und Debug-Unterbefehle benötigen einen Spieler. Asynchrone Abschlussmeldungen müssen im passenden Folia-Kontext zugestellt werden.
- `info` zeigt den operativen Zustand von Plugin, Menü, Ressourcenmonitor, Claim-Hook, BetterRTP, GriefPrevention und Jail-Modus. Debug-Werkzeuge sind Teil der Diagnoseoberfläche und dürfen nicht zu mutierenden Admin-Abkürzungen werden.
- Reload validiert YAML zuerst und lädt GUI, Reset-Konfiguration, Ressourcenmonitor, Dragon-Spawn-Guards, Claim-Hook und Violation-Schwellen neu. Bestehende Service-Instanzen, laufende Locks/Futures und immutable Config-Snapshots laufender Resets bleiben erhalten.

## Reset-Lifecycle und Persistenz

- Aktuell existieren Status/Persistenz, sichere manuelle Force-Resets und ein 60-sekündliches Folia-Grundgerüst, das Fälligkeiten nur als `NOT_DUE`, `DUE` oder `DISABLED` bewertet. Es löst noch keinen automatischen Reset aus. `force` überspringt nur den zukünftigen Termin, niemals Deaktivierung, Lock oder Sicherheitsprüfungen.
- Pro logischer Farmwelt darf höchstens ein Reset laufen. Der Lock steuert zugleich die Teleport-Verfügbarkeit und muss auf jedem Erfolgs- und Fehlerpfad freigegeben werden.
- Pipeline-Reihenfolge erhalten: Config-Snapshot/Dragon-Scope, Weltprüfung, Spielerevakuierung, erneute Leerprüfung, Worlds-Regeneration, Validierung der neuen Weltinstanz, Post-Reset-Initialisierung und erst danach State-Persistenz.
- Nur geladene, passend benannte und dimensionierte Farmwelten dürfen zurückgesetzt werden. Vanilla-Hauptdimensionen und die geschützte Hauptwelt niemals regenerieren. Spieler Folia-sicher per Entity-Scheduler und `teleportAsync` in eine sichere, nicht selbst zurückzusetzende Overworld evakuieren.
- Worlds regeneriert mit zufälligem Seed. Danach müssen neue Instanz, Bukkit-Erreichbarkeit, Name, Dimension und Hauptweltschutz erneut geprüft werden; ein zufällig identischer Seed ist nur eine Warnung.
- Post-Reset unterstützt ausschließlich konfigurierte, typisierte Bukkit-Gamerules, WorldBorder-Größe und End-Policy. Fehlende Unterbereiche verändern nichts; unbekannte oder ungültige Werte lassen die Initialisierung fehlschlagen.
- `lastReset` und `nextReset` erst nach erfolgreicher Regeneration, Validierung und Post-Reset-Initialisierung schreiben. Scheitert nur das Speichern, ist die Welt bereits erneuert, der veröffentlichte In-Memory-State bleibt aber unverändert.
- `reset-state.yml` ist die vom Reset-System geschriebene Laufzeitpersistenz: versioniertes YAML mit ISO-8601-Instants, temporärer Datei und möglichst atomarem Replace. Fehlende States werden für aktivierte Welten initialisiert; ein bestehendes `nextReset` überlebt Neustart, Reload und Intervalländerung. Das Intervall des beim Start erfassten Config-Snapshots bestimmt den Folgetermin eines laufenden Resets.

## Folia-Regeln

- Folia-Threading strikt einhalten. Bukkit-/Paper-Zugriffe auf Welten, Blöcke und Entities dürfen nicht auf ungeeigneten Threads stattfinden.
- Die vorhandenen Global-, Region-, Entity- und Async-Scheduler beziehungsweise `FarmweltScheduler`-Abstraktionen verwenden. Regionsbezogene Aktionen auf der zuständigen Region ausführen.
- `WorldsAccess.regenerate(...)` nicht zusätzlich in einen Farmwelt-Scheduler einwickeln; Worlds besitzt den vollständigen Folia-/Lifecycle-Kontext dieser Operation.
- Keine stillen Rückfälle auf klassische Bukkit-Scheduler einführen. Gemeinsam genutzte Daten aus Events, Commands und Futures thread-sicher halten.

## Externe Integrationen

- Worlds ist eine harte Runtime-Abhängigkeit und die alleinige technische Lifecycle-Grenze für dynamische Weltregeneration. Kann `WorldsAccess` nicht verbunden werden, deaktiviert sich Farmwelt.
- Keinen Bukkit-Fallback zum Entladen, Löschen, Erzeugen oder Regenerieren von Welten ergänzen, sofern dies nicht ausdrücklich beauftragt ist.
- BetterRTP ist optional, wird nicht per API angesprochen und kann durch andere konfigurierte Teleportbefehle ersetzt werden.
- GriefPrevention ist optional und ausschließlich für Claim-Erkennung zuständig. Dependency-Verhalten und Classpath-Regeln in `paper-plugin.yml` beachten.

## Endfarm-Sonderlogik

Die Endfarm enthält bewusst spezielle und versionssensitive Logik. Insbesondere `EndDragonFightCompatibility`, `EndDragonFightRuntimeAccess`, `EndDragonFightDataStore`, DragonBattle-State-Manipulation, Saved-Data-Staging mit Commit/Rollback, Bossbar-Suppression, Spawn-Guard, Exit-Portal-Erzeugung und -Verifikation sowie die vorhandene `--dragon`-Sonderlogik dürfen nicht ohne konkreten Auftrag vereinfacht, ersetzt oder entfernt werden.

Für `dragon: false` gilt:

- Direkt nach dem Reset existiert kein Enderdrache und keine Bossbar.
- Das Exit-Portal ist aktiv und der Fight-State gilt als abgeschlossen.
- Verzögerte Erstspawns werden verhindert; ein echter Vanilla-Respawn mit vier Endkristallen bleibt möglich und zählt als Wiederbeschwörung, nicht als Erstkampf.
- Nach dem Tod eines erlaubten Respawn-Drachens wird das aktive Portal in der End-Ursprungsregion erneut hergestellt und verifiziert.

Für `dragon: true` oder den einmaligen `--dragon`-Override gilt:

- Die bestehende Implementierung bereitet einen frischen Dragon-Fight mit aktiver Bossbar vor.
- Das Portal ist zunächst inaktiv und nach dem Kampf aktiv.
- Erstkampfverhalten einschließlich Drachenei bleibt erhalten.
- `--dragon` ändert die Config nicht. Bei sonstigem `dragon: false` bleibt die einmalige Spawn-Freigabe nur bis zum tatsächlichen Vanilla-Spawn bestehen; danach greift die konfigurierte Suppression wieder.

Die internen NBT-/CraftBukkit-Zugriffe sind explizit auf unterstützte Minecraft-Versionen begrenzt. Bei jedem Minecraft-/Folia-Upgrade Datenlayout, reflektierte Felder/Methoden, Portal- und Respawn-Verhalten prüfen und die Kompatibilitätsfreigabe bewusst aktualisieren.

## Änderungsprinzipien

- Änderungen klein und auf den beauftragten Scope begrenzt halten; keine größeren Refactorings ohne ausdrücklichen Auftrag.
- Funktionierende Sonderfälle nicht vereinfachen, nur weil sie ungewöhnlich wirken.
- Bestehende Config-Schlüssel, Defaults, Commands, Permissions, Platzhalter und Integrationsgrenzen als öffentliche Betriebsverträge behandeln.
- Bestehende Tests als Vertrag behandeln und Bugfixes mit Regressionstests absichern. Neue Listener-/Service-Funktionalität ebenfalls gezielt testen.
- Öffentliche APIs bevorzugen, vorhandene bewusst versionsgebundene interne Lösungen aber nicht ohne Not ersetzen.
- Keine spätere Phase vorwegnehmen. Phase 4, automatische Scheduler und weitere Features nur implementieren, wenn sie ausdrücklich beauftragt sind.
- Bei nutzersichtbaren Änderungen README/Admin-Guide und bei Architekturänderungen `docs/ARCHITECTURE.md` konsistent aktualisieren.

## Tests und Build

Passende gezielte Tests zuerst ausführen. Vor Abschluss einer Implementierung mindestens:

```bash
./gradlew test
./gradlew build
```

Neue Funktionalität durch Tests absichern. Für echte Folia-/Minecraft-/Worlds-Integration gilt zusätzlich die Strategie in `docs/testing/black-box-testing.md`; sie ersetzt die Gradle-Tests nicht.

## Dokumentationsstil

Kommentare erklären, warum ungewöhnliche Logik notwendig ist. Offensichtlichen Code nicht unnötig kommentieren und versionsabhängige Workarounds klar kennzeichnen. Neue Code-Kommentare und Projektdokumentation grundsätzlich auf Deutsch verfassen.
