# Black-Box-Abnahme

Die wiederverwendbare V2-Abnahmevorlage mit den Testfällen BB-01 bis BB-26 befindet sich in
[`v2-acceptance-template.md`](v2-acceptance-template.md). Diese Anleitung beschreibt die Durchführung und die
fachlichen PASS-Kriterien; eine separate Laufkopie der Vorlage dokumentiert das Ergebnis des konkreten Testlaufs.

## Zweck und Testumgebung

Die Black-Box-Abnahme prüft die reale Integration von Folia, Minecraft, Worlds, der gebauten Plugin-JAR und dem vollständigen Reset-Lifecycle. Sie ergänzt die deterministischen Gradle-Tests und den vorhandenen [automatisierten Folia-/Worlds-Smoke-Test](../../testing/blackbox/README.md), ersetzt aber keinen davon. Der allgemeine Build-Workflow startet bewusst keinen Minecraft-Server; der echte Server-Smoke-Test läuft getrennt in `.github/workflows/blackbox.yml`.

Alle Szenarien werden ausschließlich auf einem isolierten, wegwerfbaren Testserver mit separat verwahrtem Testbackup und separaten Testwelten ausgeführt. Produktive Welten und produktive State-Dateien dürfen nicht verwendet werden. Vor jedem Lauf sind Plugin-JAR, Folia-, Minecraft- und Worlds-Version sowie die Ausgangswerte aus `config.yml` und `reset-state.yml` zu protokollieren. `reset-state.yml` nur bei gestopptem Server und nur für den gezielt getesteten Weltabschnitt bearbeiten oder entfernen. Die komplette Datei niemals als Kurztest-Anweisung auf einem produktiven Server löschen. Automatische Kurzintervalle wie `1m` sind ausschließlich für diesen isolierten Abnahmetest zulässig.

Für Tests mit manuell vorbereiteten Terminen eine frische isolierte Fixture mit `READY` verwenden. Nach einem erfolgreichen Reset müssen gespeicherter Termin und Journalbeleg zusammenpassen. Für eine weitere Ausgangslage eine separate Fixture oder den vollständigen konsistenten Vorher-Snapshot verwenden.

Vor den Reset-Szenarien muss Folia 26.3 Build 26 mit Farmwelt und Worlds 4.5.1 ohne Exceptions starten. Das Serverlog ist während aller Tests zusätzlich auf Folia-Thread-/Region-Fehler zu prüfen.

## Vorbereitung und Sicherheitsfreigabe

Vor jedem destruktiven Szenario gelten die [FW-004-Isolationsbedingungen](phase-2.2.md).

1. Sicherstellen, dass Serververzeichnis, Farmwelten und `plugins/Farmwelt/reset-state.yml` nur Testdaten enthalten.
2. Vollständiges, rückspielbares Backup des Testservers erstellen.
3. Die aus demselben Commit gebaute Plugin-JAR installieren und ihren Dateinamen, ihre Version und vorzugsweise ihre Prüfsumme protokollieren.
4. Die für Overworld, Nether und End vorgesehenen Testwelten laden und Weltname sowie Dimension gegen `config.yml` prüfen.
5. Worlds und alle Runtime-Abhängigkeiten in den protokollierten Versionen installieren.
6. Für jedes Szenario relevante Config- und State-Ausschnitte vor dem Start sichern. Manuelle State-Änderungen ausschließlich bei gestopptem Server und nur unter `worlds.<logische-id>` durchführen.
7. Mindestens einen Spieler und einen getrennten Administrator beziehungsweise eine zweite Testsitzung für Teleport-, Evakuierungs- und Benachrichtigungsprüfungen bereithalten.

## Verbindliches Log-Gate

Das vollständige Serverlog gehört bei jedem Szenario zur Evidenz. Zusätzlich zum sichtbaren Ergebnis ist es mindestens auf folgende Befunde zu prüfen:

- Folia Region-/Thread-Verstöße,
- Bukkit-Zugriffe aus einem ungeeigneten Thread,
- Scheduler-Exceptions,
- Worlds-Lifecycle-Exceptions,
- ungefangene `CompletionException` oder `IllegalStateException`,
- unerwartete `NullPointerException`,
- Fehler am DragonBattle-, Bossbar- oder Portal-State,
- wiederholte Reset-Starts oder Worlds-Regenerationen derselben logischen Farmwelt.

