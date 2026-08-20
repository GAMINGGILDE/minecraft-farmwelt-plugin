<p align="center">
  <img src="assets/logo.png" alt="Farmwelt" width="360">
</p>
<p align="center">
  <a href="https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/GAMINGGILDE/minecraft-farmwelt-plugin/build.yml?branch=main&amp;label=build&amp;style=flat-square&amp;logo=githubactions&amp;logoColor=white"></a>
  <a href="https://www.codefactor.io/repository/github/gaminggilde/minecraft-farmwelt-plugin"><img src="https://www.codefactor.io/repository/github/gaminggilde/minecraft-farmwelt-plugin/badge?style=flat-square" alt="CodeFactor"></a>
  <a href="https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/releases"><img alt="Release" src="https://img.shields.io/github/v/release/GAMINGGILDE/minecraft-farmwelt-plugin?label=release&amp;cacheSeconds=300&amp;style=flat-square&amp;logo=github&amp;logoColor=white"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/GAMINGGILDE/minecraft-farmwelt-plugin?style=flat-square&amp;logo=opensourceinitiative&amp;logoColor=white"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Paper 26.1.2" src="https://img.shields.io/badge/Paper-26.1.2-blue?style=flat-square">
  <img alt="Folia supported" src="https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square">
</p>

# Farmwelt

Farmwelt ist ein Paper/Folia-Plugin für Minecraft-Server. Es führt Spieler über eine konfigurierbare GUI in getrennte Farmwelten, überwacht Ressourcenabbau in normalen Welten und setzt Overworld-, Nether- und End-Farmwelten sicher über Worlds zurück.

Das Plugin ist kein Claim- oder allgemeines Anti-Grief-System. GriefPrevention kann optional angebunden werden, damit Claims vom Ressourcenmonitor ausgenommen bleiben.

## Features

- Konfigurierbares 45-Slot-Menü unter `/farmwelt` mit befehlsbasierten Teleports, standardmäßig über BetterRTP.
- Ressourcenmonitor mit den Modi `audit`, `warn` und `enforce`, Staff-Meldungen, Claim-Ausnahmen und optionaler Jail-Eskalation.
- Persistente, automatische Reset-Zeitpläne sowie sichere manuelle Force-Resets für Overworld, Nether und End.
- Countdown-, Start-, Erfolgs-, optionale Fehler- und persönliche Evakuierungsmeldungen.
- Post-Reset-Gamerules, WorldBorder und eine ausdrücklich konfigurierte Enderdragon-Policy.
- Folia-gerechte Ausführung, Diagnosebefehle und ein echter automatisierter Folia-/Worlds-Smoke-Test.

Die Endfarm unterstützt sowohl einen dragonlosen Zustand mit aktivem Ausgangsportal als auch einen einmaligen frischen Erstkampf über `--dragon`. Die Betriebsdetails stehen im [Admin Guide](docs/ADMIN_GUIDE.md).

## Voraussetzungen

| Komponente | Status | Aktueller Stand |
| --- | --- | --- |
| Java | erforderlich | 25 |
| Minecraft/Paper/Folia | erforderlich | 26.1.2; Build gegen `paper-api:26.1.2.build.74-stable` |
| Worlds | erforderlich | 4.4.0; übernimmt den dynamischen Welt-Lifecycle |
| BetterRTP | optional | Standard-Teleportbefehle verwenden BetterRTP |
| GriefPrevention | optional | Claim-Ausnahmen für den Ressourcenmonitor |

Ohne Worlds lädt Farmwelt nicht. Es gibt keinen Bukkit-Fallback für die Weltregeneration. Ohne BetterRTP muss für jeden GUI-Eintrag ein anderer unterstützter Teleportbefehl konfiguriert werden.

## Installation

1. Worlds 4.4.0 und dessen Servervoraussetzungen installieren.
2. Farmwelt-JAR in den Serverordner `plugins/` legen.
3. Server starten und `plugins/Farmwelt/config.yml` prüfen.
4. Bukkit-Weltnamen, Teleportbefehle und optionale GriefPrevention-Integration anpassen.
5. Mit `/farmwelt info`, `/farmwelt status` und als Spieler mit `/farmwelt` prüfen.

