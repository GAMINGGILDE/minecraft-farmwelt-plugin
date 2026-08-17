# Farmwelt Admin Guide

Diese Anleitung richtet sich an Serveradministratoren, die Farmwelt installieren, konfigurieren, testen und im Livebetrieb einsetzen.

## Empfohlene Einrichtung

1. Plugin-JAR in den `plugins`-Ordner legen.
2. Server starten, damit die Standardconfig erzeugt wird.
3. `plugins/Farmwelt/config.yml` öffnen.
4. Unter `farmworlds` die sichtbaren GUI-Einträge prüfen.
5. BetterRTP-Befehle prüfen, zum Beispiel `betterrtp:rtp world Farmwelt`.
6. BetterRTP-Weltnamen, Permissions und Cooldowns prüfen.
7. Falls Claims ausgenommen werden sollen: GriefPrevention installieren und aktivieren.
8. `resource-monitor.claim-protection` prüfen.
9. Server neu starten oder `/farmwelt reload` ausführen.
10. `/farmwelt info` ausführen und Hook-/Modus-Status prüfen.
11. `/farmwelt debug claim` in und außerhalb eines Claims testen.
12. `/farmwelt debug monitor` aktivieren und relevante Blöcke per Rechtsklick prüfen.
13. Zuerst mit `mode: audit` starten.
14. Danach bei passenden Regeln auf `mode: warn` wechseln.
15. Erst nach erfolgreichen Tests `mode: enforce` aktivieren.

## Administrative Reset-Befehle

| Befehl | Beschreibung | Permission |
| --- | --- | --- |
| `/farmwelt status` | Kompakter Status aller konfigurierten Reset-Farmwelten. | `farmwelt.admin.status` |
| `/farmwelt status <welt>` | Details inklusive Weltname, Typ, Intervall, letztem/nächstem Reset und Restzeit. | `farmwelt.admin.status` |
| `/farmwelt reload` | Lädt GUI-, Reset- und Ressourcenmonitor-Konfiguration neu. | `farmwelt.admin.reload` |
| `/farmwelt reset force <welt>` | Startet sofort die vollständige Reset-Pipeline. | `farmwelt.admin.reset` |

Als `<welt>` wird ausschließlich die logische ID `overworld`, `nether` oder `end` verwendet. Der tatsächliche Bukkit-Weltname wird nur aus `farmworlds.<id>.reset.world` gelesen. Alle administrativen Permissions sind standardmäßig nur für Operatoren aktiv.

Der Force-Command führt sofort einen vollständigen Reset aus und sollte nur von Administratoren verwendet werden. `force` ignoriert nur den gespeicherten zukünftigen `nextReset`-Termin. Der Command umgeht weder eine deaktivierte Konfiguration noch Reset-Lock, Spielerevakuierung, Pfadschutz, Unload oder andere Sicherheitsprüfungen.

Ein Reload verwendet denselben `FarmworldResetService` und dieselbe `FarmworldResetEngine` weiter. Dadurch bleiben laufende Locks und deren immutable Config-Snapshots erhalten; gespeicherte `nextReset`-Werte werden nicht neu berechnet.

## Manueller End-to-End-Reset auf Folia

Den ersten echten Test ausdrücklich mit einer wegwerfbaren Testwelt durchführen, niemals direkt mit einer produktiven Farmwelt. Beispiel:

```yaml
farmworlds:
  overworld:
    enabled: true
    display-name: "Test-Farmwelt"
    icon: GRASS_BLOCK
    slot: 11

    reset:
      enabled: true
      world: "test_farmwelt"
      interval: "30d"

    teleport:
      type: command
      sender: player
      command: "betterrtp:rtp world test_farmwelt"
```

Nach dem Reload zuerst ausführen:

```text
/farmwelt status overworld
/farmwelt reset force overworld
```

Danach kontrollieren:

1. Der Command meldet den Start sofort.
2. Spieler werden aus `test_farmwelt` evakuiert.
3. Teleports zurück in die Welt werden während des Resets blockiert.
4. Die Welt wird entladen.
5. Der Weltordner wird gelöscht.
6. Die Welt wird neu erstellt.
7. Die neue Welt besitzt wieder den Typ `NORMAL`.
8. Eine vorher platzierte Teststruktur ist verschwunden.
9. BetterRTP kann wieder nach `test_farmwelt` teleportieren.
10. `reset-state.yml` enthält den neuen UTC-Zeitpunkt.
11. `/farmwelt status overworld` zeigt einen neuen `lastReset`.
12. Der Status zeigt den daraus berechneten neuen `nextReset`.
13. Ein weiterer Force-Reset ist möglich; der vorherige Lock ist also freigegeben.

