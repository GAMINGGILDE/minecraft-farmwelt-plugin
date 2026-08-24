# V2-Abnahmevorlage

Diese Datei ist die wiederverwendbare Vorlage für das Abnahmeprotokoll eines konkreten manuellen Release-Candidate-Testlaufs der vollständigen V2-Integration von Farmwelt, Folia, Minecraft und Worlds. Die Repository-Version bleibt absichtlich im Ausgangszustand `NOT RUN` und wird nicht als Testergebnis gepflegt. Für einen realen Testlauf ist eine Kopie beziehungsweise ein separates Abnahmeprotokoll anzulegen und auszufüllen. `NOT RUN` in dieser Template-Datei bedeutet nicht, dass das Repository oder die Implementierung unfertig ist.

Die Durchführung und die vollständigen PASS-Kriterien stehen in [`black-box-testing.md`](black-box-testing.md). Der separate [automatisierte Folia-/Worlds-Smoke-Test](../../testing/blackbox/README.md) deckt einen echten Overworld-Reset ab, setzt die manuellen BB-01-bis-BB-26-Statuswerte aber nicht automatisch auf `PASS`.

Die Statuswerte bedeuten:

| Status | Bedeutung |
| --- | --- |
| `NOT RUN` | Noch nicht ausgeführt. |
| `PASS` | Alle fachlichen Kriterien und das Log-Gate sind erfüllt; Evidenz ist hinterlegt. |
| `FAIL` | Mindestens ein fachliches Kriterium oder das Log-Gate ist verletzt. |
| `BLOCKED` | Der Test war aus einem dokumentierten externen Grund nicht sicher oder vollständig ausführbar. |

Ein Szenario darf nur `PASS` erhalten, wenn sein Serverlog frei von relevanten unerwarteten Folia-, Threading-, Scheduler- und Worlds-Lifecycle-Fehlern ist. Erwartete Meldungen eines bewusst injizierten Fehlerfalls müssen in der Notiz klar von unerwarteten Exceptions getrennt werden.

## Matrix

| ID | Szenario | Erwartung | Status | Notiz | Evidenz |
| --- | --- | --- | --- | --- | --- |
| BB-01 | Manueller Overworld-Reset | Genau eine sichere Regeneration; Evakuierung, Validierung, Post-Reset und State-Persistenz vollständig erfolgreich. | `NOT RUN` | — | — |
| BB-02 | Manueller Nether-Reset | Geladene Nether-Testwelt wird genau einmal mit korrektem Namen und korrekter Dimension vollständig zurückgesetzt. | `NOT RUN` | — | — |
| BB-03 | Manueller End-Reset ohne Dragon | Manuell und automatisch: kein Drache/Bossbar, aktives benutzbares Portal, kein später Erstspawn; Kristall-Respawn und Portal nach Tod funktionieren. | `NOT RUN` | — | — |
| BB-04 | Manueller End-Reset mit `--dragon` | Frischer Erstkampf mit Bossbar, Drache, zunächst inaktivem Portal und Drachenei; danach Portal aktiv und `dragon: false` unverändert. | `NOT RUN` | — | — |
| BB-05 | Automatischer Reset | Kurzintervall löst bei Fälligkeit genau einen Reset ohne Dragon-Override aus; Folgetermin basiert auf erfolgreichem Abschluss. | `NOT RUN` | — | — |
| BB-06 | Restart-Catch-up | Nach 60 Sekunden genau ein Catch-up für fälligen State; zukünftiger exakter Termin überlebt den Neustart. | `NOT RUN` | — | — |
| BB-07 | Mehrere fällige Welten | Fällige Welten werden in Config-Reihenfolge vollständig nacheinander verarbeitet; ein Weltfehler stoppt die folgenden nicht. | `NOT RUN` | — | — |
| BB-08 | Doppelreset | Zweiter Aufruf ist `ALREADY_RUNNING`; nur ein Lifecycle/eine Regeneration, Lock danach frei und späterer Reset möglich. | `NOT RUN` | — | — |
| BB-09 | Teleport während Reset | GUI-Teleport in die gelockte logische Farmwelt bleibt blockiert und ist nach Reset wieder verfügbar. | `NOT RUN` | — | — |
| BB-10 | Reload während Reset | Laufender Reset und Lock bleiben intakt; alter Snapshot gilt für ihn, neue Config nur zukünftig, kein zweiter Scheduler. | `NOT RUN` | — | — |
| BB-11 | Reload-Intervall | Persistenter Termin bleibt beim Reload unverändert; neues Intervall gilt erst für den Folgetermin nach Erfolg. | `NOT RUN` | — | — |
| BB-12 | Countdown-Warnungen | Schwellen erscheinen geordnet und je `next-reset`-Termin höchstens einmal; Neustart holt höchstens die aktuell relevante Warnung nach. | `NOT RUN` | — | — |
| BB-13 | Reset-Startmeldung | Genau bei akzeptiertem Pipeline-Start sichtbar; keine Meldung bei `NOT_CONFIGURED`, `DISABLED` oder `ALREADY_RUNNING`. | `NOT RUN` | — | — |
| BB-14 | Reset-Erfolgsmeldung | Erst nach Regeneration, Validierung, Post-Reset und erfolgreicher State-Persistenz; neuer `{next-reset}` stimmt. | `NOT RUN` | — | — |
| BB-15 | Reset-Fehlermeldung | Echter gestarteter Pipeline-Fehler meldet Fehler statt Erfolg; fachliche Abweisung erzeugt keine globale Fehlermeldung. | `NOT RUN` | — | — |
| BB-16 | Persönliche Evakuierungsmeldung | Nur tatsächlich erfolgreich Evakuierte erhalten sie einmal; Versandfehler beeinflusst den Reset nicht. | `NOT RUN` | — | — |
| BB-17 | Notification-Deaktivierung | Haupt- und Einzelschalter unterdrücken nur den jeweiligen Nachrichtentyp; Reset und Zeitplan laufen normal. | `NOT RUN` | — | — |
| BB-18 | Placeholder | `{world}`, `{time}` und `{next-reset}` werden in ihren unterstützten Meldungen korrekt aus Config und State gerendert. | `NOT RUN` | — | — |
| BB-19 | Gamerules nach Reset | Alle konfigurierten typisierten Gamerules sind nach erfolgreichem Post-Reset mit den erwarteten Werten aktiv. | `NOT RUN` | — | — |
| BB-20 | WorldBorder nach Reset | Konfigurierte WorldBorder-Größe ist nach erfolgreichem Post-Reset aktiv. | `NOT RUN` | — | — |
| BB-21 | Hauptwelt-Schutz | Als Hauptwelt erkannte Instanz wird vor Worlds-Regeneration sicher abgewiesen; State bleibt unverändert. | `NOT RUN` | — | — |
| BB-22 | Falsche Dimension | Geladene Welt falscher Dimension wird nicht regeneriert; kein Erfolg und keine State-Änderung. | `NOT RUN` | — | — |
| BB-23 | Nicht geladene Farmwelt | Keine Regeneration, kein falscher Erfolg und keine State-Änderung. | `NOT RUN` | — | — |
| BB-24 | Persistenzfehler | Nach erneuerter Welt folgt `STATE_SAVE_FAILED`; alter veröffentlichter/persistierter State bleibt und Erfolgsmeldung bleibt aus. | `NOT RUN` | — | — |
| BB-25 | Shutdown während Startup-Delay | Sauberer Stopp hinterlässt keinen Task; nächster Start hat genau eine Nachholung und einen Scheduler. | `NOT RUN` | — | — |
| BB-26 | Folia-/Worlds-Logprüfung | Logs aller Szenarien sind klassifiziert und frei von unerwarteten Threading-/Lifecycle-Fehlern sowie doppelten Reset-Starts. | `NOT RUN` | — | — |