Ein fachlich korrekt wirkendes Szenario ist **FAIL**, sobald dabei ein relevanter unerwarteter Threading- oder Lifecycle-Fehler im Log entsteht. Bei absichtlich erzeugten Fehlerfällen müssen erwartete Plugin-Meldung und technischer Fehler dem Testschritt eindeutig zugeordnet werden; zusätzliche oder ungefangene Exceptions bleiben ein FAIL. Sensible Daten in archivierten Logausschnitten schwärzen, Zeitstempel und Szenario-ID jedoch erhalten.

Das automatisierte CI-Smoke-Harness behandelt genau eine testumgebungsspezifische Ausnahme: Langsame GitHub-Runner können während der Worlds-Welterstellung oder -Regeneration die Folia-Watchdog-Schwelle von fünf Sekunden knapp überschreiten. Nur der vollständige Global-Region-Dump mit dem bekannten Worlds-/Minecraft-`initWorld`-Stack, parsebarer Dauer von höchstens zehn Sekunden und höchstens je einem Create-/Regenerate-Vorkommen wird als `KNOWN / ALLOWED` separat archiviert. Andere, längere oder wiederholte Watchdogs sowie sämtliche zusätzlichen Exceptions und Threading-Verstöße bleiben FAIL. Diese Harness-Regel verändert die Produktionslogik und die Lifecycle-Verantwortung von Worlds nicht; für die manuelle Abnahme gilt weiterhin die Bewertung des konkreten Testsystems.

## Test A – Manueller Reset

1. In der isolierten Overworld-Farmwelt eine eindeutig erkennbare Teststruktur platzieren.
2. `/farmwelt reset force overworld` ausführen.
3. Prüfen, dass Spieler evakuiert werden, der Rückteleport während des Resets blockiert ist und Worlds die Welt genau einmal regeneriert.
4. Prüfen, dass die Teststruktur verschwunden ist, Weltname und Dimension stimmen sowie konfigurierte Gamerules und WorldBorder angewendet wurden.
5. Prüfen, dass `last-reset` und `next-reset` erst nach vollständigem Erfolg geschrieben werden und `/farmwelt status overworld` anschließend `Geplant` sowie `Reset läuft: Nein` zeigt.

Den grundlegenden Lifecycle danach mit getrennten Nether- und End-Testwelten wiederholen.

## Test B – Automatischer 1-Minuten-Reset

Für genau eine Testwelt `reset.enabled: true` und `interval: "1m"` konfigurieren. Eine Intervalländerung verschiebt einen vorhandenen persistenten `next-reset` ausdrücklich nicht. Für den kontrollierten Kurztest daher bei gestopptem Testserver zuerst ein Backup erstellen und ausschließlich den State-Abschnitt dieser Testwelt kontrolliert neu initialisieren; States anderer Welten bleiben unangetastet.

Nach dem Start prüfen:

```text
fehlender Testwelt-State
-> nextReset = Initialisierungszeitpunkt + 1 Minute
-> bis zur Fälligkeit kein Reset
-> bei Fälligkeit genau ein automatischer Reset ohne Dragon-Override
-> lastReset = tatsächlicher Abschlusszeitpunkt
-> nextReset = Abschlusszeitpunkt + 1 Minute
```

Ein weiterer Scheduler-Tick vor dem neuen Termin darf keinen zweiten Reset starten.

## Test C – Restart-Catch-up

1. Den `next-reset` der Testwelt bei gestopptem Server gezielt in die Vergangenheit setzen.
2. Server starten und die 60 Sekunden Startup-Sicherheitsverzögerung vollständig abwarten.
3. Prüfen, dass genau ein Catch-up-Reset startet, der State erst bei Erfolg verschoben wird und anschließend der reguläre Scheduler übernimmt.
4. Mit einem zukünftigen `next-reset` wiederholen und prüfen, dass der exakte Termin den Neustart übersteht und kein Catch-up startet.

Ein sehr alter Termin löst nur einen Catch-up-Versuch aus, nicht einen Reset pro verpasstem Intervall.

FW-007 prüft verlorene Callbacks, Retirement, Rejections, offene externe Futures und Shutdown an Journalgrenzen mit steuerbaren JUnit-Doubles. Der Workflow-Test verwendet echte Journal- und Zeitplandateien. Im isolierten Smoke-Test bleiben genau ein erfolgreicher Worlds-Handoff, Instanz-, Seed-, Marker-, Post-Reset- und Belegprüfung je Lauf verbindlich. Ein absichtlich unterbrochener Worlds-Schreibvorgang liefert keinen zuverlässigen Nachweis für die Callback-Rennen. Spieler-Disconnects im Mehrspielerbetrieb und die Endfarm bleiben Gegenstand der manuellen Abnahme.

