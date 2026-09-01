# Farmwelt Architektur

Diese Datei beschreibt den aktuellen technischen Aufbau und die Lifecycle-Verträge von Farmwelt V2. Benutzer- und Betriebsanweisungen stehen im [Admin Guide](ADMIN_GUIDE.md); reale Integrationstests stehen unter [`docs/testing/`](testing/black-box-testing.md).

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
    +-- ResetWarningTracker.java
    +-- ResetNotificationMessageFormatter.java
    +-- ResetNotificationAudience.java
    +-- BukkitResetNotificationAudience.java
    +-- ResetPlayerNotificationAudience.java
    +-- BukkitResetPlayerNotificationAudience.java
    +-- FarmworldEvacuationResult.java
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
5. `FarmworldResetEngine` erhält Reset-Service, Notification-Service und den einmal erzeugten Worlds-Adapter per Constructor Injection.
6. GUI, weitere Services und Listener werden erstellt.
7. Der Befehl `/farmwelt` wird registriert.
8. `FarmweltCommand` wird zusätzlich als Listener registriert, weil der Monitor-Debug auf Rechtsklicks reagiert.
9. `FarmweltGuiListener` verarbeitet GUI-Klicks.
10. `ResourceBreakListener` verarbeitet Blockabbau- und Explosions-Events.
11. `StartupResetCoordinator` plant genau einen um 60 Sekunden verzögerten globalen Folia-Task. Er holt beim Start überfällige Welten sequenziell nach und startet erst danach den periodischen `AutomaticResetScheduler`.

Beim Stoppen bricht `FarmweltPlugin` über den Coordinator den noch wartenden Startup-Task oder den periodischen Task gezielt ab. Ein bereits laufender Reset wird nicht künstlich beendet; insbesondere verwendet Farmwelt für bereits an Worlds beziehungsweise die Reset-Pipeline übergebene Lifecycle-Operationen weder `Future.cancel(...)` noch Thread-Interrupts. Ein halbfertig abgebrochener Welt-Lifecycle wäre unsicherer als die kontrollierte Fortsetzung bis zum Prozessende. Nach dem Stop startet die Catch-up-Kette aber weder eine weitere Welt noch den periodischen Scheduler. Lifecycle-Generationen im Coordinator und im periodischen Scheduler schützen dabei auch gegen verspätete Future-Abschlüsse beziehungsweise Task-Callbacks. Ein Config-Reload startet weder eine zweite Startup-Sequenz noch einen weiteren periodischen Task; die bestehende Komponente liest bei jeder Prüfung den aktuellen Snapshot des `FarmworldResetService`.

Beim Reload über `/farmwelt reload` verhindert ein nicht blockierender Guard parallele Reload-Läufe aus verschiedenen Folia-Regionen. Ein weiterer Aufruf wird abgewiesen, solange der erste Lauf noch aktiv ist. Danach gilt:

1. Bukkit/Paper lädt die Config neu.
2. Die Reset-Konfiguration wird zuerst gelesen und gemeinsam mit `reset-state.yml` übernommen. Schlägt dieser sicherheitskritische Schritt fehl, bleiben die zuvor veröffentlichten GUI- und Ressourcenmonitor-Snapshots erhalten.
3. Farmwelt-GUI-Einträge und Ressourcenmonitor-Konfiguration werden neu gelesen.
4. Derselbe `FarmworldResetService`, dieselbe `FarmworldResetEngine`, derselbe `ResetNotificationService` und derselbe Worlds-Adapter bleiben bestehen.
5. Der Notification-Service übernimmt den neuen Snapshot und entfernt Tracking-State deaktivierter oder entfernter Farmwelten.
6. Laufende Reset-Locks, Config-Snapshots und Worlds-Futures bleiben dadurch unverändert aktiv.
7. Claim-Hook wird neu initialisiert.
8. Violation-Schwellen und Zeitfenster werden neu geladen.

Die Reload-Werte des Ressourcenmonitors, des Claim-Hooks und der Violation-Auswertung werden jeweils als vollständige immutable Laufzeit-Snapshots veröffentlicht. Jeder Zugriff aus einer parallelen Folia-Region sieht dadurch einen vollständig aufgebauten alten oder neuen Snapshot statt stückweise aktualisierter Felder. Bestehende Violation-Datensätze bleiben im Speicher, werden aber nach dem neuen Zeitfenster bewertet. Persistenz gibt es aktuell nicht.

