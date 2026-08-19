# Farmwelt Architektur

Diese Datei beschreibt den aktuellen technischen Aufbau des Farmwelt-Plugins. Sie ersetzt die frühere Entwicklungsspezifikation und soll bei Wartung, Debugging und Erweiterungen als Orientierung dienen.

## Überblick

Farmwelt ist ein Paper/Folia-Plugin mit drei Hauptbereichen:

1. `/farmwelt`-GUI für Farmwelt-Teleports.
2. Ressourcenmonitor für normale Welten.
3. Administrations-, Reset- und Debug-Befehle für Betrieb und Diagnose.

Das Plugin implementiert keine eigene Random-Teleport-Logik. Teleports werden über konfigurierbare Befehle ausgeführt, typischerweise BetterRTP. Claims werden optional über GriefPrevention erkannt, damit Ressourcenabbau innerhalb von Grundstücken ignoriert werden kann.

## Modulstruktur

```text
src/main/java/de/minecraftgilde/farmwelt/
+-- FarmweltPlugin.java
+-- command/
|   +-- FarmweltCommand.java
+-- config/
|   +-- ConfigManager.java
|   +-- FarmworldResetConfigParser.java
|   +-- FarmworldResetNotificationConfigParser.java
+-- gui/
|   +-- FarmweltMenu.java
|   +-- FarmweltMenuHolder.java
|   +-- FarmweltMenuItem.java
|   +-- TeleportAction.java
+-- listener/
|   +-- FarmweltGuiListener.java
|   +-- ResourceBreakListener.java
+-- claim/
|   +-- ClaimProtectionProvider.java
|   +-- GriefPreventionClaimProtectionProvider.java
|   +-- NoopClaimProtectionProvider.java
+-- model/
|   +-- ResourceMatch.java
|   +-- ResourceWorldRule.java
|   +-- ResourceWorldType.java
|   +-- ViolationAction.java
|   +-- ViolationRecord.java
|   +-- ViolationResult.java
|   +-- ViolationSnapshot.java
+-- service/
|   +-- ClaimProtectionService.java
|   +-- FarmweltTeleportService.java
|   +-- JailActionService.java
|   +-- MessageService.java
|   +-- ResourceDetectionService.java
|   +-- ViolationService.java
+-- reset/
    +-- FarmworldLifecycleService.java
    +-- WorldsFarmworldLifecycleService.java
    +-- FarmworldResetEngine.java
    +-- FarmworldPostResetInitializer.java
    +-- BukkitFarmworldPostResetInitializer.java
    +-- FarmworldResetService.java
    +-- ResetNotificationConfig.java
    +-- ResetNotificationMessageConfig.java
    +-- ResetNotificationService.java
    +-- BukkitFarmworldWorldOperations.java
    +-- StartupResetCoordinator.java
    +-- AutomaticResetScheduler.java
    +-- ResetDueStateEvaluator.java
    +-- FoliaFarmweltScheduler.java
    +-- YamlResetStateRepository.java
```

## Plugin-Lifecycle

Die Hauptklasse ist `FarmweltPlugin`.

Beim Start:

1. `saveDefaultConfig()` erzeugt die Standardconfig, falls noch keine existiert.
2. Die harte Dependency Worlds wird einmalig über `WorldsAccess.access()` im Adapter verbunden. Bei einem Fehler deaktiviert sich Farmwelt ohne Bukkit-Fallback.
3. `ConfigManager` lädt Farmwelt-GUI-Einträge, Reset-Konfiguration und Ressourcenmonitor-Regeln.
4. `FarmworldResetService` lädt beziehungsweise initialisiert `reset-state.yml`.
5. `FarmworldResetEngine` erhält den bestehenden Reset-Service und den einmal erzeugten Worlds-Adapter per Constructor Injection.
6. GUI, weitere Services und Listener werden erstellt.
7. Der Befehl `/farmwelt` wird registriert.
8. `FarmweltCommand` wird zusätzlich als Listener registriert, weil der Monitor-Debug auf Rechtsklicks reagiert.
9. `FarmweltGuiListener` verarbeitet GUI-Klicks.
10. `ResourceBreakListener` verarbeitet Blockabbau- und Explosions-Events.
11. `StartupResetCoordinator` plant genau einen um 60 Sekunden verzögerten globalen Folia-Task. Er holt beim Start überfällige Welten sequenziell nach und startet erst danach den periodischen `AutomaticResetScheduler`.