Vor dem ersten produktiven Reset die [Betriebs- und Testanleitung](docs/ADMIN_GUIDE.md) verwenden und ausschließlich mit gesicherten Testwelten beginnen.

## Commands

| Befehl | Zweck | Permission |
| --- | --- | --- |
| `/farmwelt` | Öffnet das Farmwelt-Menü. | `farmwelt.use` |
| `/farmwelt status [welt]` | Zeigt den Reset-Status aller oder einer logischen Farmwelt-ID. | `farmwelt.admin.status` |
| `/farmwelt info` | Zeigt den operativen Plugin- und Integrationsstatus. | `farmwelt.admin` |
| `/farmwelt reload` | Validiert und lädt die Konfiguration neu. | `farmwelt.admin.reload` |
| `/farmwelt reset force <welt>` | Startet die vollständige sichere Reset-Pipeline sofort. | `farmwelt.admin.reset` |
| `/farmwelt reset force end --dragon` | Startet für diesen End-Reset einen frischen Vanilla-Erstkampf. | `farmwelt.admin.reset` |
| `/farmwelt debug claim` | Prüft Claim-Hook und aktuelle Spielerposition. | `farmwelt.admin` |
| `/farmwelt debug monitor` | Prüft Ressourcenmonitor-Entscheidungen per Rechtsklick. | `farmwelt.admin` |
| `/farmwelt debug violations [spieler]` | Zeigt flüchtige Violation- und Blockierzähler. | `farmwelt.admin` |

`<welt>` ist bei Status und Reset die logische ID `overworld`, `nether` oder `end`, nicht der Bukkit-Weltname. Die vollständige Command-, Permission- und Betriebserklärung steht im [Admin Guide](docs/ADMIN_GUIDE.md).

## Konfiguration

Minimales Beispiel für einen GUI-Eintrag mit aktivem Reset-Plan:

```yaml
farmworlds:
  overworld:
    enabled: true
    display-name: "Farmwelt"
    icon: GRASS_BLOCK
    slot: 11
    reset:
      enabled: true
      world: "farmwelt"
      interval: "30d"
    teleport:
      type: command
      sender: player
      command: "betterrtp:rtp world farmwelt"
```

Die ausgelieferte [`config.yml`](src/main/resources/config.yml) enthält alle V2-Bereiche und konservative Defaults. Eine kommentierte Schlüsselreferenz, Notifications, `reset-state.yml`, Scheduler, Reload, Ressourcenmonitor und Endfarm-Betrieb sind im [Admin Guide](docs/ADMIN_GUIDE.md) dokumentiert.

## Dokumentation

- [Admin Guide](docs/ADMIN_GUIDE.md) – Installation, vollständige Config- und Betriebsreferenz, Commands, Permissions und Wartung.
- [Architektur](docs/ARCHITECTURE.md) – Lifecycle-, Worlds-, State-, Locking-, Notification- und Folia-Verträge.
- [Manuelle Black-Box-Abnahme](docs/testing/black-box-testing.md) – vollständige reale V2-Teststrategie.
- [V2-Abnahmematrix](docs/testing/v2-acceptance-checklist.md) – BB-01 bis BB-26 und Evidenzvorlage.
- [Automatisierter Smoke-Test](testing/blackbox/README.md) – Folia-/Worlds-Harness und CI-spezifisches Log-Gate.
- [Release-Prozess](docs/RELEASE.md) – Build-, Versions-, Tag- und GitHub-Release-Ablauf.

## Build

```bash
./gradlew clean test
./gradlew build
```

Unter Windows PowerShell entsprechend `./gradlew.bat`. Der Standardbuild erzeugt aktuell `build/libs/Farmwelt-2.0.0-SNAPSHOT.jar`; ein Release-Build erhält seine Version über `-PreleaseVersion=X.Y.Z`.