Die FW-007-Regressionen in `ResetWorkflowIntegrationTest` zählen direkt am `WorldsAccess.regenerate`-Einstieg des Test-Doubles hinter dem produktiven Adapter. Die beiden realen Black-Box-Jobs prüfen die erfolgreiche Regeneration anhand von Start- und Rückkehrmeldung, Weltmarker und persistentem Beleg.

| Fehlerpfad im Workflow-Test | Gezählte API-Aufrufe | Nachweis |
| --- | ---: | --- |
| Zweiter Thread stoppt die Engine vor der Übergabe | 0 | Journal bleibt beim Stop auf `WORLDS_PENDING`, Zeitplan unverändert. |
| Zweiter Thread stoppt die Engine nach der verbindlichen Übernahme, noch vor dem Worlds-Aufruf im Adapter | 1 | Stop kehrt bei angehaltener Übergabe zurück. Das Original-Future bleibt offen. Späterer Erfolg löst keinen Post-Reset aus. |
| Worlds meldet das Veto des Regenerate-Events im Original-Future | 2 insgesamt | Ein Aufruf für die gesperrte Overworld, ein erfolgreicher für den Nether. |
| Original-Future gecancelt, eingepackte Cancellation, synchroner Wurf, fehlendes Future, abgelehnter Beobachteranschluss oder gemeldeter Timeout | 1 je Fall | Die vorbereitete Nether-Pipeline nimmt ihr eigenes Journal zurück. Force, Catch-up, Reload und periodischer Tick erzeugen keinen weiteren Aufruf. |

FW-009 prüft die DragonBattle-Dateien in isolierten JUnit-Verzeichnissen mit der intern freigegebenen Version 26.1.2. Die Workflow-Regression entfernt nach dem Staging die zum Journal gehörende Sicherung und meldet ein terminales Unload-Veto. Die vorbereitete Datei bleibt lesbar, `WORLDS_PENDING` sperrt auch nach Neustart und der Worlds-Aufrufzähler bleibt bei eins. Mit intaktem Dateipaar stellt derselbe bestätigte Veto-Pfad das Original wieder her. Allgemeine terminale Fehler, Cancellation, Timeout, synchrone Fehler, fehlendes Future, gescheiterter Beobachteranschluss und Instanzwechsel bewahren den Dateibestand. Weitere Fälle injizieren Schreib-, Flush-, Move-, Hash- und Cleanup-Fehler und simulieren Crash-Reste. Eine echte Stromunterbrechung wird dabei nicht simuliert.

Die realen CI-Jobs für Overworld und Nether auf Folia 26.3 Build 26 und Worlds 4.5.1 verwenden weiterhin das unveränderte Isolations- und Log-Gate. Die positive reale Endfarm-Abnahme bleibt Phase 2.4 vorbehalten. Sie benötigt eine eigene Versionsprüfung sowie Portal-, Respawn- und Mehrspielertests.

Auf der Basis `9fcfc41` schlugen vier neue Erwartungen fehl. Eine fehlende Sicherung kostete die aktive Datei, ein verändertes Backup wurde installiert, eine ältere Sicherung verhinderte kein weiteres Staging und ein generischer Worlds-Fehler löste einen Restore aus. Ein isolierter Aufruf des ursprünglichen Stage-Fehlerpfads mit inzwischen fremd belegtem Ziel ersetzte außerdem dessen Inhalt und verbrauchte Backup und Tempdatei. Der Fall `foreignTarget` prüft diese Situation heute über den normalen Stage-Einstieg.

## Test D – Mehrere fällige Welten

Overworld, Nether und End auf dem Testserver gleichzeitig auf `DUE` vorbereiten. Nach der Startup-Verzögerung muss die vollständige Pipeline in Konfigurationsreihenfolge laufen:

```text
overworld vollständig fertig
-> nether vollständig fertig
-> end vollständig fertig
-> regulärer Scheduler startet
```

Keine zwei Startup-Resets dürfen parallel laufen. Optional einen kontrollierten Worlds-Fehler für die erste Welt erzeugen: Die folgenden Welten müssen weiter verarbeitet werden, der alte Termin bleibt fällig. Die betroffene Welt zeigt eine Recovery-Sperre und wird beim nächsten regulären Tick übersprungen.