Auf Folia außerdem das Serverlog gezielt auf Thread-/Region-Fehler prüfen. Phase 3 startet weiterhin keinen automatischen Reset-Scheduler und enthält weder Countdown/Broadcasts noch Overdue-Aktion oder Startup-Reset.

## Empfohlene Startwerte

Konservative Startwerte:

```yaml
resource-monitor:
  enabled: true
  mode: audit
  violation-window-seconds: 600

  audit:
    log-cooldown-seconds: 120

  actions:
    warning:
      enabled: true
      after-blocks: 5
      cooldown-seconds: 60

    notify-staff:
      enabled: true
      after-blocks: 10
      cooldown-seconds: 60

    cancel-break:
      enabled: true
      after-blocks: 15
      cooldown-seconds: 10

    jail:
      enabled: false
      mode: notify-only
```

Diese Werte sind bewusst vorsichtig. Auf Servern mit viel legitimer Bautätigkeit in Hauptwelten können höhere Schwellen sinnvoll sein. Auf Servern mit klar getrennten Bau- und Farmwelten können niedrigere Schwellen reichen, sollten aber erst nach Audit-Logs gesetzt werden.

## Testplan

### Test A: GUI

1. Als Spieler mit `farmwelt.use` einloggen.
2. `/farmwelt` ausführen.
3. Prüfen, ob die GUI geöffnet wird.
4. Farmwelt anklicken.
5. Prüfen, ob der konfigurierte BetterRTP-Befehl ausgeführt wird.

Erwartung: Die GUI öffnet sich, der Spieler erhält die Teleportmeldung und BetterRTP übernimmt den Teleport.

### Test B: Claim-Hook

1. In einen GriefPrevention-Claim stellen.
2. `/farmwelt debug claim` ausführen.
3. Danach außerhalb eines Claims erneut ausführen.

Erwartung: Im Claim meldet der Befehl `Position liegt in Claim: ja`, außerhalb `nein`. Außerdem sollte `/farmwelt info` `Claim-Hook aktiv: ja` melden.

### Test C: Overworld-Ressourcen

1. In einer überwachten Overworld stehen, zum Beispiel `world`.
2. Einen Block aus `world-rules.<welt>.resources` prüfen.
3. `/farmwelt debug monitor` aktivieren.
4. Block rechtsklicken.
5. Audit, Warn oder Enforce je nach Modus prüfen.

Erwartung: Kategorie ist `overworld`; der Block würde vom Monitor geprüft, sofern kein Claim oder Bypass greift.

### Test D: Overworld-Höhencheck

1. Einen Ressourcenblock auf unterschiedlichen Y-Höhen testen.
2. Zum Beispiel ein Erz in einem Berg oder einen Oberflächenblock unterhalb von Y 63 prüfen.
3. `/farmwelt debug monitor` verwenden.
4. Danach im passenden Modus abbauen.

Erwartung: Die Höhe entscheidet nicht über die Erkennung. Nur die `resources`-Liste, Welt, Claim und Bypass sind relevant.

### Test E: Nether

1. In einer überwachten Nether-Welt testen, zum Beispiel `world_nether`.
2. Einen Block aus `world-rules.<welt>.resources` prüfen.
3. Keine Seehöhe erwarten.

Erwartung: Kategorie ist `nether`; nur die `resources`-Liste entscheidet.

### Test F: End

1. In einer überwachten End-Welt testen, zum Beispiel `world_the_end`.
2. Einen Block aus der End-`resources`-Liste prüfen.
3. Keine Seehöhe erwarten.

Erwartung: Kategorie ist `end`; nur die `resources`-Liste entscheidet.

### Test F2: End-Loot / Elytra

1. In einer überwachten End-Welt testen, zum Beispiel `world_the_end`.
2. Sicherstellen, dass `world-rules.world_the_end.protected-items` `ELYTRA` enthält.
3. Im `audit`- oder `warn`-Modus ein End-City-Item-Frame mit Elytra anschlagen.
4. Im `enforce`-Modus denselben Versuch wiederholen.

Erwartung: In `audit`/`warn` wird der Versuch als Kategorie `end-loot` erkannt. In `enforce` wird die Elytra-Entnahme sofort blockiert, sofern `actions.cancel-break.enabled` aktiv ist.

### Test G: Claim-Ausnahme

1. Einen Claim in einer überwachten Welt nutzen.
2. Einen grundsätzlich relevanten Ressourcenblock im Claim platzieren oder vorhandenen Block prüfen.
3. Block abbauen.

Erwartung: Keine Warnung, keine Staff-Violation und kein Blockieren, wenn `skip-inside-claims: true` aktiv ist.

### Test H: Bypass

1. Einem Testspieler `farmwelt.bypass` geben.
2. Relevante Ressourcen in überwachten Welten abbauen.
3. `/farmwelt debug monitor` kann zusätzlich zeigen, dass der Spieler Bypass hat.

