# Black-Box-Abnahme

## Zweck und Testumgebung

Die Black-Box-Abnahme prüft die reale Integration von Folia, Minecraft, Worlds, der gebauten Plugin-JAR und dem vollständigen Reset-Lifecycle. Sie ergänzt die deterministischen Gradle-Tests, ersetzt sie aber nicht. Der reguläre Build-Workflow startet bewusst keinen Minecraft-Server, solange dafür kein stabiles Server-, EULA-, Console- und Welt-Harness vorhanden ist.

Alle Szenarien werden auf einem isolierten Testserver mit Backup und separaten Testwelten ausgeführt. Vor jedem Lauf sind Plugin-JAR, Folia-, Minecraft- und Worlds-Version sowie die Ausgangswerte aus `config.yml` und `reset-state.yml` zu protokollieren. `reset-state.yml` nur bei gestopptem Server und nur für den gezielt getesteten Weltabschnitt bearbeiten oder entfernen. Die komplette Datei niemals als Kurztest-Anweisung in einem laufenden oder produktiven Server löschen.

Vor den Reset-Szenarien muss Folia 26.1.2 mit Farmwelt und Worlds ohne Exceptions starten. Das Serverlog ist während aller Tests zusätzlich auf Folia-Thread-/Region-Fehler zu prüfen.

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

## Test D – Mehrere fällige Welten

Overworld, Nether und End auf dem Testserver gleichzeitig auf `DUE` vorbereiten. Nach der Startup-Verzögerung muss die vollständige Pipeline in Konfigurationsreihenfolge laufen:

```text
overworld vollständig fertig
-> nether vollständig fertig
-> end vollständig fertig
-> regulärer Scheduler startet
```

Keine zwei Startup-Resets dürfen parallel laufen. Optional einen kontrollierten Worlds-Fehler für die erste Welt erzeugen: Die folgenden Welten müssen weiter verarbeitet werden, der fehlgeschlagene State bleibt fällig und wird bei einem späteren regulären Tick erneut versucht.

## Test E – End ohne Dragon

Mit `post-reset.end.dragon: false` einen normalen End-Reset ohne `--dragon` ausführen und zusätzlich einen automatischen End-Reset beobachten. In beiden Fällen prüfen:

- kein Enderdrache nach dem Reset,
- keine aktive Bossbar,
- aktives und benutzbares Ausgangsportal,
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

## Test G – Reload-Intervall

1. Mit `interval: "30d"` einen festen zukünftigen `next-reset` notieren.
2. Auf `interval: "60d"` ändern und `/farmwelt reload` ausführen.
3. Prüfen, dass der vorhandene Termin unverändert bleibt.
4. Den Termin auf dem Testserver kontrolliert fällig machen und den automatischen Reset abschließen lassen.
5. Prüfen, dass erst dessen neuer `next-reset` auf dem tatsächlichen Abschluss plus 60 Tage basiert.

Optional den Reload während einer offenen Reset-Pipeline ausführen: Der laufende Reset muss seinen ursprünglichen 30-Tage-Snapshot verwenden; erst die nächste Pipeline darf 60 Tage verwenden.

## Zusätzliche Sicherheitsprüfungen

- Eine als Hauptwelt erkannte oder falsch dimensionierte Welt endet mit dem vorgesehenen Sicherheitsstatus und wird nicht regeneriert.
- Eine nicht geladene Farmwelt erzeugt keinen falschen Erfolg und verändert ihren State nicht.
- Ein offener Reset blockiert Teleports in diese Farmwelt; ein zweiter Reset derselben logischen ID führt zu keiner zweiten Worlds-Regeneration.
- Ein Persistenzfehler nach Regeneration erzeugt `STATE_SAVE_FAILED`; der vorherige veröffentlichte und persistierte State bleibt bestehen.
- Ein Fehler einer Welt beendet weder den regulären Scheduler noch die Verarbeitung anderer fälliger Welten.

## Abnahmeprotokoll und Fehlerdiagnose

Für jeden Lauf festhalten:

- verwendete Versionen und Commit,
- Start- und Abschlusszeitpunkte,
- relevante Statusausgaben vor, während und nach dem Reset,
- Weltname, Dimension, Seed-Wechsel, Gamerules und WorldBorder,
- gesicherte State-Datei vor und nach dem Szenario,
- Ergebnis der End-/Dragon-Prüfungen.

Bei Fehlern Server- und Pluginlogs sowie die verwendeten Config- und State-Dateien sichern. Ein später automatisierter Black-Box-Workflow soll diese Daten mit eindeutigem Szenario- und Run-Namen als Artefakte ablegen.