Die positiven Kriterien der Tests E und F setzen die separate technische Freigabe voraus. In Phase 2.2 auf 26.3 für `dragon: false`, `dragon: true` sowie `--dragon` mit und ohne End-Abschnitt die frühe Versionsablehnung und unveränderte Journale prüfen. Die Versionssperre bleibt aktiv.

## Test E – End ohne Dragon

Mit `post-reset.end.dragon: false` einen normalen End-Reset ohne `--dragon` ausführen und zusätzlich einen automatischen End-Reset beobachten. In beiden Fällen prüfen:

- kein Enderdrache nach dem Reset,
- keine aktive Bossbar,
- aktives und benutzbares Ausgangsportal, dessen Brunnenbasis bei `x/z = 0` auf der
  tatsächlichen Oberfläche der zentralen Endinsel liegt und weder schwebt noch darunter liegt,
- keine verzögerte Erstspawn-Auslösung beim späteren Spielerbeitritt,
- ein echter Vanilla-Respawn mit vier Endkristallen bleibt möglich,
- nach dem Tod dieses Respawn-Drachens ist das Ausgangsportal wieder aktiv.

## Test F – End mit `--dragon`

`/farmwelt reset force end --dragon` ausführen und prüfen:

- frischer Vanilla-Erstkampf mit aktiver Bossbar,
- Drache darf erscheinen,
- Portal ist vor dem Kampf inaktiv und danach aktiv,
- Erstkampfverhalten einschließlich Drachenei bleibt erhalten,
- die einmalige Freigabe verändert `dragon: false` in der Config nicht,
- ein späterer normaler oder automatischer Reset verwendet wieder die konfigurierte dragonlose Policy.

Für die Abnahme von FW-001 auf 26.1.2 Test F auch ohne `post-reset.end` ausführen und den verzögerten Erstspawn nach Betreten der Endfarm prüfen. In einer isolierten Testwelt zusätzlich bei aktivierter End-Policy den Vanilla-End als Ziel eintragen und ohne Farmwelt-Reset einen Vanilla-Kampf beobachten. Spawn, Ei und Portal müssen Vanilla folgen. Serverlog und Weltzustand vor und nach dem Kampf erfassen.

Folia 26.3 benötigt vor einer späteren Freigabe eine Prüfung des internen Datenlayouts und die reale Wiederholung von Test E und F einschließlich Bossbar, Portal, Ei und Vier-Kristall-Respawn.

## Test G – Reload-Intervall

1. Mit `interval: "30d"` einen festen zukünftigen `next-reset` notieren.
2. Auf `interval: "60d"` ändern und `/farmwelt reload` ausführen.
3. Prüfen, dass der vorhandene Termin unverändert bleibt.
4. Den Termin auf dem Testserver kontrolliert fällig machen und den automatischen Reset abschließen lassen.
5. Prüfen, dass erst dessen neuer `next-reset` auf dem tatsächlichen Abschluss plus 60 Tage basiert.

Der verpflichtende Reload-Smoke-Test während einer offenen Reset-Pipeline ist zusätzlich unter Test I beschrieben. Der laufende Reset muss seinen ursprünglichen 30-Tage-Snapshot verwenden; erst die nächste Pipeline darf 60 Tage verwenden.

## Zusätzliche Sicherheitsprüfungen

- Eine als Hauptwelt erkannte oder falsch dimensionierte Welt endet mit dem vorgesehenen Sicherheitsstatus und wird nicht regeneriert.
- Eine nicht geladene Farmwelt erzeugt keinen falschen Erfolg und verändert ihren State nicht.
- Ein offener Reset blockiert Teleports in diese Farmwelt; ein zweiter Reset derselben logischen ID führt zu keiner zweiten Worlds-Regeneration.
- Ein Persistenzfehler nach Regeneration erzeugt `STATE_SAVE_FAILED`; der vorherige veröffentlichte und persistierte State bleibt bestehen.
- Ein Fehler einer Welt beendet weder den regulären Scheduler noch die Verarbeitung anderer fälliger Welten.

## Test H – Reset-Notifications

Für die Nachrichtentests eindeutige Texte konfigurieren, die Szenario, `{world}`, `{time}` und `{next-reset}` sichtbar voneinander unterscheiden. `{time}` ist nur für `warning-message` vorgesehen; Lifecycle- und Evakuierungstexte verwenden die bereits unterstützten Platzhalter `{world}` und `{next-reset}`. Keine zusätzlichen Placeholder voraussetzen.

### Countdown-Warnungen