Erwartung: Keine Warnung, keine Staff-Violation und kein Blockieren für diesen Spieler.

### Test I: Explosionsschutz

1. In einer überwachten Welt einen Ressourcenblock und einen nicht relevanten Block platzieren.
2. `mode: enforce` und `actions.cancel-break.enabled: true` setzen.
3. Eine Explosion auslösen, zum Beispiel TNT in sicherer Testumgebung.
4. Prüfen, welche Blöcke zerstört wurden.

Erwartung: Der Ressourcenblock bleibt stehen, weil er aus der Explosionsliste entfernt wurde. Andere Blöcke können weiterhin durch die Explosion zerstört werden. In `audit` und `warn` verändert der Explosionsschutz keine Blöcke.

## Rollout-Strategie für den Live-Server

### Phase 1: Audit

`mode: audit` einige Tage laufen lassen. Console-Logs und Staff-Meldungen beobachten. In dieser Phase sollen False Positives gefunden werden: falsche Weltregeln, fehlende Claim-Erkennung oder zu breite Materiallisten.

Empfohlen:

- `audit.log-to-console: true`
- `audit.notify-staff: true`
- `audit.log-cooldown-seconds` nicht zu niedrig setzen; `120` Sekunden ist ein sinnvoller Startwert für Live-Audit
- `actions.jail.enabled: false`
- Die breiten 26.1.2-Standardlisten bewusst prüfen und Materialien entfernen, die in der Hauptwelt erlaubt bleiben sollen.

### Phase 2: Warn

Auf `mode: warn` wechseln, sobald die Erkennung plausibel ist. Spieler erhalten ab `actions.warning.after-blocks` Hinweise auf `/farmwelt`. Moderatoren beobachten Staff-Meldungen, ohne dass der Abbau blockiert wird.

Empfohlen:

- Warnschwelle nicht unter 5 setzen.
- Staff-Schwelle höher als Warnschwelle setzen.
- Cooldowns aktiv lassen.
- Spielerhinweise klar und kurz formulieren.

### Phase 3: Enforce

Erst nach Audit und Warn auf `mode: enforce` wechseln. `actions.cancel-break.after-blocks` sollte höher als Warn- und Staff-Schwelle liegen. Blockieren sollte zunächst mit Teammitgliedern und typischen Spielerfällen getestet werden.

Empfohlen:

- `cancel-break.enabled: true`
- `cancel-break.after-blocks` nicht zu niedrig setzen.
- Explosionsschutz mit TNT oder anderer kontrollierter Explosion testen.
- Staff-Meldungen weiter beobachten.
- Jail oder andere harte Sanktionen deaktiviert lassen, bis der Serverbetrieb stabil ist.

## Moderationshinweise

Staff-Meldungen sind Hinweise, keine automatische Schuldzuweisung. Ein Treffer bedeutet nur, dass ein Spieler einen konfigurierten Ressourcenblock in einer überwachten Welt abgebaut hat.

Claims sind bewusst ausgenommen. Wenn Spieler in Claims bauen oder abbauen, soll Farmwelt nicht eingreifen. Bei Meldungen außerhalb von Claims sollten Moderatoren zunächst auf `/farmwelt` und die Farmwelt-Regeln hinweisen.

Automatische harte Sanktionen sollten vorsichtig verwendet werden. Die vorhandene Jail-Stufe zählt nur blockierte Abbauversuche nach aktivem Enforce-Blockieren und ist standardmäßig deaktiviert. Für den normalen Betrieb reicht meist Warnen, Staff informieren und Blockieren.

## Wartung

- Config nach Änderungen mit `/farmwelt reload` neu laden.
- Danach `/farmwelt info` ausführen.
- Bei Config-Fehlern das Serverlog prüfen.
- Nach Minecraft-, Paper- oder Folia-Updates die GUI, den Ressourcenmonitor und Enforce erneut testen.
- Nach BetterRTP-Updates die Teleportbefehle testen.
- Nach GriefPrevention-Updates `/farmwelt debug claim` in und außerhalb eines Claims testen.
- Nach Änderungen an Welt-Namen sowohl `monitored-worlds`/`ignored-worlds` als auch `world-rules` prüfen.
- Nach Änderungen an Permission-Gruppen `farmwelt.use`, `farmwelt.admin`, `farmwelt.bypass` und `farmwelt.notify` prüfen.

## Schnellcheck nach Reload

1. `/farmwelt info`
2. `/farmwelt`
3. Farmwelt anklicken
4. `/farmwelt debug claim`
5. `/farmwelt debug monitor`
6. Relevanten Block rechtsklicken
7. Im passenden Modus echten Abbau testen

Wenn einer dieser Schritte unerwartet ausfällt, zuerst Serverlog, Weltname, Permission und Claim-Hook prüfen.