## Testlauf-Protokoll

Für jeden realen Lauf eine eigene Kopie ausfüllen. Dateipfade oder Artefakt-Links dürfen in die Evidenzspalte der Matrix übernommen werden.

| Feld | Wert |
| --- | --- |
| Run-ID | |
| Datum/Uhrzeit und Zeitzone | |
| Tester | |
| Git-Commit | |
| Plugin-Version/JAR | |
| JAR-Prüfsumme | |
| Java-Version | |
| Minecraft-/Folia-Version | |
| Worlds-Version | |
| Testserver/Isolation und Backup | |
| Relevante `config.yml` | |
| Relevante `reset-state.yml` vor Test | |
| Relevante `reset-state.yml` nach Test | |
| Command beziehungsweise Trigger | |
| Reset-Startzeitpunkt | |
| Reset-Abschlusszeitpunkt | |
| Reset-Ergebnis | |
| Alter/neuer Seed | |
| Gamerules vor/nach Reset | |
| WorldBorder vor/nach Reset | |
| End-/Dragon-/Bossbar-/Portal-Zustand | |
| Relevante Serverlog-Ausschnitte | |
| Log-Gate-Ergebnis | |
| Gesamtstatus (`PASS`/`FAIL`/`BLOCKED`) | |
| Abweichung/Blocker | |

## Evidenzkonvention

Evidenz soll die Szenario-ID und Run-ID im Namen tragen, beispielsweise `V2-2026-08-20-BB-08-server.log`. Mindestens zu sichern sind der relevante Logzeitraum, Chat- oder Konsolenausgaben, Config- und State-Ausschnitte vor und nach dem Test sowie bei visuellen End-/GUI-Prüfungen ein Screenshot oder Video. Keine produktiven Welten, Zugangsdaten oder vollständigen produktiven Konfigurationen als Testartefakte verwenden.

In der ausgefüllten Laufkopie dürfen nach Abschluss keine `NOT RUN`-Einträge verbleiben. `BLOCKED` ist kein bestandenes Szenario; Grund, fehlende Voraussetzung und geplanter Nachtest müssen dokumentiert sein. Die V2-Gesamtabnahme ist nur bei `PASS` für alle BB-01 bis BB-26 erteilt.