1. Auf dem isolierten Testserver für eine einzige Farmwelt gut beobachtbare Warnschwellen konfigurieren und deren `next-reset` kontrolliert initialisieren. Wegen des 60-sekündlichen Scheduler-Takts genügend Abstand zwischen den Schwellen lassen.
2. Prüfen, dass die Schwellen in absteigender zeitlicher Reihenfolge erscheinen und jede Schwelle für denselben `next-reset`-Termin höchstens einmal ausgespielt wird. Zusätzliche Scheduler-Ticks innerhalb derselben Schwelle dürfen keine Wiederholung erzeugen.
3. Den Server vor Erreichen einer weiteren Schwelle sauber neu starten. Nach der Startup-Verzögerung darf höchstens die aktuell relevante, zeitlich nächste Warnung nachgeholt werden; ältere verpasste Warnungen dürfen nicht als Serie erscheinen.
4. Bis zum Reset weiterbeobachten und anhand von Chat und Log ausschließen, dass eine für diesen `next-reset`-Termin bereits protokollierte Schwelle mehrfach ausgespielt wird.
5. Mit `notifications.enabled: false` wiederholen. Es darf keine Warning erscheinen und der Reset-Zeitplan muss unverändert weiterlaufen.

Countdowns gelten nur für geplante automatische Resets, nicht für `/farmwelt reset force`. Der Test dokumentiert pro sichtbarer Warnung Schwelle, Empfangszeit, gerenderten Text und persistenten `next-reset`.

### Reset-Start, Erfolg und Fehler

- **Start:** Einen akzeptierten manuellen und einen geplanten Reset auslösen. Die konfigurierte Startmeldung muss genau beim tatsächlichen Beginn der Pipeline erscheinen. Je einen Aufruf mit `NOT_CONFIGURED`, `DISABLED` und während eines offenen Locks mit `ALREADY_RUNNING` auslösen; keiner dieser fachlich abgewiesenen Aufrufe darf eine globale Startmeldung erzeugen.
- **Erfolg:** Bei einem vollständigen `SUCCESS` prüfen, dass die Erfolgsmeldung erst nach Regeneration, Validierung, Post-Reset-Initialisierung und erfolgreicher State-Persistenz erscheint. Der darin gerenderte `{next-reset}` muss dem anschließend gelesenen State entsprechen.
- **Fehler:** `reset-failure.enabled: true` setzen und auf dem isolierten Server einen kontrollierten echten Pipeline-Fehler erzeugen, beispielsweise einen vorbereiteten Worlds-Fehler oder den Persistenzfehler aus BB-24. Es muss eine Fehlermeldung, aber keine Erfolgsmeldung erscheinen. `ALREADY_RUNNING` und andere Abweisungen vor Pipeline-Start dürfen keine globale Reset-Fehlermeldung erzeugen.
- **Einzelschalter:** `reset-start.enabled`, `reset-success.enabled`, `reset-failure.enabled` und `evacuation.enabled` getrennt prüfen. Der jeweils deaktivierte Nachrichtentyp bleibt aus, ohne den Resetablauf zu ändern. Mit dem Hauptschalter `notifications.enabled: false` müssen alle Reset-Nachrichten dieser Farmwelt ausbleiben.

Ein Fehler wird nur mit einer kontrollierten Testserver-Vorrichtung erzwungen. Fehlt eine reproduzierbare und sichere Fehler-Injektion, wird das Szenario als manueller Test `BLOCKED` dokumentiert; dafür wird kein Test-Command oder anderer Produktionscode ergänzt.

### Persönliche Evakuierungsmeldung

1. Spieler A in der betroffenen Farmwelt, Spieler B in einer anderen Welt und nach Möglichkeit Spieler C zunächst in der Farmwelt positionieren.
2. Den Reset starten und Spieler C vor seiner konkreten Evakuierungsoperation eigenständig aus der Welt wechseln lassen.
3. Prüfen, dass nur tatsächlich erfolgreich evakuierte Spieler ihre persönliche Nachricht genau einmal erhalten. Spieler B und der rechtzeitig gewechselte Spieler C dürfen sie nicht erhalten.
4. Sichtbar prüfen, dass die Nachricht kein Broadcast ist und `{world}` den GUI-Anzeigenamen statt logischer ID oder Bukkit-Weltname enthält.
5. Einen Versandfehler kontrolliert über Disconnect beziehungsweise nicht mehr verfügbare Entity-Scheduler-Zustellung provozieren. Der Reset muss unabhängig davon weiterlaufen; ein erwarteter Best-Effort-Hinweis im Log darf keine ungefangene Exception enthalten.