Beim Stoppen bricht `FarmweltPlugin` über den Coordinator den noch wartenden Startup-Task oder den periodischen Task gezielt ab. Ein bereits laufender Reset wird nicht künstlich beendet; nach dem Stop startet die Catch-up-Kette aber weder eine weitere Welt noch den periodischen Scheduler. Eine Lifecycle-Generation schützt dabei auch gegen verspätete Future-Abschlüsse. Ein Config-Reload startet weder eine zweite Startup-Sequenz noch einen weiteren periodischen Task; die bestehende Komponente liest bei jeder Prüfung den aktuellen Snapshot des `FarmworldResetService`.

Beim Reload über `/farmwelt reload`:

1. Bukkit/Paper lädt die Config neu.
2. Farmwelt-GUI-Einträge, Reset-Konfiguration und Ressourcenmonitor-Konfiguration werden neu gelesen.
3. Derselbe `FarmworldResetService`, dieselbe `FarmworldResetEngine` und derselbe Worlds-Adapter bleiben bestehen.
4. Laufende Reset-Locks, Config-Snapshots und Worlds-Futures bleiben dadurch unverändert aktiv.
5. Claim-Hook wird neu initialisiert.
6. Violation-Schwellen und Zeitfenster werden neu geladen.

Bestehende Violation-Datensätze bleiben im Speicher, werden aber nach dem neuen Zeitfenster bewertet. Persistenz gibt es aktuell nicht.

## Reset-Architektur und Worlds

Farmwelt besitzt die fachliche Reset-Orchestrierung: Konfiguration, Reset-Lock, Status, Teleport-Sperre, API-basierter Hauptweltschutz, Spieler-Evakuierung, Ergebnisvalidierung, Post-Reset-Initialisierung, Logging sowie `lastReset` und `nextReset`. Worlds besitzt den technischen, versionsspezifischen Welt-Lifecycle.

Der vollständige automatische Steuerungs- und Persistenzpfad ist:

```text
StartupResetCoordinator (einmaliger Catch-up nach 60 Sekunden)
    -> AutomaticResetScheduler (reguläre Prüfung danach)
        -> ResetDueStateEvaluator
            -> FarmworldResetExecutor
                -> FarmworldResetEngine
                    -> FarmworldResetService
                        -> ResetStateRepository (reset-state.yml)
```

Startup-Catch-up und periodischer Scheduler benutzen denselben Executor und damit dieselbe Engine wie der manuelle Force-Reset. Nur ein vollständig erfolgreicher Engine-Durchlauf verschiebt den persistenten State; Coordinator und Scheduler besitzen weder eigene Resetlogik noch einen zusätzlichen Reset-Lock.

`FarmworldResetConfig` enthält zusätzlich den immutable `ResetNotificationConfig`-Snapshot mit absteigend sortierten, eindeutigen `Duration`-Schwellen und den einzelnen `ResetNotificationMessageConfig`-Werten. `ResetNotificationService` ist in Phase 5.1 lediglich der zentrale, zustandslose Zugriff auf diesen jeweils aktuell geladenen Snapshot. Die Komponente sendet keine Nachrichten, plant keine Tasks und besitzt weder einen eigenen Reset-State noch Persistenz. Beim Reload wird sie nicht neu aufgebaut; ihr Zugriff über denselben `FarmworldResetService` macht den neuen Notification-Snapshot automatisch sichtbar. Laufende Reset-Snapshots und gespeicherte `nextReset`-Werte bleiben davon unberührt.

```text
FarmworldResetEngine
    -> FarmworldLifecycleService
        -> WorldsFarmworldLifecycleService
            -> WorldsAccess.regenerate(world)
    -> FarmworldPostResetInitializer
        -> Bukkit Gamerule-/WorldBorder-API
        -> EnderDragon EntityScheduler
```