## Reset-Architektur und Worlds

Farmwelt besitzt die fachliche Reset-Orchestrierung: Konfiguration, Reset-Lock, Status, Teleport-Sperre, API-basierter Hauptweltschutz, Spieler-Evakuierung, Ergebnisvalidierung, Post-Reset-Initialisierung, Logging sowie `lastReset` und `nextReset`. Worlds ist eine harte Runtime-Abhängigkeit und besitzt allein den technischen, versionsspezifischen dynamischen Welt-Lifecycle. Es gibt keinen Bukkit-Fallback; Farmwelt ruft weder `Server#unloadWorld` noch `WorldCreator` auf und löscht keine Weltverzeichnisse.

Der vollständige automatische Steuerungs- und Persistenzpfad ist:

```text
StartupResetCoordinator (einmaliger Catch-up nach 60 Sekunden)
    -> AutomaticResetScheduler (reguläre Prüfung danach)
        -> ResetNotificationService
            -> ResetWarningTracker (nur Countdown)
            -> ResetNotificationAudience
        -> ResetDueStateEvaluator
            -> FarmworldResetExecutor
                -> FarmworldResetEngine
                    -> ResetNotificationService (Start, Evakuierung und fachliches Ergebnis)
                        -> ResetNotificationAudience (globale Lifecycle-Nachrichten)
                        -> ResetPlayerNotificationAudience (persönliche Evakuierungsnachricht)
                    -> FarmworldResetService
                        -> ResetStateRepository (reset-state.yml)
```

Startup-Catch-up und periodischer Scheduler benutzen denselben Executor und damit dieselbe Engine wie der manuelle Force-Reset. Nur ein vollständig erfolgreicher Engine-Durchlauf verschiebt den persistenten State; Coordinator und Scheduler besitzen weder eigene Resetlogik noch einen zusätzlichen Reset-Lock.

`FarmworldResetConfig` enthält zusätzlich zum technischen Bukkit-Weltnamen den nutzerfreundlichen `displayName` sowie den immutable `ResetNotificationConfig`-Snapshot mit absteigend sortierten, eindeutigen `Duration`-Schwellen und den einzelnen `ResetNotificationMessageConfig`-Werten. Der Parser übernimmt den Anzeigenamen direkt aus `farmworlds.<id>.display-name`, ohne die Reset-Logik an GUI-Klassen zu koppeln.

`ResetNotificationService` liest für Countdown-Prüfungen den aktuellen Config- und State-Snapshot aus demselben `FarmworldResetService`. Für Lifecycle-Nachrichten übergibt die Engine ihren beim Start erfassten immutable Config-Snapshot. Nach akzeptiertem Lock sowie gültiger und aktivierter Konfiguration sendet die Engine genau eine Startmeldung; `NOT_CONFIGURED`, `DISABLED` und `ALREADY_RUNNING` erreichen diesen Punkt nicht. Das abschließende fachliche `ResetResult` erzeugt nur bei `SUCCESS` eine Erfolgsmeldung. Alle anderen Status eines tatsächlich gestarteten Resets, einschließlich `STATE_SAVE_FAILED`, werden auf die optional aktivierte Fehlermeldung abgebildet. Die Erfolgsmeldung liest erst nach erfolgreicher State-Persistenz den neu veröffentlichten `nextReset`; sonst bleibt der bestehende Termin sichtbar oder der Formatter verwendet `unbekannt`. Notifications sind Best-Effort und nicht sicherheitskritisch: Ein Fehler beim Formatieren, Scheduling oder Versenden darf weder die Reset-Pipeline beschädigen noch ihren fachlichen Status verändern.

`ResetWarningTracker` hält pro logischer Farmwelt nur den aktuellen `nextReset`, die aktuell bekannten Warnschwellen und die bereits verwendeten Dauern. Ein geänderter Termin ersetzt den alten Zyklus vollständig. Beim ersten Snapshot sowie nach einer relevanten Reload-Reinitialisierung markiert der Tracker alle bereits erreichten Schwellen als erledigt, liefert aber höchstens die dem aktuellen Zeitpunkt nächste Schwelle zurück. Danach liefert er nur neu überschrittene, noch nicht verwendete Dauern. Gleichheit mit einem exakten Tick-Zeitpunkt ist nicht erforderlich; entscheidend ist `remaining <= warning` bei weiterhin zukünftigem `nextReset`.