### Placeholder

Für die Meldungstexte Marker wie `W={world}; T={time}; N={next-reset}` verwenden und die Ausgabe gegen Config und State vergleichen:

- `{world}` entspricht dem konfigurierten `display-name`, nicht logischer ID oder Bukkit-Weltname.
- `{time}` entspricht in der Countdown-Warnung der konfigurierten Schwelle, nicht einer zufällig später gemessenen Restzeit.
- `{next-reset}` entspricht dem konkreten, lokal formatierten Termin des maßgeblichen veröffentlichten States. Bei der Erfolgsmeldung ist dies der neu persistierte Folgetermin.

## Test I – Lifecycle-Hardening

Diese Tests sind kritische Integration-Smoke-Tests; die detaillierten Unit-Tests werden nicht manuell nachgestellt.

### Doppelreset derselben Farmwelt

Während `/farmwelt reset force overworld` sichtbar läuft, den Reset mit `/farmwelt reset force OVERWORLD` erneut anfordern. Erwartet werden ein Worlds-Aufruf und eine Evakuierung. Der zweite Aufruf meldet `ALREADY_RUNNING`. Bei einem Reload während der offenen Pipeline zusätzlich die Config-ID in `Overworld` ändern und den physischen Namen beibehalten. Status und GUI müssen dieselbe Sperre zeigen. Nach Abschluss müssen Journal und Zeitplan denselben Versuch bestätigen und die Sperre muss aufgehoben sein.

### Teleport während eines Reset-Locks

Während derselben offenen Pipeline über das bestehende 45-Slot-Farmwelt-Menü in die betroffene logische Farmwelt teleportieren. Der Klick beziehungsweise der vor der Befehlsausführung wiederholte Lock-Check muss den Teleport blockieren; kein Spieler darf während der kritischen Phase zurückkehren. Nach Freigabe des Locks muss derselbe konfigurierte Teleport wieder verfügbar sein. Dabei GUI-Anzeigename, logische ID und tatsächlichen Bukkit-Weltnamen in der Evidenz getrennt notieren.

### Reset-Anzeige im Farmwelt-Menü

Auf dem isolierten Testserver zusätzlich mit einem Spieler prüfen, der nur `farmwelt.use` und keine Admin-Permissions hat:

1. `/farmwelt` öffnen. Bei jeder Welt bleiben die konfigurierten Lore-Zeilen erhalten; darunter stehen der gespeicherte nächste Reset in der Server-Zeitzone, die ungefähre Restzeit und der Klickhinweis. Den Termin als Admin mit `/farmwelt status <welt>` vergleichen. Der Info-Kompass erklärt den Verlust platzierter Blöcke und gelagerter Items und verweist auf die Welt-Items. Darstellung, Lesbarkeit und unveränderte Slots visuell prüfen.
2. Gezielte Termine auf dem gestoppten Testserver vorbereiten: mehr als eine Stunde, höchstens eine Stunde und höchstens fünf Minuten Restzeit. Nach dem Start und jeweils erneutem Öffnen graue, gelbe beziehungsweise rote Reset-Zeilen prüfen. Unter einer Minute muss `unter 1 Minute` erscheinen. Ein bereits geöffnetes Menü bleibt bewusst eine Momentaufnahme.
3. Einen fälligen Termin vor Beginn der Pipeline beobachten: `Reset fällig` statt negativer Restzeit. Während eines offenen Resets das Menü erneut öffnen: `Reset läuft` und `Teleport vorübergehend gesperrt` statt Datum und Klickhinweis. Nach erfolgreichem Reset muss erneutes Öffnen den neu persistierten Folgetermin zeigen.
4. Reset-Konfiguration deaktivieren und reloaden: `Automatischer Reset deaktiviert` auch bei vorhandenem historischem State. Bei einem eigenen GUI-Eintrag ohne Reset-Konfiguration steht `Termin nicht verfügbar`; der konfigurierte Teleport bleibt nutzbar. Nach Wiederaktivierung muss der bestehende Termin erneut erscheinen.
5. Nur das Reset-Intervall ändern und reloaden: Der angezeigte bestehende Termin bleibt gleich. `notifications.enabled: false` unterdrückt die Menüanzeige nicht. Klick-/Drag-Schutz, Schließen-Item und die zweifache Teleport-Sperre weiterhin prüfen.