Die produktive Pipeline lautet:

```text
Config-Snapshot und Lock
    -> geladene Bukkit-Welt, Name, Dimension und Hauptweltschutz prüfen
    -> Spieler evakuieren und leere Welt bestätigen
    -> WorldsAccess.regenerate(world)
    -> neue Weltinstanz über Bukkit prüfen
    -> Gamerules, WorldBorder und Enderdragon-Policy anwenden
    -> Reset-State speichern
    -> Lock freigeben
```

Farmwelt ruft weder `Server#unloadWorld` noch `WorldCreator` auf und löscht keine Weltverzeichnisse. Der zurückgegebene Weltordner wird nur diagnostisch geloggt. `lastReset` und `nextReset` werden ausschließlich nach erfolgreicher Worlds-Regeneration, Validierung und Post-Reset-Initialisierung geschrieben. Scheitert die Initialisierung, lautet das Ergebnis `POST_RESET_FAILED`, der State bleibt unverändert und der Lock wird freigegeben. Schlägt anschließend nur `reset-state.yml` fehl, lautet das Ergebnis `STATE_SAVE_FAILED`; die Welt ist dann trotzdem bereits regeneriert und initialisiert.

`WorldsAccess.regenerate(...)` wird nicht in `FoliaFarmweltScheduler.runGlobal(...)` verpackt, da Worlds sein Global-/Folia-Scheduling selbst kapselt. Eigene kurze Bukkit-Prüfungen und asynchrone State-I/O verwenden weiterhin den Farmwelt-Scheduler. Fehler der Worlds-Future werden als `REGENERATE_FAILED` mit unveränderter Ursache abgebildet. Die interne `WorldOperationException.Reason`-API von Worlds wird bewusst nicht in Commands oder Business-Logik übernommen.

`ResetDueStateEvaluator` liefert je logischer Farmwelt `NOT_DUE`, `DUE` oder `DISABLED`. Nach einer festen Startup-Sicherheitsverzögerung von 60 Sekunden ermittelt `StartupResetCoordinator` damit die überfälligen Welten in stabiler Konfigurationsreihenfolge. Die Reset-Futures werden per `thenCompose` sequenziell verkettet; vor dem Start jeder Welt wird deren aktueller State erneut mit derselben Due-Logik bewertet. Ein Fehler oder `ALREADY_RUNNING` wird verarbeitet, ohne die nächste Welt zu blockieren. Es gibt pro überfälliger Welt nur einen Versuch, nicht je verpasstem Intervall. Der Coordinator manipuliert keine States und besitzt keinen Reset-Lock.

Erst nach Ende dieser Catch-up-Kette startet `AutomaticResetScheduler` genau einen periodischen globalen Folia-Task. Er prüft danach alle 60 Sekunden die persistenten `nextReset`-Zeitpunkte gegen ein aktuelles `Instant`. Nur bei `DUE` ruft er nicht-blockierend `FarmworldResetExecutor.reset(farmworldKey)` auf; dadurch gelten die normalen `ResetOptions` ohne Dragon-Override. Mehrere im regulären Tick fällige Welten werden weiterhin unabhängig angestoßen. Die Engine bleibt mit ihrem `runningResets`-Lock die einzige Schutzinstanz gegen parallele Resets derselben Farmwelt, weshalb `ALREADY_RUNNING` erwartungsgemäß ohne Warnung behandelt wird. Erfolg und echte Fehler werden kompakt geloggt; synchrone Startfehler und exceptional Futures beenden weder den periodischen Task noch die Verarbeitung anderer Farmwelten. Nur die Engine aktualisiert nach vollständigem Erfolg `lastReset` und `nextReset`; bei Fehlern bleibt der fällige State unverändert.

## ConfigManager

`ConfigManager` ist die zentrale Übersetzung von `config.yml` in laufzeitfreundliche Strukturen.

Wichtige Aufgaben:

- Farmwelt-GUI-Einträge aus `farmworlds` lesen.
- Reset-Einträge über den kleinen `FarmworldResetConfigParser` validieren; nur bekannte logische IDs mit gültigem Weltname, `m`-/`h`-/`d`-Intervall und gültigem `post-reset` gelangen in den Laufzeit-Snapshot.
- Optionale Notification-Werte tolerant über `FarmworldResetNotificationConfigParser` laden. Ungültige Warning-Einträge werden einzeln geloggt und ignoriert; sie können den Reset-Plan nicht deaktivieren. Fehlende Bereiche und Nachrichtentexte erhalten sichere Defaults.
- Icons als Bukkit-`Material` validieren.
- GUI-Slots validieren.
- Teleport-Aktionen validieren.
- Ressourcenmonitor-Grundwerte lesen.
- `monitored-worlds` und `ignored-worlds` in Sets vorbereiten.
- Permission-Namen für Bypass und Notify lesen.
- Audit-Optionen lesen.
- Action-Schwellen und Cooldowns lesen.
- Jail-Konfiguration lesen.
- Weltregeln vorbereiten.
- Materialnamen beim Laden in `EnumSet<Material>` übersetzen.

Die Materiallisten werden dadurch nicht bei jedem Blockabbau aus der Config gelesen. Ungültige Materialien werden beim Laden geloggt und ignoriert.

Die ausgelieferte Standardconfig verwendet bewusst breite Ressourcenlisten auf Basis der Paper-API 26.1.2. Sie decken typische natürliche Farmressourcen wie Holz, Erze, Amethyst, Sand/Gravel/Clay/Mud, Terracotta, Eis, Nether- und End-Blöcke ab. Serverbetreiber können diese Listen enger ziehen, wenn einzelne Materialien in Hauptwelten erlaubt bleiben sollen.

## Command-System

`FarmweltCommand` implementiert den zentralen Befehl `/farmwelt`.

Vorhandene Befehle:

```text
/farmwelt
/farmwelt status [welt]
/farmwelt info
/farmwelt reload
/farmwelt reset force <welt>
/farmwelt reset force end --dragon
/farmwelt debug claim
/farmwelt debug monitor
/farmwelt debug violations [spieler]
```

Permissions:

- `/farmwelt`: `farmwelt.use`
- Status: `farmwelt.admin.status`
- Reload: `farmwelt.admin.reload`
- Manueller Reset: `farmwelt.admin.reset`
- Info und Debug: `farmwelt.admin`

Status, Info, Reload und manueller Reset können auch von der Konsole genutzt werden. Debug-Befehle benötigen einen Spieler, weil sie mit Spielerpositionen, Rechtsklicks oder online Spielern arbeiten. Async-Abschlussmeldungen laufen für Spieler über den EntityScheduler und für andere Sender über den GlobalRegionScheduler.

## GUI-Flow

Klassen:

- `FarmweltMenu`
- `FarmweltMenuHolder`
- `FarmweltMenuItem`
- `FarmweltGuiListener`

Ablauf:

1. Spieler führt `/farmwelt` aus.
2. `FarmweltCommand` prüft `farmwelt.use`.
3. `FarmweltMenu.open(player)` erstellt ein Inventory mit 45 Slots.
4. Farmwelt-Einträge aus der Config werden in den Inhaltsbereich gelegt.
5. Statische Items wie Info- und Schließen-Item werden ergänzt.
6. `FarmweltGuiListener` bricht Klicks und Drags in der GUI ab, damit Items nicht entnommen werden können.
7. Klick auf einen Farmwelt-Eintrag ruft `FarmweltTeleportService.teleport(...)` auf.

Die Config-Slots der Farmwelt-Einträge beziehen sich auf den internen Inhaltsbereich mit 27 Slots. Im Inventory wird ein Offset verwendet, damit die Einträge optisch im mittleren Bereich liegen.

## Teleport-Flow

Klasse:

- `FarmweltTeleportService`

Unterstützt wird aktuell:

```yaml
teleport:
  type: command
  sender: player
  command: "betterrtp:rtp world Farmwelt"
```

Unterstützte Sender:

- `player`: Befehl wird über `player.performCommand(...)` ausgeführt.
- `console`: Befehl wird über `server.dispatchCommand(...)` als Konsole ausgeführt.

Folia-relevanter Ablauf:

1. Der GUI-Klick löst den Teleport-Service aus.
2. Der Service plant die Befehlsausführung über `player.getScheduler().execute(...)`.
3. Im Spieler-Kontext wird das Inventory geschlossen.
4. Platzhalter werden ersetzt.
5. Der Befehl wird ohne führenden Slash ausgeführt.

Unterstützte Platzhalter:

- `{player}`
- `{world}`
- `{id}`
- `{display-name}`

`{world}` und `{display-name}` verwenden aktuell den Anzeigenamen des GUI-Eintrags. Für technische Weltnamen sollte der BetterRTP-Befehl direkt in der Config fest eingetragen werden.

## Ressourcenmonitor

Klasse:

- `ResourceBreakListener`

Der Ressourcenmonitor reagiert auf `BlockBreakEvent`, `EntityDamageByEntityEvent`, `HangingBreakEvent`, `BlockExplodeEvent` und `EntityExplodeEvent` mit `ignoreCancelled = true`. Bereits von anderen Plugins abgebrochene Events werden nicht verarbeitet. `EntityDamageByEntityEvent` deckt geschützte Item-Frame-Loots wie Elytren in End-City-Schiffen ab; `HangingBreakEvent` verhindert im `enforce`-Modus, dass geschützte Item Frames indirekt zerstört werden.

Entscheidungsreihenfolge:

1. Ressourcenmonitor muss aktiviert sein.
2. Modus muss `audit`, `warn` oder `enforce` sein.
3. Claim-Fail-Mode darf den Monitor nicht deaktivieren.
4. Spieler darf keine Bypass-Permission haben.
5. Welt muss in `monitored-worlds` stehen.
6. Welt darf nicht in `ignored-worlds` stehen.
7. Für die Welt muss eine `world-rules`-Regel existieren.
8. Blockmaterial muss zur Weltregel passen, oder Item-Loot muss in `protected-items` stehen.
9. Wenn Claim-Ausnahmen aktiv sind, darf die Block- bzw. Item-Frame-Position nicht in einem Claim liegen.
10. Danach wird je nach Modus Audit, Warnung, Staff-Notify oder Blockabbruch verarbeitet.

Diese Reihenfolge ist wichtig: Teurere Prüfungen wie Claims passieren erst, nachdem einfache Ausschlussgründe erledigt sind.

Im `enforce`-Modus schützt der Listener zusätzlich Ressourcenblöcke vor indirekter Zerstörung durch Explosionen. Dafür wird die `blockList()` des Explosions-Events gefiltert: erkannte Ressourcenblöcke werden entfernt, die Explosion selbst wird aber nicht komplett abgebrochen. Der Explosionsschutz ist aktiv, wenn der Ressourcenmonitor im `enforce`-Modus läuft und `actions.cancel-break.enabled` aktiv ist. Geschützte Item-Frame-Loots werden in `enforce` sofort blockiert, da sie einzelne hochwertige Loot-Aktionen statt fortlaufenden Blockabbaus sind.

## Weltregeln und ResourceDetectionService

Klasse:

- `ResourceDetectionService`

Weltregeln werden über `ResourceWorldRule` abgebildet. Unterstützte Typen:

- `overworld`
- `nether`
- `end`

Overworld:

- Prüft ausschließlich `resources`.
- Es gibt keine Höhenprüfung.
- Treffer erhalten die Kategorie `overworld`.

Nether:

- Prüft ausschließlich `resources`.
- Treffer erhalten die Kategorie `nether`.

End:

- Prüft ausschließlich `resources`.
- Treffer erhalten die Kategorie `end`.
- `protected-items` schützt Item-Frame-Loot wie `ELYTRA`; Treffer erhalten die Kategorie `end-loot`.

Wenn keine Regel existiert oder das Material weder in `resources` noch als passender Item-Loot in `protected-items` steht, wird kein Ressourcen-Treffer erzeugt.

## Claim-Architektur

Klassen:

- `ClaimProtectionService`
- `ClaimProtectionProvider`
- `GriefPreventionClaimProtectionProvider`
- `NoopClaimProtectionProvider`

