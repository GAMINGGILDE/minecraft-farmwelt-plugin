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
|   +-- FarmweltMenuLore.java
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
4. `FarmworldResetService` lädt den Zeitplan und die Recovery-Journale. Anschließend initialisiert er fehlende Zeitpläne freigegebener Welten.
5. `FarmworldResetEngine` erhält Reset-Service, Notification-Service und den einmal erzeugten Worlds-Adapter per Constructor Injection.
6. GUI, weitere Services und Listener werden erstellt.
7. Der Befehl `/farmwelt` wird registriert.
8. `FarmweltCommand` wird zusätzlich als Listener registriert, weil der Monitor-Debug auf Rechtsklicks reagiert.
9. `FarmweltGuiListener` verarbeitet GUI-Klicks.
10. `ResourceBreakListener` verarbeitet Blockabbau- und Explosions-Events.
11. `StartupResetCoordinator` plant genau einen um 60 Sekunden verzögerten globalen Folia-Task. Er beobachtet beim Start überfällige Welten mit begrenzter Wartezeit sequenziell und startet erst danach den periodischen `AutomaticResetScheduler`.

Beim Stop sperrt die Engine neue Arbeit und beendet noch wartende eigene Tasks. Bereits gestartete Dateioperationen dürfen ihren Abschluss veröffentlichen. Der letzte dauerhafte Beleg bestimmt die Recovery. Lifecycle-Generationen im Coordinator und im periodischen Scheduler schützen dabei auch gegen verspätete Future-Abschlüsse beziehungsweise Task-Callbacks. Ein Config-Reload startet weder eine zweite Startup-Sequenz noch einen weiteren periodischen Task; die bestehende Komponente liest bei jeder Prüfung den aktuellen Snapshot des `FarmworldResetService`.

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
Reset-Lock und Config-Snapshot
    -> effektive Dragon-Policy bestimmen, erforderliche Versionsfreigabe prüfen, Dragon-Scope öffnen
    -> geladene Bukkit-Welt, Name, Dimension und Hauptweltschutz validieren
    -> Spieler evakuieren und erfolgreiche Einzelteleports persönlich benachrichtigen
    -> Welt erneut auf Leerstand prüfen
    -> WORLDS_PENDING dauerhaft speichern
    -> Instanz, Hauptweltschutz, Dimension und Leerstand vor Übergabe erneut prüfen
    -> WorldsAccess.regenerate(world)
    -> WORLDS_RETURNED dauerhaft speichern
    -> neue Bukkit-Weltinstanz, Name, Dimension und Hauptweltschutz validieren
    -> Gamerules, WorldBorder und Enderdragon-Policy anwenden
    -> POST_RESET_DONE mit Abschlusszeit und Folgetermin dauerhaft speichern
    -> Reset-State mit Versuchs-ID persistieren und veröffentlichen
    -> SUCCESS
    -> Lock auf jedem Exit-Pfad freigeben