### Reload während laufendem Reset

1. Den Lauf mit einem bekannten Intervall und einer eindeutig erkennbaren Notification-/Post-Reset-Konfiguration beginnen.
2. Während der offenen Pipeline die Config für zukünftige Läufe ändern und `/farmwelt reload` ausführen.
3. Prüfen, dass der laufende Reset nicht abgebrochen wird, sein Lock bestehen bleibt und sein ursprünglicher immutable Config-Snapshot einschließlich Intervall verwendet wird.
4. Prüfen, dass die neue Config erst zukünftige Entscheidungen beeinflusst, genau ein periodischer Scheduler bestehen bleibt und kein doppelter Reset ausgelöst wird.
5. Serverlog und resultierenden State auf Concurrency-, Folia- und Lifecycle-Fehler prüfen.

### Shutdown während Startup-Delay

Den Server starten und vor Ablauf der 60-sekündigen Startup-Verzögerung sauber stoppen. Anschließend erneut starten und die Verzögerung vollständig abwarten. Es darf kein alter Startup-Task nachträglich Arbeit ausführen und weder doppelte Startup-Nachholung noch doppelter Scheduler entstehen. Ein vorbereiteter fälliger State muss beim zweiten vollständigen Start genau einmal normal nachgeholt werden.

## Test J – Schutz-, Post-Reset- und Fehlerfälle

- **Gamerules:** Für jede Dimension mindestens eine typisierte Gamerule mit vom Weltdefault unterscheidbarem Wert konfigurieren und nach vollständigem Reset über die Serveroberfläche auslesen. Der konfigurierte Wert muss erst nach erfolgreichem Post-Reset gelten.
- **WorldBorder:** Eine eindeutig erkennbare Größe konfigurieren und nach dem Reset erneut auslesen. Die konfigurierte Größe muss nach erfolgreichem Post-Reset angewendet sein.
- **Hauptwelt-Schutz:** Die geschützte Hauptwelt niemals tatsächlich als Testziel opfern. Auf dem isolierten Server eine Konstellation herstellen, in der die konfigurierte Farmwelt auf die als Hauptwelt erkannte Instanz zeigt. Der Reset muss vor Worlds-Regeneration mit dem vorgesehenen Sicherheitsstatus enden.
- **Falsche Dimension:** Einer logischen ID eine geladene Testwelt der falschen Dimension zuordnen. Keine Regeneration und keine State-Änderung dürfen erfolgen.
- **Nicht geladene Farmwelt:** Eine korrekt benannte Testwelt gezielt nicht laden und den Reset anfordern. Es darf keinen Erfolg, keine Regeneration und keine State-Änderung geben.
- **Persistenzfehler:** Nach Backup auf dem isolierten Server die Schreibbarkeit des Plugin-State-Verzeichnisses kontrolliert so einschränken, dass erst das atomare Speichern nach einer ansonsten erfolgreichen Regeneration fehlschlägt. Erwartet werden `STATE_SAVE_FAILED`, eine aktivierte Fehlermeldung statt Erfolg und ein unveränderter veröffentlichter sowie persistierter State. Für eine gezielte Injektion ausschließlich `reset-state.yml.tmp` blockieren, etwa durch ein nicht leeres gleichnamiges Testverzeichnis. Die Journaldateien müssen schreibbar bleiben. Die Schreibbarkeit danach wiederherstellen. Zwei reguläre Ticks, einen Force-Aufruf und einen Neustart mit Catch-up beobachten. Im Log muss die Zahl der Worlds-Aufrufe für diesen Versuch bei eins bleiben, der Status muss `POST_RESET_DONE` nennen. Eine zweite gesunde, fällige Farmwelt muss weiter zurückgesetzt werden. Falls das sicher nicht reproduzierbar ist, BB-24 als `BLOCKED` markieren.

## Abnahmeprotokoll und Fehlerdiagnose

Für jeden Lauf festhalten:

- Datum, Uhrzeit und eindeutige Run-ID,
- Git-Commit, Plugin-Version, JAR-Dateiname und vorzugsweise JAR-Prüfsumme,
- Java-, Minecraft-/Folia- und Worlds-Version,
- relevante Ausschnitte aus `config.yml`,
- relevante Ausschnitte aus `reset-state.yml` sowie Versionsmarker und weltbezogenes Journal vor und nach dem Test,
- ausgeführter Command beziehungsweise automatischer Trigger,
- tatsächlicher Start- und Abschlusszeitpunkt der Reset-Pipeline,
- `ResetResult` beziehungsweise administrativ sichtbarer Ergebnisstatus,
- alter und neuer Seed, sofern relevant,
- Gamerules und WorldBorder vor und nach dem Reset,
- End-, Dragon-, Bossbar- und Portal-Zustand, sofern relevant,
- relevante, zeitlich zuordenbare Serverlog-Ausschnitte einschließlich Log-Gate-Prüfung,
- Status `PASS`, `FAIL` oder `BLOCKED` und eine kurze Notiz bei Abweichungen.

Bei Fehlern Server- und Pluginlogs sowie die verwendeten Config- und State-Dateien sichern. Die [`V2-Abnahmevorlage`](v2-acceptance-template.md) ist pro Testlauf zu kopieren und auszufüllen.

## Automatisierter Smoke-Test

Das branch-unabhängige Harness unter [`testing/blackbox/`](../../testing/blackbox/README.md) ergänzt die manuelle Abnahme um einen kleinen echten End-to-End-Smoke-Test. Der separate Workflow `.github/workflows/blackbox.yml` verwendet dieselbe lokale Einstiegsschnittstelle `bash ./testing/blackbox/run-blackbox.sh` und läuft bei Pull Requests sowie manuell. Wegen der Kosten eines realen Minecraft-Serverstarts und des bereits auf jedem Push laufenden normalen Builds wird nicht zusätzlich jeder Push automatisch getestet.

### Automatisiert

- isolierter Folia-Serverstart mit gepinnten und hashgeprüften Binaries sowie der Farmwelt-JAR des aktuellen Commits,
- Startup und Aktivierung von Worlds sowie Farmwelt ohne relevante Exceptions,
- automatisches Erzeugen und Laden je einer separaten Overworld- und Nether-Testfarmwelt auf eigenständigen Runnern durch Worlds,
- ein vollständiger Reset über `farmwelt reset force <welt>` je Dimension und die reale Worlds-Integration,
- `SUCCESS`, genau ein Farmwelt-Regenerationsaufruf und genau ein Worlds-Erfolg,
- Nachweis der echten Neuerzeugung durch das Verschwinden eines Markers im alten `region`-Bereich,
- Vorhandensein und Vergleich von altem und neuem Seed; der Abnahmelauf verlangt einen beobachteten Seedwechsel,
- Fortschreiben und Konsistenz von `reset-state.yml` einschließlich UUID- und Zeitabgleich mit dem Recovery-Journal,
- vollständiges Folia-/Thread-/Scheduler-/Worlds-Lifecycle-Log-Gate einschließlich der eng begrenzten bekannten CI-Watchdog-Ausnahme,
- sauberer Konsolen-Shutdown mit Timeout,
- Upload von Harness-Ausgabe, Serverlogs, Findings, bekannten Watchdog-Blöcken, Config, State und Worlds-Laufzeitdaten als Testartefakte.

### Weiterhin manuell

- Spieler-Evakuierung und persönliche Meldungen,
- GUI, Teleports, BetterRTP und GriefPrevention,
- DragonBattle, `--dragon`, Kristall-Respawn und visuelle Portalprüfung,
- Restart-Catch-up, mehrere fällige oder parallele Farmwelten,
- Notification-Schwellen und persistente Langzeit-Scheduler-Tests,
- die vollständige Abnahme aller Szenarien BB-01 bis BB-26.

Der Smoke-Test ist damit eine zusätzliche mittlere Ebene zwischen Unit-/Integrationstests und manueller V2-Abnahme. Ein erfolgreicher Smoke-Lauf erteilt keine vollständige V2-Freigabe.

## CI- und Build-Strategie

Der allgemeine Build-Workflow `.github/workflows/build.yml` führt `./gradlew build` mit Java 25 aus; der Gradle-Lifecycle führt dabei die Tests aus. Der Release-Workflow baut ebenfalls mit `clean build`. Vor der V2-Abnahme werden lokal zusätzlich die explizit geforderten Befehle ausgeführt:

```bash
./gradlew clean test
./gradlew build
```

Der separate Black-Box-Workflow führt zuerst `./gradlew clean test` und `./gradlew build` aus. Nur danach installiert er genau die dabei erzeugte Farmwelt-JAR. Das normale Build-Gate bleibt unverändert; die manuelle Black-Box-Abnahme bleibt ein zusätzliches Release-Gate und ersetzt weder Unit-Tests noch automatisierten Smoke-Test.