`ClaimProtectionService` entscheidet anhand der Config, welcher Provider genutzt wird.

Aktuell unterstützter Provider:

```yaml
provider: GriefPrevention
```

GriefPrevention wird optional angebunden. Der Provider nutzt Reflection, um die GriefPrevention-Datenstruktur und `getClaimAt(Location, boolean, Claim)` vorzubereiten. Dadurch bleibt Farmwelt ohne harte Compile-Abhängigkeit zu GriefPrevention lauffähig.

Wichtige Config-Werte:

- `enabled`: Schaltet Claim-Prüfung ein.
- `skip-inside-claims`: Ignoriert Ressourcenabbau in Claims.
- `fail-mode: disable-monitor`: Deaktiviert den Ressourcenmonitor, wenn der aktivierte Claim-Provider nicht verfügbar ist.
- `ignore-height`: Wird an GriefPrevention weitergereicht.

Beim Ressourcenmonitor wird die Position des abgebauten Blocks geprüft. `/farmwelt debug claim` prüft dagegen die aktuelle Spielerposition, weil der Befehl für schnelle Admin-Diagnose gedacht ist.

## ViolationService

Klasse:

- `ViolationService`

Der ViolationService hält pro Spieler einen Datensatz im Speicher. Er arbeitet thread-sicher mit `ConcurrentHashMap` und aktualisiert Einträge atomar über `compute`.

Gespeichert werden unter anderem:

- Spieler-UUID.
- Aktuelle Verstöße im Zeitfenster.
- Blockierte Versuche.
- Startzeit des Fensters.
- Letzter erkannter Block.
- Letzte Position.
- Letzte Kategorie.
- Zeitpunkte der letzten Actions.
- Status, ob Jail im aktuellen Fenster bereits ausgelöst wurde.

Zeitfenster:

```yaml
resource-monitor:
  violation-window-seconds: 600
```

Wenn das Zeitfenster abgelaufen ist, startet der nächste relevante Treffer wieder mit einem neuen Datensatz. Es gibt keine Datenbank und keine Persistenz über Serverneustarts.

Action-Entscheidung:

- `warning`: Wird ab `after-blocks` und nach Cooldown ausgelöst.
- `notify-staff`: Wird ab `after-blocks` und nach Cooldown ausgelöst.
- `cancel-break`: Wird in `enforce` ab `after-blocks` und nach Cooldown als Nachricht ausgelöst.
- `jail`: Nutzt nicht den normalen Violation-Zähler, sondern die Anzahl blockierter Versuche.

Wichtig: Der eigentliche Blockabbruch im `enforce`-Modus hängt an der aktuellen Count-Schwelle von `cancel-break`. Der Cooldown steuert die Nachricht, nicht die Tatsache, ob nach erreichter Schwelle weiter blockiert wird.

## MessageService

Klasse:

- `MessageService`

Der MessageService ist für Spieler-, Staff- und Console-Meldungen zuständig.

Aufgaben:

- Audit-Logs in die Konsole schreiben.
- Audit-Meldungen an Spieler mit Notify-Permission senden.
- Violation-Warnungen an Spieler senden.
- Staff-Benachrichtigungen senden.
- Cancel-Break-Nachrichten und Actionbar senden.
- Jail-Meldungen senden.
- Platzhalter ersetzen.

Nachrichten verwenden aktuell Legacy-Farbcodes mit `&` und werden über Adventure-Komponenten ausgegeben.

Wichtige Platzhalter:

- `{player}`
- `{uuid}`
- `{world}`
- `{x}`
- `{y}`
- `{z}`
- `{block}`
- `{category}`
- `{count}`
- `{blocked-count}`
- `{window-seconds}`

## Enforce- und Jail-Flow

Enforce wird nur bei `resource-monitor.mode: enforce` aktiv.

Ablauf bei einem relevanten Blockabbau:

1. Violation wird registriert.
2. Warn- und Staff-Actions werden geprüft.
3. Wenn `cancel-break.enabled` aktiv ist und die Schwelle erreicht ist, wird `event.setCancelled(true)` gesetzt.
4. Spieler erhält je nach Cooldown Chat- und/oder Actionbar-Nachricht.
5. Der blockierte Versuch wird separat registriert.
6. Wenn die Jail-Schwelle für blockierte Versuche erreicht ist, wird `JailActionService.execute(...)` aufgerufen.