Der Tracker ist synchronisiert, klein und ausschließlich transient. Reloads bereinigen deaktivierte oder entfernte Einträge; weder Warning-Dauern noch Versandstatus gelangen in `reset-state.yml`. `ResetNotificationMessageFormatter` ersetzt `{world}`, `{time}` und `{next-reset}` unabhängig von Bukkit; Lifecycle- und Evakuierungstexte verwenden davon `{world}` und `{next-reset}`. Dabei nutzt er den vorhandenen `GermanDurationFormatter` und dieselbe `ZoneId.systemDefault()`-/`dd.MM.yyyy HH:mm`-Logik wie der Statusbefehl. `BukkitResetNotificationAudience` deserialisiert etablierte `&`-Farbcodes und plant globale Nachrichten pro Online-Spieler über dessen Folia-Entity-Scheduler. Die getrennte `BukkitResetPlayerNotificationAudience` plant die persönliche Evakuierungsnachricht ausschließlich für den betroffenen Spieler in demselben sicheren Entity-Kontext. Sämtliche Formatter-/Audience-Ausnahmen werden im Notification-Service abgefangen und können den Reset-Status nicht verändern.

`BukkitFarmworldWorldOperations` unterscheidet pro Spieler zwischen erfolgreichem Reset-Teleport, fehlgeschlagenem Versuch und einer vor dem Entity-Task bereits selbst verlassenen Farmwelt. `FarmworldEvacuationResult` enthält dedupliziert nur die Spieler mit bestätigtem erfolgreichem Teleport. Die Engine übergibt genau diese Spieler unmittelbar nach Abschluss der Evakuierungsoperation an den Notification-Service und prüft danach weiterhin separat den Welt-Leerstand. Teilweise erfolgreiche Evakuierungen dürfen deshalb korrekt benachrichtigt werden, auch wenn der Reset wegen eines anderen Spielers oder erst in einer späteren Pipeline-Stufe fehlschlägt. Disconnects zwischen Teleport und Nachricht führen lediglich zum Auslassen der Nachricht; es gibt bewusst keinen Retry und keine persistente Historie. Automatische, beim Start nachgeholte und manuelle Force-Resets gelangen ausnahmslos über diesen gemeinsamen Engine-Pfad.

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
Config-Snapshot / Dragon-Scope und Reset-Lock
    -> geladene Bukkit-Welt, Name, Dimension und Hauptweltschutz validieren
    -> Spieler evakuieren und erfolgreiche Einzelteleports persönlich benachrichtigen
    -> Welt erneut auf Leerstand prüfen
    -> WorldsAccess.regenerate(world)
    -> neue Bukkit-Weltinstanz, Name, Dimension und Hauptweltschutz validieren
    -> Gamerules, WorldBorder und Enderdragon-Policy anwenden
    -> Reset-State persistieren und veröffentlichen
    -> SUCCESS
    -> Lock auf jedem Exit-Pfad freigeben
