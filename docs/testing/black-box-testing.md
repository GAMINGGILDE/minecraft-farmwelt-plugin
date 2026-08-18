# Geplante Black-Box-Tests

## Zweck

Spätere Black-Box-Tests sollen die reale Integration von Folia, Minecraft, Worlds, der gebauten Plugin-JAR und dem vollständigen Reset-Lifecycle prüfen. Sie ergänzen die schnellen Gradle-Tests, werden aber bewusst als separater Workflow mit isoliertem Testserver geplant.

Phase 3.6 richtet noch keinen Folia-Server, keine EULA-Automatisierung, keine Weltgenerierung, kein RCON-/Console-Harness und keine automatisierten Drachen- oder Neustart-Szenarien in CI ein.

## Erste Szenarien

### Server-Boot

- Folia 26.1.2 startet.
- Das Plugin und seine Worlds-Integration laden erfolgreich.
- Das Startup-Log enthält keine Exceptions.

### Force Reset

Der administrative Force-Reset wird jeweils für eine isolierte Overworld-, Nether- und End-Farmwelt ausgeführt. Die Tests prüfen den erfolgreichen Worlds-Lifecycle und die Verfügbarkeit der regenerierten Welt.

### Persistenz

Dieses Szenario ist besonders für Phase 4 vorgesehen:

```text
Server starten
→ reset-state.yml erzeugen/lesen
→ Server stoppen
→ Server neu starten
→ nextReset unverändert
```

### Fälliger automatischer Reset

Nach Einführung des automatischen Reset-Schedulers wird geprüft:

```text
nextReset liegt in der Vergangenheit
→ Serverstart
→ genau ein Reset
→ neuer nextReset persistiert
```

## Fehlerdiagnose

Bei fehlgeschlagenen Black-Box-Tests sollen Serverlogs, Pluginlogs sowie relevante Konfigurations- und State-Dateien als CI-Artefakte hochgeladen werden. Der spätere Workflow soll dafür einen eigenen, klar abgegrenzten Job und eindeutige Artefaktnamen erhalten.

## Manuelle Endfarm-Tests

Folgende Minecraft-Verhaltensweisen bleiben zunächst bewusste manuelle Tests:

- Enderdrachen-Respawn mit vier Endkristallen
- Bossbar-Verhalten
- Drachenei beim Erstkampf
- aktive Exit-Portal-Blöcke
- Vanilla-Respawn nach einem Reset mit `dragon: false`

Diese Szenarien wurden bereits manuell erfolgreich validiert. Ihre Automatisierung ist kein Bestandteil von Phase 3.6.