```

### Identitäten und Laufzeitreservierung (FW-003)

| Identität | Verwendung |
|---|---|
| Logische ID | `FarmworldType` ordnet externe Schreibweisen mit `Locale.ROOT` den Typen Overworld, Nether und End zu. `key()` liefert den Schlüssel für Config, Commands, Menü, Scheduler, Warnungen und Zeitplan. |
| Physisches Ziel | Die Reservierung vergleicht konfigurierte Bukkit-Namen ohne Beachtung der Großschreibung. Vor Worlds prüft die Pipeline exakten registrierten Namen, Weltinstanz, Dimension und Hauptweltschutz. Nach Worlds prüft sie die neue registrierte Instanz. Objekt und Welt-UUID können beim Weltwechsel wechseln. |
| Reset-Versuch | FW-002 vergibt für die einzelne Pipeline eine UUID. Journal und Zeitplan belegen mit dieser UUID den Abschluss. |

Die Engine erfasst den Config-Snapshot und reserviert Typ und Zielname gemeinsam unter dem kurzen Monitor, über den der Service auch neue Konfigurationen veröffentlicht. Der Besitzer hält die Reservierung über Evakuierung, Worlds, Persistenz und Dragon-Scope-Bereinigung. Ein abgebrochener Beobachter kann sie nicht freigeben. Auf Scheduler- oder Dateioperationen wird unter diesem Monitor nicht gewartet. Die Teleport-Prüfungen vor dem Scheduling und im Spieler-Kontext sehen auch eine Reservierung des neu zugeordneten Zielnamens.

Der zurückgegebene Weltordner wird nur diagnostisch geloggt. `lastReset` und `nextReset` werden ausschließlich nach erfolgreicher Worlds-Regeneration, Validierung und Post-Reset-Initialisierung als Kandidat berechnet. `FarmworldResetService` speichert zuerst die vollständige Kandidaten-Map und veröffentlicht sie erst nach erfolgreichem Repository-Save als neuen In-Memory-State. Scheitert die Initialisierung, lautet das Ergebnis `POST_RESET_FAILED`; scheitert nur das Speichern, lautet es `STATE_SAVE_FAILED`. Im zweiten Fall ist die Welt bereits regeneriert und initialisiert, es gibt aber kein `SUCCESS` und der zuvor veröffentlichte State bleibt unverändert. Damit gilt die Invariante: `nextReset` wird nur nach einer vollständig erfolgreichen Pipeline fortgeschrieben.

`WorldsAccess.regenerate(...)` wird nicht in `FoliaFarmweltScheduler.runGlobal(...)` verpackt, da Worlds sein Global-/Folia-Scheduling selbst kapselt. Eigene kurze Bukkit-Prüfungen und asynchrone State-I/O verwenden weiterhin den Farmwelt-Scheduler. Fehler der Worlds-Future werden als `REGENERATE_FAILED` mit unveränderter Ursache abgebildet. Die interne `WorldOperationException.Reason`-API von Worlds wird bewusst nicht in Commands oder Business-Logik übernommen.

`ResetDueStateEvaluator` liefert je logischer Farmwelt `NOT_DUE`, `DUE` oder `DISABLED`. Nach einer festen Startup-Sicherheitsverzögerung von 60 Sekunden ermittelt `StartupResetCoordinator` damit die überfälligen Welten in stabiler Konfigurationsreihenfolge. Die separaten Startup-Beobachtungen werden per `thenCompose` sequenziell verkettet; vor dem Start jeder Welt wird deren aktueller State erneut mit derselben Due-Logik bewertet. Ein Fehler oder `ALREADY_RUNNING` wird verarbeitet, ohne die nächste Welt zu blockieren. Es gibt pro überfälliger Welt nur einen Versuch, nicht je verpasstem Intervall. Der Coordinator manipuliert keine States und besitzt keinen Reset-Lock.

Erst nach Ende dieser Catch-up-Kette startet `AutomaticResetScheduler` genau einen periodischen globalen Folia-Task. Er prüft danach alle 60 Sekunden zuerst Countdown-Warnungen und anschließend die persistenten `nextReset`-Zeitpunkte gegen dasselbe aktuelle `Instant`. Notification-Fehler werden pro Farmwelt beziehungsweise Broadcast geloggt und können weder den Due-Check noch den Start eines fälligen Resets verhindern. Für `now >= nextReset` gibt der Countdown-Pfad nichts aus. Nur bei `DUE` ruft der Scheduler asynchron `FarmworldResetExecutor.resetIfDue(...)` ohne Dragon-Override auf. Die Engine prüft den Termin nach dem Lock-Erwerb nochmals. Mehrere im regulären Tick fällige Welten werden weiterhin unabhängig angestoßen. Die Engine bleibt mit ihrem `runningResets`-Lock die einzige Schutzinstanz gegen parallele Resets derselben Farmwelt, weshalb `ALREADY_RUNNING` ohne zusätzlichen Lifecycle-Broadcast behandelt wird. Erfolg und echte Fehler werden kompakt geloggt; synchrone Startfehler und exceptional Futures beenden weder den periodischen Task noch die Verarbeitung anderer Farmwelten. Nur die Engine aktualisiert nach vollständigem Erfolg `lastReset` und `nextReset`; bei Fehlern bleibt der fällige State unverändert. Manuelle Force-Resets umgehen den Scheduler weiterhin und erzeugen daher keine Countdown-Serie, erhalten über dieselbe Engine aber dieselben Start-/Erfolgs-/Fehlermeldungen wie automatische und Startup-Resets.

### Recovery an der Worlds-Grenze (FW-002)

Vor FW-002 überdauerte ausschließlich der Zeitplan einen Prozessabbruch. Zwischen dem ersten Worlds-Aufruf und der Veröffentlichung des neuen Plans konnte derselbe überfällige Termin deshalb einen zweiten destruktiven Versuch auslösen. Der Lock gilt bis zum tatsächlichen Pipeline-Abschluss. Das Journal überbrückt einen Fehler oder das Ende des Prozesses.

| Übergang | Dauerhafter Nachweis | Verhalten nach Fehler, Stop oder Neustart |
| --- | --- | --- |
| Anforderung, Fälligkeit, Lock, Config-/Dragon-Snapshot | Bisheriger Plan und bisheriges Journal | Lock, Optionen und Snapshot sind flüchtig. Ein neuer Versuch durchläuft alle Prüfungen. |
| Weltprüfung, Evakuierung, Leerprüfung | Bisheriger Plan | Ein erneuter Versuch prüft die aktuelle Spielerbelegung. Bereits erfolgte Teleports können bestehen bleiben. |
| Evakuierung und Leerprüfung bestanden, unmittelbar vor Worlds | `WORLDS_PENDING` mit UUID, logischer ID, Bukkit-Name und Startzeit | Erst nach bestätigtem Schreiben gelangt die Welt an Worlds. Fehler beim Schreiben stoppen die Übergabe. Nach einem Crash in diesem Fenster sperrt bereits der vorbereitete Versuch. |
| Letzte Prüfung nach dem Journal-Flush scheitert | Die laufende Pipeline kennt den Vorgänger und hat Worlds noch nicht aufgerufen. | Unter dem Laufzeit-Lock prüft die Rücknahme denselben Versuch im Speicher und auf der Platte. Sie ersetzt ihn atomar durch den vorherigen Platz (`READY` oder Abschlussbeleg). Schreibfehler sperren die Welt. Beim Neustart entscheidet der vorgefundene dauerhafte Stand. |
| Entladen, Entfernen und Neuerstellen durch Worlds | `WORLDS_PENDING` | Ein ausstehendes, fehlerhaftes oder unbrauchbares Future lässt das tatsächliche Ergebnis offen. Die Sperre bleibt bestehen. |
| Worlds liefert eine andere Instanz, Validierung und Post-Reset laufen | `WORLDS_RETURNED` | Ein Fehler bei Weltprüfung, Gamerules, Border oder Dragon-Policy sperrt die Welt. Bei Fehler im Phasenwechsel bleibt die vorherige Bestätigung maßgeblich. |
| Initialisierung bestätigt, Zeitplan noch offen | `POST_RESET_DONE` mit exaktem Abschluss und Folgetermin aus dem Intervall-Snapshot | Der Plan wird als Kandidat gespeichert. Bei `STATE_SAVE_FAILED` bleibt der veröffentlichte Plan erhalten. Eine administrative Nachtragung ist anhand des Journals möglich. |
| State-Save abgeschlossen, Veröffentlichung, Benachrichtigung und Lock-Freigabe | `POST_RESET_DONE` und passende `attempt-id`, `last-reset`, `next-reset` im Weltabschnitt | Auch ein Prozessende direkt nach dem Dateiersatz hinterlässt einen erkennbaren Abschluss. Alte UUIDs oder abweichende Termine sperren die Welt. |

`ResetRecoveryJournal` liest die drei Plätze beim Service-Start. Scheduler und Commands prüfen den veröffentlichten Speicherstand. Vor jedem Phasenwechsel vergleicht der asynchrone Schreiber zusätzlich den erwarteten Vorgänger mit der Datei. Die Vorbereitung prüft Sperre und Vorgänger unter demselben Schreiblock. Das Journal verwendet einen festen Platz pro `FarmworldType`, auch bei unterschiedlich geschriebener ID. Ein lesbarer offener Versuch schützt zusätzlich seinen Bukkit-Namen bei einer geänderten Zuordnung. Die ID aus einer älteren Journaldatei wird für den Belegvergleich ihrem geprüften Typ zugeordnet. Die Rücknahme vor Worlds verwendet weiterhin den originalen Vorgänger einschließlich seiner Schreibweise.

Beim Lesen von `reset-state.yml` werden eindeutige bekannte IDs im Speicher kanonisiert. Mehrdeutige Abschnitte werden vor der Auswahl eines Termins anhand ihrer YAML-Schlüssel erkannt. Ihre ursprünglichen Einträge bleiben beim Speichern unabhängiger Zeitpläne erhalten. Der erste reguläre Replace schreibt eindeutige IDs kanonisch. Vor und nach diesem Replace erkennt derselbe Journalvergleich die Versuchs-UUID und beide Zeitstempel. Ein Start in einem dieser beiden Zustände benötigt keinen weiteren Migrationsschritt.

Die interne Pipeline besitzt logische und physische Reservierung sowie Scope. Commands erhalten eine Future-Kopie. Der Startup-Koordinator beobachtet jeden laufenden Versuch separat. Er beendet die Beobachtung bei Ergebnis, Stop oder nach 12 000 globalen Ticks. Ein verspäteter Abschluss erreicht ausschließlich die ursprüngliche Pipeline. Startup und Scheduler überspringen weiterhin `RECOVERY_BLOCKED`.

### Scheduler- und Future-Verträge (FW-007)

| Operation und Kontext | Besitzer, Abschluss und mögliche Seiteneffekte |
| --- | --- |
| Weltprüfung, Leerprüfung und Prüfung der neuen Instanz, global | Der Scheduler besitzt das Callback-Future. Name, Seed, Dimension und Ordner werden im globalen Kontext geprüft. Vor und nach dem Journal-Flush wird die Belegung geprüft. |
| Evakuierung, global und Entity | Globaler Snapshot der Spieler und des Zielspawns. Der Entity-Callback prüft die aktuelle Welt und startet den Teleport. Ein Regionswechsel bleibt beim Entity-Scheduler. Retirement und unmittelbare Ablehnung liefern getrennte Fehler. |
| Teleport, externe API | Das Teleport-Future gehört Paper. Die Pipeline wartet auf sämtliche Ergebnisse, auch bei einem anderen bereits fehlgeschlagenen Spieler. Ein offenes Future hält Scope und Reservierung. Ein verlorenes Ergebnis durch Cancellation oder fehlendes Future sperrt einen neuen Versuch im laufenden Prozess. |
| Journal und Zeitplan, async | Eigene Tasks führen Prepare, besitzergebundene Rücknahme, Phasenwechsel und abschließenden Save aus. Nach Start besitzt der Callback den Schreibvorgang bis zu dessen Rückkehr. Beim Stop nach Prepare kann WORLDS_PENDING auch ohne Worlds-Aufruf zurückbleiben. |
| Regeneration, Worlds | Der Adapter delegiert direkt. Das fremde Future und seine Lifecycle-Seiteneffekte bleiben bei Worlds. Das Journal sperrt Wiederholung nach Fehler oder fehlendem Ergebnis. Der späte Erfolg kann die ursprüngliche Pipeline im aktiven Lifecycle einmal fortführen. |
| Post-Reset, global, Region und Entity | Gamerules und Border laufen global, bestehende End-Operationen in ihren vorgesehenen Kontexten. Chunk-Laden bleibt eine externe Operation. Eigene verzögerte Verifikation und Entity-Entfernung verwenden denselben Scheduler. Die Versionsgrenze 26.1.2 gilt vor Evakuierung. |
| Zustellung und Cleanup | Broadcast-Empfänger werden global ermittelt, Zustellung erfolgt im Entity-Kontext. Retirement einer akzeptierten persönlichen Nachricht erlaubt ihr Auslassen. Scope-Cleanup und Freigabe laufen an der internen Completion und benötigen keinen Bukkit-Kontext. |
| Startup und periodischer Tick, global | Höchstens ein Beobachtungstimer pro Startup-Kette. Die Generation schützt vor Reaktivierung nach Stop. Der periodische Tick prüft auch manuell gestartete Versuche mit monotoner Laufzeitmessung und protokolliert die erste Überschreitung. |

Ein eigener Task ist bis zum atomaren Entfernen aus der Wartemenge ungestartet. Genau ein Pfad gewinnt zwischen Callback, Retirement, Rejection und Shutdown. Danach besitzt der Callback den Abschluss. Geprüfte Exceptions, Runtime-Exceptions und LinkageError erreichen das Future mit Ursache. Fatale VM-Fehler propagieren aus dem Scheduler-Callback. Aufrufer erhalten eine Kopie. Ein bereits laufender Task darf seine Seiteneffekte beenden. Für einen nach Stop noch zugestellten wartenden Callback ist das Ausführungsrecht bereits erloschen. Nicht-asynchrone Future-Fortsetzungen können auf dem registrierenden oder dem abschließenden Thread laufen.

Die zehn Minuten Beobachtungszeit lassen bei 20 TPS doppelt so viel Zeit wie das reguläre 300-Sekunden-Resetfenster des Smoke-Tests. Tick-Verzögerungen verlängern die Startup-Wartezeit. Der Laufzeit-Watchdog verwendet `System.nanoTime()`. Eine überschrittene Beobachtungsfrist beendet keine Worlds-Regeneration und gibt keine Reset-Reservierung frei.

Worlds 4.5.1 verwendet weltbezogene Builder und Dateipfade, greift beim Entladen aber auch auf den serverweiten Pausenzustand zu. Die API dokumentiert keinen Isolationsvertrag für eine zweite Regeneration während eines ungeklärten Handoffs. Daher überlappt eine Startup-Übergabe keine andere offene Worlds-Übergabe. Die Engine prüft den Konflikt vor der Reservierung und nach der letzten Leerprüfung. Der reguläre Betrieb behält unabhängige Resets bei und stellt neue Handoffs bei einem ungewöhnlich langen offenen Worlds-Versuch zurück. Der periodische Scheduler bleibt aktiv. [WorldsAccess 4.5.1](https://github.com/TheNextLvl-net/worlds/blob/4.5.1/api/src/main/java/net/thenextlvl/worlds/WorldsAccess.java) · [WorldsPlugin](https://github.com/TheNextLvl-net/worlds/blob/4.5.1/plugin/src/main/java/net/thenextlvl/worlds/WorldsPlugin.java) · [PaperLevelView](https://github.com/TheNextLvl-net/worlds/blob/4.5.1/plugin/src/main/java/net/thenextlvl/worlds/view/PaperLevelView.java)

Unmittelbar vor dem synchronen Adapteraufruf entscheidet derselbe Monitor wie in `stop()` zwischen Deaktivierung und `NOT_STARTED → ACCEPTED`. Dieser Übergang erteilt das unwiderrufliche Ausführungsrecht für genau einen Aufruf. Danach kann ein Threadwechsel die tatsächliche Ausführung bis nach dem Stop verzögern. Der Adapter darf diesen bereits übernommenen Aufruf zu Ende führen. Der Monitor ist schon beim Aufruf frei. Die Engine erwirbt ihn zur Veröffentlichung des Abschlusszustands erneut. Gewinnt der Stop zuerst, bleibt Worlds unberührt. Nur solange der Versuch `NOT_STARTED` ist, kommt eine besitzergebundene Journalrücknahme infrage. Verhindert der Stop den Rücknahme-Task, bleibt `WORLDS_PENDING` erhalten.

Worlds führt die Regeneration auf einem zulässigen Tick-Thread direkt aus. Beim Scheduling auf die Globalregion liefert `supplyGlobal` ein separates Future, in das es Erfolg oder Fehler der inneren Kette überträgt. Ein Veto des Regenerate-Events oder ein abgelehnter Unload beendet die Kette mit einer Exception. Damit endet die Koordination zwischen Welten. Das Journal sperrt weiter das betroffene Ziel. Auch ein regulär gelieferter, aber ungültiger Weltwert beendet die Beobachtung und scheitert an der Ergebnisprüfung. [Worlds 4.5.1, supplyGlobal](https://github.com/TheNextLvl-net/worlds/blob/4.5.1/plugin/src/main/java/net/thenextlvl/worlds/WorldsPlugin.java#L152-L167) · [Regeneration und Fehlerpfade](https://github.com/TheNextLvl-net/worlds/blob/4.5.1/plugin/src/main/java/net/thenextlvl/worlds/WorldsPlugin.java#L384-L412)

Eine Cancellation des Original-Futures kann laufende Arbeit von ihrem Ergebnis trennen. Ein synchroner Wurf, ein fehlendes Future, ein gescheiterter Beobachteranschluss oder ein gemeldeter Beobachtungs-Timeout lassen das Ende ebenfalls offen. In diesen Fällen behält die Engine die Reservierung und stellt sofort alle weiteren Worlds-Übergaben im laufenden Prozess zurück. Bereits übernommene andere Operationen laufen weiter. Bei einem vorbereiteten DragonBattle-Datenreset bleiben die bereitgestellten Daten und deren Sicherung für die Klärung erhalten. Ein weiterhin offenes, beobachtbares Future kann dagegen später noch genau einmal einen Abschluss melden. Das Ergebnis belegt nur den Abschluss der Worlds-Kette. Intern lediglich protokollierte Dateifehler bleiben Gegenstand von FW-006.

Bei `EntityScheduler.execute` bedeutet false, dass keiner der beiden Callbacks folgt. Bei Annahme erhält die Entity regulär genau einen Ausführungs- oder Retirement-Callback. Im kritischen Retirement-Kontext signalisiert Farmwelt das Ergebnis. [Paper EntityScheduler](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html)

**Dateivertrag.** Im Plugin-Datenordner kennzeichnet `reset-recovery.version` die eingerichtete Formatversion 1. Die drei Dateien `reset-recovery-{overworld,nether,end}.journal` enthalten UTF-8-Zeilen in fester Reihenfolge. Ein unbenutzter Platz enthält `version=1` und `phase=READY`. Bei einem Versuch folgen auf Version und Phase die Felder `id`, `key`, `world`, `started`, `completed` und `next`. Zeiten sind ISO-8601-Instants. Die letzten beiden Felder bleiben bis zur bestätigten Initialisierung leer. Pro Welt bleibt der letzte Beleg erhalten, bis ein zulässiger neuer Versuch ihn ersetzt. Fehlende Pflichtfelder, unbekannte Versionen, widersprüchliche Belege und nicht lesbare Plätze sperren die betroffene Welt. Ein fehlender oder ungültiger gemeinsamer Versionsmarker bei bestehender Recovery-Historie erfordert die Prüfung des gesamten Dateisatzes.

Der Schreiber synchronisiert die temporäre Datei mit `SYNC` und fordert für das Journal einen atomaren Replace im selben Verzeichnis an. Der Zeitplan behält seinen bisherigen Replace-Fallback. Nach jedem Replace wird unter Unix das Elternverzeichnis synchronisiert. Der Windows-NIO-Provider bietet dafür keinen Verzeichniskanal. Prozessneustarts sind abgedeckt. Bei Stromausfall hängt die Dauerhaftigkeit von Dateisystem, Controller und Schreibcache ab, unter Windows zusätzlich von der Persistenz der Verzeichniseinträge. Der Vertrag setzt einen einzelnen Serverprozess pro Datenordner und ein kohärentes lokales Dateisystem voraus. Ein gemeinsamer Verlust oder ein gemeinsames Zurückrollen aller Nachweise lässt sich aus den Dateien selbst nicht erkennen.

## Endfarm- und DragonBattle-Vertrag

Auf Folia 26.3 ist ausschließlich der `PRESERVE`-Pfad ohne Dragon-Eingriff verfügbar. Die nachfolgenden DragonBattle-Verträge beschreiben die interne Freigabe für 26.1.2.

Die Engine prüft die wirksame Policy vor Eröffnung des Reset-Scopes. Regeneration und Initialisierung verwenden dieselbe Policy-Funktion mit dem unveränderlichen Reset-Snapshot und dem einmaligen Override. Ohne End-Abschnitt benötigt nur `--dragon` die Versionsfreigabe für den geladenen Kampf. Der Worlds-Adapter schreibt in diesem Fall keine DragonBattle-Saved-Data. Bei konfigurierter Policy prüft er beim Staging im Unload-Event nochmals die Freigabe und die registrierte Weltinstanz.

Beim Start und auf der Globalregion prüft der Initialisierer die geordnete Bukkit-Weltliste mit demselben Hauptweltschutz wie die Reset-Engine. Ein Unload-Event entzieht der betroffenen Instanz zunächst die Freigabe. Worlds 4.5.1 kann danach noch asynchron speichern oder beim Entfernen scheitern. Der Abgleich im nächsten globalen Tick übernimmt deshalb den tatsächlichen Registrierungsstand. Eine noch offene einmalige Spawn-Freigabe gehört weiterhin ausschließlich derselben Weltinstanz.

Vor einem Eingriff prüfen die Callbacks zusätzlich über den Dimensionskey, ob die validierte Instanz und mindestens eine ihr zuvor vorangehende Welt noch registriert sind. So bleibt auch eine erst später zur Hauptwelt aufgerückte Endfarm geschützt. Der Key-Lookup verwendet auf 26.1.2 die Level-Tabelle des Servers. Regions- und Entity-Callbacks lesen keine Bukkit-Weltliste. Der Portal-Callback prüft außerdem die Policy-Revision und bezieht den aktuellen DragonBattle in der End-Ursprungsregion. Auch ein bereits abgebrochenes `DragonEggFormEvent` erreicht die Portalreparatur. Die globale Inspektion aktiver Drachen nach dem Reset gehört zu FW-005.

Die Endfarm verwendet bewusst versionsgebundene Bukkit-/CraftBukkit-/Minecraft-Zugriffe. `EndDragonFightCompatibility` begrenzt die freigegebene Serverversion, `EndDragonFightRuntimeAccess` kapselt DragonBattle-Zustand und Bossbar, und `EndDragonFightDataStore` staged die betroffenen Saved-Data-Änderungen mit Commit/Rollback. `BukkitFarmworldPostResetInitializer` koordiniert außerdem Spawn-Guard, Portal-Erzeugung und spätere Portal-Verifikation in der zuständigen Region.

Für `post-reset.end.dragon: false` setzt die Initialisierung den Fight-State auf abgeschlossen, entfernt einen vorhandenen Drachen, unterdrückt die Bossbar und stellt ein aktives Exit-Portal her. Vor der ersten Portal-Erzeugung wird der zentrale End-Chunk vollständig geladen; die Brunnenbasis wird anschließend in der zuständigen Region über `MOTION_BLOCKING_NO_LEAVES` aus der tatsächlichen Inseloberfläche ermittelt. Der Spawn-Guard verhindert verzögerte Erstspawns. Eine echte Vanilla-Wiederbeschwörung mit vier Endkristallen bleibt erlaubt; sie zählt nicht als Erstkampf. Nach dem Tod dieses Respawn-Drachens wird das aktive Portal in der End-Ursprungsregion erneut hergestellt und verifiziert.

Für `dragon: true` oder den einmaligen Command-Override `--dragon` wird ein frischer Vanilla-Erstkampf mit aktiver Bossbar vorbereitet. Der Drache darf erscheinen, das Portal ist zunächst inaktiv und wird nach dem Kampf aktiv; das Drachenei-Erstkampfverhalten bleibt erhalten. `--dragon` verändert den Config-Snapshot für zukünftige Resets nicht. Wenn die dauerhafte Config `dragon: false` enthält, existiert die einmalige Spawn-Freigabe nur bis zum tatsächlichen Vanilla-Spawn; anschließend greift wieder die konfigurierte Suppression.

Diese Logik darf bei Minecraft-/Folia-Upgrades nicht als gewöhnliches Entity-Cleanup behandelt werden. Datenlayout, reflektierte Felder und Methoden, Saved-Data-Staging, Portal, Kristall-Respawn, Bossbar und Drachenei benötigen eine bewusste Kompatibilitätsprüfung.

### Datei- und Lifecycle-Zustände (FW-009)

Die folgende Matrix unterscheidet den Inhalt am aktiven Pfad von den Originalbytes im Versuch. Ein erfolgreiches Unload-Event belegt nur die Auslieferung an den Listener. Weitere Listener können den Unload abbrechen. Worlds kann Fehler nach der Entladung intern protokollieren.

| Übergang | Original am Ziel | Sicherung | Temporär | Vorbereitet am Ziel | Bukkit-Instanz / Worlds | Journal | Zeitplanbeleg dieses Versuchs |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Vor Staging | falls vorhanden | – | – | – | alt, Übergabe begonnen | WORLDS_PENDING | – |
| Vorbereitung | falls vorhanden | ggf. angelegt | neue NBT | – | alt, im Unload-Event | WORLDS_PENDING | – |
| Original aus Ziel genommen | – | einzige Quelle der Originalbytes, ggf. mehrere Namen desselben Inodes | neue NBT | – | alt, vor Rückkehr des Events | WORLDS_PENDING | – |
| Staging veröffentlicht | – | falls Original vorhanden | Referenz auf vorbereitete Datei | ja | Unload offen, Veto noch möglich | WORLDS_PENDING | – |
| Fehler oder Beobachtung verloren | unbekannt | falls erhalten | falls erhalten | unbekannt | alt, ersetzt oder ungeklärt | WORLDS_PENDING | – |
| Neue Instanz gemeldet | – | falls erhalten | falls erhalten | ggf. von Minecraft geändert | neue Instanz, Validierung offen | WORLDS_RETURNED | – |
| Initialisiert | – | falls erhalten | falls erhalten | Daten der neuen Generation | neue Instanz validiert | POST_RESET_DONE | fehlt oder widersprüchlich |
| Zeitplan gespeichert | – | Cleanup zulässig | Cleanup zulässig | Daten der neuen Generation | neue Instanz bestätigt | POST_RESET_DONE | passende UUID und Termine |

Im Ausgangscode entfernte der Rollback die aktive Datei vor der Sicherungsprüfung. Der Fehlerpfad beim Staging löschte sogar beide Zielnamen ohne Eigentumsnachweis. Ein unterbrochener Move, ein belegter Zielname oder eine fehlende Sicherung konnten damit die letzte nutzbare Datei kosten. Auch das Cleanup nach bloßer Worlds-Rückkehr konnte die Sicherung vor der Prüfung der neuen Instanz verbrauchen.

Der Versuch erhält einen exklusiven Arbeitsordner neben der aktiven Datei. Die Namen darin tragen die FW-002-UUID. Eine temporäre Datei wird vor dem Eingriff vollständig geschrieben und synchronisiert. Beim Austausch wird der bisherige Eintrag atomar auf einen freien Platz im Arbeitsordner verschoben und dort erneut geprüft. Ein Hardlink veröffentlicht die eigene Datei ausschließlich an einem freien Ziel. Die Rücknahme setzt eine belegte Herkunft, unveränderte Inhalte und einen bestätigten Abbruch vor der Entladung voraus. Unklare Rückgaben und fremde Einträge bleiben zur Untersuchung erhalten. Beim Neustart gibt es keinen dateinamengesteuerten Restore. Journal und Zeitplan behalten ihr Schema.

Der exklusive Ordner `ender_dragon_fight.dat.farmwelt-stage` sperrt weitere Stagings auch bei ursprünglich fehlender Datei. Nach einem Stage-Fehler bleiben Teildateien zur Diagnose erhalten. Nur im noch laufenden Unload-Handler darf eine verifizierte Originalreferenz einen inzwischen freien Zielnamen wiederherstellen. Bereits belegte Namen bleiben erhalten. Zusätzliche Rücknahmefehler hängen an der ursprünglichen Exception.

**Folia-Kontexte.** Worlds 4.5.1 wertet das Unload-Event vor dem Entfernen der Welt aus. Das Legacy-Staging muss vor der Rückkehr aus diesem Event fertig sein. Es schreibt eine kleine komprimierte NBT und begrenzt das Lesen bestehender Daten auf 1 MiB. Ein späteres Veto wird erst nach terminalem `UNLOAD_FAILED` ausgewertet. Die Hash-Vorprüfung läuft async. In der Global-Aufgabe werden dieselbe registrierte Altinstanz, das endgültige Veto, weitere Unloads, Dateipaare und die tatsächlich verschobenen Einträge geprüft. Nur dort darf der aktive Name zurückkehren. Für die letzte Inhaltsprüfung im globalen Kontext gilt ein Original-Limit von 4 KiB. Größere Originale bleiben mit Sicherung zur Offline-Prüfung erhalten. Verzeichnis-Flush und Cleanup nach der Rücknahme laufen async. Beim Stop verfallen wartende Scheduler-Aufgaben gemäß FW-007. [Folia-Unload in Worlds 4.5.1](https://github.com/TheNextLvl-net/worlds/blob/4.5.1/plugin/src/main/java/net/thenextlvl/worlds/view/FoliaLevelView.java)

**Dateisystem.** Exklusive Ordnererstellung und Hardlinks im selben Dateisystem sichern freie Namen und die Identität der Einträge. Jeder Rename belegt einen freien Platz im versuchseigenen Ordner mit `ATOMIC_MOVE`. Ein nichtatomarer Fallback oder `REPLACE_EXISTING` würde die Erhaltung eines zwischenzeitlich belegten Ziels gefährden. Fehlende Unterstützung führt zum Abbruch. SHA-256, Dateiattribute, reale Pfade und `isSameFile` ergänzen sich. Auf Unix werden bereits vor dem Staging mehrfach verlinkte Originale abgelehnt. Der Windows-Provider stellt die Linkanzahl über diese API nicht bereit. Bestehende Dateien werden auf keiner Plattform zum Schreiben geöffnet.

**Dauerhaftigkeit.** Die vorbereitete Datei wird mit `FileChannel.force(true)` synchronisiert. Für Verzeichniseinträge nutzt Farmwelt die bestehende Verzeichnissynchronisation. Der Windows-Provider unterstützt diese über NIO nicht. Mehrere Namen, Welt, Journal und Zeitplan lassen sich auf keiner Plattform als gemeinsame Transaktion speichern. Ein Prozessabbruch kann jeden Zwischenstand hinterlassen. Selbst ein erfolgreicher Flush ist nur so belastbar wie Dateisystem, Datenträger und Cache. Der Baum muss vom Server exklusiv verwaltet werden. Java NIO bietet plattformübergreifend keinen an einen erwarteten Inode gebundenen Rename oder Unlink gegen einen gleichzeitig schreibenden fremden Prozess.

**Abschluss.** Das Reset-Ergebnis wird erst nach der vorhandenen FW-002-Persistenz bestätigt. Im selben Async-Task erhält ausschließlich der Eigentümer des passenden Belegs das Cleanup. Es prüft den global validierten Weltordner, die aktive Datei und die Identität der Versuchsdateien. Ein bereits gespeicherter Reset kann eine gesonderte Cleanup-Warnung haben. Ein fehlgeschlagener Cleanup-Versuch löst keine weitere automatische Löschung aus. Der nächste Stage erkennt die Reste. Bei Post-Reset- oder Speicherfehler erhält die Pipeline kein Cleanup-Recht.

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
- `FarmweltMenuLore`
- `FarmweltMenuHolder`
- `FarmweltMenuItem`
- `FarmweltGuiListener`

Ablauf:

1. Spieler führt `/farmwelt` aus.
2. `FarmweltCommand` prüft `farmwelt.use`.
3. `FarmweltMenu.open(player)` erstellt ein Inventory mit 45 Slots.
4. Farmwelt-Einträge aus der Config werden in den Inhaltsbereich gelegt. `FarmweltMenuLore` ergänzt ihre Beschreibungen um den aktuellen Reset-Termin, die Restzeit beziehungsweise den Reset-Status und den passenden Teleport-Hinweis.
5. Statische Items wie Info- und Schließen-Item werden ergänzt.
6. `FarmweltGuiListener` bricht Klicks und Drags in der GUI ab, damit Items nicht entnommen werden können.
7. Klick auf einen Farmwelt-Eintrag ruft `FarmweltTeleportService.teleport(...)` auf.

Die Config-Slots der Farmwelt-Einträge beziehen sich auf den internen Inhaltsbereich mit 27 Slots. Im Inventory wird ein Offset verwendet, damit die Einträge optisch im mittleren Bereich liegen.

`FarmweltMenuLore` erhält den bestehenden `FarmworldResetService`, die `FarmworldAvailabilityService`-Sicht der Reset-Engine, eine `Clock` und die Server-Zeitzone vom Composition Root. Die Zuordnung erfolgt ausschließlich über `FarmweltMenuItem.id()`. Config und State werden gemeinsam unter dem Service-Monitor gelesen, damit kein paralleler Reload die beiden Snapshots vermischt. Ein laufender Reset hat Vorrang vor Zeitplan und deaktivierter oder entfernter Config. Die Anzeige verwendet ausschließlich den veröffentlichten `nextReset`, ohne YAML-Zugriffe, eigene Terminberechnung aus dem Intervall oder mutierende Reset-Aktionen.

Die verbleibende Dauer wird beim Aufbau der Item-Beschreibung mit dem vorhandenen `GermanDurationFormatter` formatiert. Warnfarben gelten bei höchstens einer Stunde (gelb) und höchstens fünf Minuten (rot). Das Menü aktualisiert sich beim erneuten Öffnen; es gibt keinen zusätzlichen periodischen Task. Die bestehenden Folia-Kontexte für Inventory-Zugriffe und beide Reset-Lock-Prüfungen im Teleport-Service bleiben maßgeblich. Der Info-Kompass erläutert den Verlust platzierter Blöcke und gelagerter Items beim Reset.

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
- Wird als `compileOnly("net.thenextlvl:worlds:4.5.1")` aus `https://repo.thenextlvl.net/releases` kompiliert und nicht in die Farmwelt-JAR geshadet.
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