```

Der `runningResets`-Lock erlaubt maximal einen Reset pro logischer Farmwelt. Derselbe Lock steuert die Teleport-Verfügbarkeit; `FarmweltTeleportService` prüft ihn vor dem Scheduling und erneut im Entity-Kontext. Die Engine entfernt ihn in allen synchronen und asynchronen Erfolgs- und Fehlerpfaden. Scheduler und Startup-Coordinator besitzen bewusst keinen zweiten Reset-Lock.

Der zurückgegebene Weltordner wird nur diagnostisch geloggt. `lastReset` und `nextReset` werden ausschließlich nach erfolgreicher Worlds-Regeneration, Validierung und Post-Reset-Initialisierung als Kandidat berechnet. `FarmworldResetService` speichert zuerst die vollständige Kandidaten-Map und veröffentlicht sie erst nach erfolgreichem Repository-Save als neuen In-Memory-State. Scheitert die Initialisierung, lautet das Ergebnis `POST_RESET_FAILED`; scheitert nur das Speichern, lautet es `STATE_SAVE_FAILED`. Im zweiten Fall ist die Welt bereits regeneriert und initialisiert, es gibt aber kein `SUCCESS` und der zuvor veröffentlichte State bleibt unverändert. Damit gilt die Invariante: `nextReset` wird nur nach einer vollständig erfolgreichen Pipeline fortgeschrieben.

`WorldsAccess.regenerate(...)` wird nicht in `FoliaFarmweltScheduler.runGlobal(...)` verpackt, da Worlds sein Global-/Folia-Scheduling selbst kapselt. Eigene kurze Bukkit-Prüfungen und asynchrone State-I/O verwenden weiterhin den Farmwelt-Scheduler. Fehler der Worlds-Future werden als `REGENERATE_FAILED` mit unveränderter Ursache abgebildet. Die interne `WorldOperationException.Reason`-API von Worlds wird bewusst nicht in Commands oder Business-Logik übernommen.

`ResetDueStateEvaluator` liefert je logischer Farmwelt `NOT_DUE`, `DUE` oder `DISABLED`. Nach einer festen Startup-Sicherheitsverzögerung von 60 Sekunden ermittelt `StartupResetCoordinator` damit die überfälligen Welten in stabiler Konfigurationsreihenfolge. Die Reset-Futures werden per `thenCompose` sequenziell verkettet; vor dem Start jeder Welt wird deren aktueller State erneut mit derselben Due-Logik bewertet. Ein Fehler oder `ALREADY_RUNNING` wird verarbeitet, ohne die nächste Welt zu blockieren. Es gibt pro überfälliger Welt nur einen Versuch, nicht je verpasstem Intervall. Der Coordinator manipuliert keine States und besitzt keinen Reset-Lock.

Erst nach Ende dieser Catch-up-Kette startet `AutomaticResetScheduler` genau einen periodischen globalen Folia-Task. Er prüft danach alle 60 Sekunden zuerst Countdown-Warnungen und anschließend die persistenten `nextReset`-Zeitpunkte gegen dasselbe aktuelle `Instant`. Notification-Fehler werden pro Farmwelt beziehungsweise Broadcast geloggt und können weder den Due-Check noch den Start eines fälligen Resets verhindern. Für `now >= nextReset` gibt der Countdown-Pfad nichts aus. Nur bei `DUE` ruft der Scheduler nicht-blockierend `FarmworldResetExecutor.reset(farmworldKey)` auf; dadurch gelten die normalen `ResetOptions` ohne Dragon-Override. Mehrere im regulären Tick fällige Welten werden weiterhin unabhängig angestoßen. Die Engine bleibt mit ihrem `runningResets`-Lock die einzige Schutzinstanz gegen parallele Resets derselben Farmwelt, weshalb `ALREADY_RUNNING` ohne zusätzlichen Lifecycle-Broadcast behandelt wird. Erfolg und echte Fehler werden kompakt geloggt; synchrone Startfehler und exceptional Futures beenden weder den periodischen Task noch die Verarbeitung anderer Farmwelten. Nur die Engine aktualisiert nach vollständigem Erfolg `lastReset` und `nextReset`; bei Fehlern bleibt der fällige State unverändert. Manuelle Force-Resets umgehen den Scheduler weiterhin und erzeugen daher keine Countdown-Serie, erhalten über dieselbe Engine aber dieselben Start-/Erfolgs-/Fehlermeldungen wie automatische und Startup-Resets.

## Endfarm- und DragonBattle-Vertrag

Die Endfarm verwendet bewusst versionsgebundene Bukkit-/CraftBukkit-/Minecraft-Zugriffe. `EndDragonFightCompatibility` begrenzt die freigegebene Serverversion, `EndDragonFightRuntimeAccess` kapselt DragonBattle-Zustand und Bossbar, und `EndDragonFightDataStore` staged die betroffenen Saved-Data-Änderungen mit Commit/Rollback. `BukkitFarmworldPostResetInitializer` koordiniert außerdem Spawn-Guard, Portal-Erzeugung und spätere Portal-Verifikation in der zuständigen Region.

Für `post-reset.end.dragon: false` setzt die Initialisierung den Fight-State auf abgeschlossen, entfernt einen vorhandenen Drachen, unterdrückt die Bossbar und stellt ein aktives Exit-Portal her. Vor der ersten Portal-Erzeugung wird der zentrale End-Chunk vollständig geladen; die Brunnenbasis wird anschließend in der zuständigen Region über `MOTION_BLOCKING_NO_LEAVES` aus der tatsächlichen Inseloberfläche ermittelt. Der Spawn-Guard verhindert verzögerte Erstspawns. Eine echte Vanilla-Wiederbeschwörung mit vier Endkristallen bleibt erlaubt; sie zählt nicht als Erstkampf. Nach dem Tod dieses Respawn-Drachens wird das aktive Portal in der End-Ursprungsregion erneut hergestellt und verifiziert.

Für `dragon: true` oder den einmaligen Command-Override `--dragon` wird ein frischer Vanilla-Erstkampf mit aktiver Bossbar vorbereitet. Der Drache darf erscheinen, das Portal ist zunächst inaktiv und wird nach dem Kampf aktiv; das Drachenei-Erstkampfverhalten bleibt erhalten. `--dragon` verändert den Config-Snapshot für zukünftige Resets nicht. Wenn die dauerhafte Config `dragon: false` enthält, existiert die einmalige Spawn-Freigabe nur bis zum tatsächlichen Vanilla-Spawn; anschließend greift wieder die konfigurierte Suppression.

Diese Logik darf bei Minecraft-/Folia-Upgrades nicht als gewöhnliches Entity-Cleanup behandelt werden. Datenlayout, reflektierte Felder und Methoden, Saved-Data-Staging, Portal, Kristall-Respawn, Bossbar und Drachenei benötigen eine bewusste Kompatibilitätsprüfung.

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
- Den vollständigen Ressourcenmonitor-Stand nach erfolgreichem Parsen atomar als immutable Snapshot veröffentlichen.

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
5. Ein Spielerbefehl wird ohne führenden Slash unmittelbar in diesem Entity-Kontext ausgeführt.
6. Ein Konsolenbefehl wird zur Ausführung an den `GlobalRegionScheduler` übergeben. Eine Fehlermeldung an den Spieler kehrt über dessen Entity-Scheduler zurück.

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

Nachrichten verwenden aktuell Legacy-Farbcodes mit `&` und werden über Adventure-Komponenten ausgegeben. Globale Staff-Meldungen werden für jeden Empfänger einzeln auf dessen Entity-Scheduler geplant; erst dort erfolgen Permission-Prüfung und Versand.

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

1. Staff-Meldungen werden über den MessageService pro Empfänger im jeweiligen Entity-Kontext geplant.
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

- Teleportbefehle aus der GUI beginnen im Entity-Scheduler des Spielers. Konsolenbefehle wechseln zur Ausführung auf den Global-Region-Scheduler.
- Spieler-Evakuierungen verwenden den Entity-Scheduler und `teleportAsync`.
- Kurze eigene Bukkit-Weltprüfungen laufen über den Global-Region-Scheduler.
- Startup-Delay und periodische Fälligkeitsprüfung laufen über den Global-Region-Scheduler. Die Startup-Kette wartet ausschließlich nicht-blockierend über `CompletableFuture` auf Reset-Abschlüsse; der periodische Task wartet nicht auf Reset-Futures. Die angestoßene Pipeline verwendet jeweils ihre bestehenden Folia-Kontexte.
- Dynamisches Entladen, Regenerieren und erneutes Laden übernimmt Worlds mit seiner versionsspezifischen Folia-Implementierung.
- Der Aufruf `WorldsAccess.regenerate(...)` erhält keine zusätzliche Scheduler-Hülle durch Farmwelt.
- Jail-Konsolenbefehle werden über den Global-Region-Scheduler geplant.
- Spielernachrichten nach Jail-Befehl werden wieder über den Entity-Scheduler geplant.
- Staff-Broadcasts prüfen Permissions und senden Nachrichten im Entity-Kontext des jeweiligen Empfängers.
- Violation-Daten liegen in thread-sicheren Strukturen; Reload-Konfigurationen werden als zusammengehörige immutable Snapshots veröffentlicht.
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

- [`README.md`](../README.md): kompakter Projekteinstieg, Kernfeatures, Voraussetzungen, Installation und wichtigste Commands.
- [`docs/ADMIN_GUIDE.md`](ADMIN_GUIDE.md): vollständige Admin-, Config- und Betriebsreferenz.
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md): technische Architektur und Lifecycle-Verträge.
- [`docs/RELEASE.md`](RELEASE.md): Build-, Versions- und Releaseprozess.
- [`docs/testing/black-box-testing.md`](testing/black-box-testing.md): vollständige manuelle Black-Box-Teststrategie.
- [`docs/testing/v2-acceptance-template.md`](testing/v2-acceptance-template.md): wiederverwendbare V2-Abnahmevorlage.
- [`testing/blackbox/README.md`](../testing/blackbox/README.md): automatisiertes Folia-/Worlds-Smoke-Harness.
- [`AGENTS.md`](../AGENTS.md): Entwicklungsregeln und technische Verträge für zukünftige Änderungen.