Ablauf bei einer relevanten Explosion:

1. `BlockExplodeEvent` oder `EntityExplodeEvent` liefert die betroffenen Blöcke.
2. Der Listener prüft Monitorstatus, `enforce`-Modus und `cancel-break`.
3. Für jeden Block werden Weltregel, Ressourcenmaterial und Claim-Ausnahme geprüft.
4. Erkannte Ressourcenblöcke werden aus der Explosionsliste entfernt.
5. Alle übrigen Blöcke bleiben in der Explosionsliste.

Jail ist standardmäßig deaktiviert:

```yaml
actions:
  jail:
    enabled: false
    mode: notify-only
```

Unterstützte Jail-Modi:

- `disabled`: keine Aktion.
- `notify-only`: nur Staff informieren.
- `execute-command`: konfigurierten Befehl als Konsole ausführen.

Folia-relevanter Ablauf bei `execute-command`:

1. Staff-Meldung wird direkt über den MessageService gesendet.
2. Der Konsolenbefehl wird über `getGlobalRegionScheduler().execute(...)` geplant.
3. Die optionale Spielernachricht nach erfolgreichem Befehl wird über `player.getScheduler().execute(...)` geplant.

## Debug-Werkzeuge

`/farmwelt info` zeigt:

- Plugin-Version.
- Anzahl geladener Farmwelt-Einträge.
- Ressourcenmonitor-Status und Modus.
- Claim-Provider.
- Claim-Hook-Status.
- BetterRTP-Status.
- GriefPrevention-Status.
- Jail-Modus.

`/farmwelt debug claim` zeigt:

- Claim-Provider.
- Claim-Schutz-Status.
- Ob die Spielerposition in einem Claim liegt.

`/farmwelt debug monitor`:

- Schaltet pro Spieler einen Rechtsklick-Debugmodus um.
- Rechtsklick auf einen Block zeigt Welt-, Regel-, Claim-, Bypass-, Ressourcen- und Blockierinformationen.
- Der Modus wird beim erneuten Befehl oder beim Quit entfernt.

`/farmwelt debug violations [spieler]`:

- Zeigt aktuellen Violation-Zähler.
- Zeigt blockierte Versuche.
- Zeigt Restzeit des Fensters.
- Zeigt Schwellen und Jail-Status.
- Optional kann ein anderer online Spieler geprüft werden.

## Folia-Entscheidungen

Das Plugin ist in `paper-plugin.yml` mit `folia-supported: true` markiert.

Aktuelle Folia-relevante Punkte:

- Teleportbefehle aus der GUI werden über den Entity-Scheduler des Spielers geplant.
- Spieler-Evakuierungen verwenden den Entity-Scheduler und `teleportAsync`.
- Kurze eigene Bukkit-Weltprüfungen laufen über den Global-Region-Scheduler.
- Startup-Delay und periodische Fälligkeitsprüfung laufen über den Global-Region-Scheduler. Die Startup-Kette wartet ausschließlich nicht-blockierend über `CompletableFuture` auf Reset-Abschlüsse; der periodische Task wartet nicht auf Reset-Futures. Die angestoßene Pipeline verwendet jeweils ihre bestehenden Folia-Kontexte.
- Dynamisches Entladen, Regenerieren und erneutes Laden übernimmt Worlds mit seiner versionsspezifischen Folia-Implementierung.
- Der Aufruf `WorldsAccess.regenerate(...)` erhält keine zusätzliche Scheduler-Hülle durch Farmwelt.
- Jail-Konsolenbefehle werden über den Global-Region-Scheduler geplant.
- Spielernachrichten nach Jail-Befehl werden wieder über den Entity-Scheduler geplant.
- Violation-Daten liegen in thread-sicheren Strukturen.
- Der Ressourcenmonitor arbeitet eventgetrieben und speichert nur kleine In-Memory-Datensätze.

Bei neuen Features sollten Welt-, Block- oder Spielerzugriffe weiterhin im passenden Kontext passieren. Besonders kritisch sind zeitversetzte Aktionen, Teleports, Inventarzugriffe und direkte Weltmanipulationen.

## Abhängigkeiten

`paper-plugin.yml`:

```yaml
dependencies:
  server:
    Worlds:
      load: BEFORE
      required: true
      join-classpath: true
    BetterRTP:
      load: BEFORE
      required: false
      join-classpath: false
    GriefPrevention:
      load: BEFORE
      required: false
      join-classpath: true
```

Worlds:

- Ist eine harte Runtime-Abhängigkeit für V2.
- Wird als `compileOnly("net.thenextlvl:worlds:4.4.0")` aus `https://repo.thenextlvl.net/releases` kompiliert und nicht in die Farmwelt-JAR geshadet.
- Stellt ausschließlich über den Adapter den dynamischen Lifecycle bereit.
- Ohne aktive API-Instanz bricht Farmwelt den Plugin-Start ab; es gibt keinen stillen Bukkit-Fallback.

BetterRTP:

- Wird nicht direkt per API genutzt.
- Farmwelt führt nur konfigurierte Befehle aus.
- Ohne BetterRTP startet Farmwelt, aber die Standardbefehle funktionieren nicht.

GriefPrevention:

- Wird optional für Claim-Erkennung genutzt.
- Der Hook wird über Reflection vorbereitet.
- Bei aktivem `fail-mode: disable-monitor` deaktiviert ein fehlender Hook den Ressourcenmonitor.

## Datenhaltung

Farmwelt nutzt keine Datenbank. Neben der Config persistiert `reset-state.yml` den letzten und nächsten Reset-Zeitpunkt pro logischer Farmwelt. Die Datei wird vom Plugin verwaltet und sollte im normalen Betrieb nicht manuell editiert werden. Ein Neustart oder eine Intervalländerung per Reload verschiebt einen bereits gespeicherten nächsten Reset nicht; das neue Intervall gilt erst nach dem nächsten erfolgreichen Reset.

In-Memory-Daten:

- Geladene Farmwelt-Menüeinträge.
- Geladene Ressourcenregeln.
- Violation-Datensätze pro Spieler.
- Audit-Cooldown-Zeitpunkte pro Spieler, Material und Kategorie. Wiederholte Audit-Treffer setzen den Zeitpunkt auch dann neu, wenn keine Meldung ausgegeben wird.
- Aktive Monitor-Debug-Spieler.
- Aktive Reset-Locks und immutable Config-Snapshots laufender Resets.

Diese Daten gehen bei Serverneustart verloren. Das ist für die aktuelle Funktion beabsichtigt.

## Erweiterungshinweise

Bei neuen Features zuerst prüfen:

1. Gehört die Änderung in Config, Command, Listener oder Service?
2. Muss sie Folia-Kontext beachten?
3. Muss sie in `/farmwelt info` oder Debug-Ausgaben sichtbar werden?
4. Braucht sie eine neue Permission?
5. Muss die Admin-Doku angepasst werden?
6. Muss die README nur kurz oder ausführlich aktualisiert werden?

Leitlinien:

- Harte Runtime-Abhängigkeiten nur an klaren Architekturgrenzen einsetzen; Worlds ist für den unter Folia benötigten Welt-Lifecycle ausdrücklich diese Grenze.
- Config-Werte beim Laden validieren und vorbereiten.
- Event-Listener früh verlassen, wenn ein Fall nicht relevant ist.
- Ressourcenmonitor-Regeln nicht pro Event aus YAML lesen.
- Spieler- und Weltzugriffe bei asynchronen oder geplanten Aktionen Folia-sicher ausführen.
- Harte Sanktionen standardmäßig deaktiviert oder sehr konservativ halten.

## Dokumentationsstruktur

- `README.md`: Überblick, Installation, Commands, Permissions und Betriebsgrundlagen.
- `docs/ADMIN_GUIDE.md`: Einrichtung, Testplan, Rollout und Wartung.
- `docs/ARCHITECTURE.md`: Technischer Aufbau und Wartungshinweise für Entwickler.
