<p align="center">
  <img src="assets/logo.png" alt="Farmwelt" width="360">
</p>
<p align="center">
  <a href="https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/GAMINGGILDE/minecraft-farmwelt-plugin/build.yml?branch=main&amp;label=build&amp;style=flat-square&amp;logo=githubactions&amp;logoColor=white"></a>
  <a href="https://www.codefactor.io/repository/github/gaminggilde/minecraft-farmwelt-plugin"><img src="https://www.codefactor.io/repository/github/gaminggilde/minecraft-farmwelt-plugin/badge?style=flat-square" alt="CodeFactor"></a>
  <a href="https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/releases"><img alt="Release" src="https://img.shields.io/github/v/release/GAMINGGILDE/minecraft-farmwelt-plugin?label=release&amp;cacheSeconds=300&amp;style=flat-square&amp;logo=github&amp;logoColor=white"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/GAMINGGILDE/minecraft-farmwelt-plugin?style=flat-square&amp;logo=opensourceinitiative&amp;logoColor=white"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-blue?style=flat-square">
  <img alt="Folia supported" src="https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square">
</p>

# Farmwelt

Farmwelt ist ein Paper/Folia-Plugin für Minecraft-Server. Es führt Spieler über eine konfigurierbare GUI in getrennte Farmwelten, überwacht Ressourcenabbau in normalen Welten und setzt Overworld-, Nether- und End-Farmwelten sicher über Worlds zurück.

Das Plugin ist kein Claim- oder allgemeines Anti-Grief-System. GriefPrevention kann optional angebunden werden, damit Claims vom Ressourcenmonitor ausgenommen bleiben.

## Features

- Konfigurierbares 45-Slot-Menü unter `/farmwelt` mit befehlsbasierten Teleports, standardmäßig über BetterRTP.
- Nächster Reset-Termin und Restzeit direkt bei jeder Farmwelt im Menü, mit Warnfarben und sichtbarem Reset-Status.
- Ressourcenmonitor mit den Modi `audit`, `warn` und `enforce`, Staff-Meldungen, Claim-Ausnahmen und optionaler Jail-Eskalation.
- Persistente, automatische Reset-Zeitpläne sowie sichere manuelle Force-Resets für Overworld, Nether und End.
- Countdown-, Start-, Erfolgs-, optionale Fehler- und persönliche Evakuierungsmeldungen.
- Post-Reset-Gamerules, WorldBorder und eine ausdrücklich konfigurierte Enderdragon-Policy.
- Folia-gerechte Ausführung, Diagnosebefehle und ein echter automatisierter Folia-/Worlds-Smoke-Test.

Ein ungeklärter Reset sperrt weitere Regenerationen dieser Farmwelt auch nach einem Neustart. `/farmwelt status <welt>` nennt den Versuch und die zuletzt bestätigte Phase. Den Ablauf für die Wiederherstellung beschreibt der [Admin Guide](docs/ADMIN_GUIDE.md#recovery-nach-einem-unterbrochenen-reset).

Auf Folia 26.3 sind Overworld- und Nether-Resets vorgesehen. End-Resets mit konfiguriertem `dragon` oder `--dragon` enden vor der Evakuierung mit `UNSUPPORTED_DRAGON_VERSION`. Ohne wirksame Dragon-Policy gilt `PRESERVE`. Die technische Endfarm-Abnahme folgt in Phase 2.4. Die Betriebsdetails stehen im [Admin Guide](docs/ADMIN_GUIDE.md).

Spieler sehen unter `/farmwelt` beim jeweiligen Welt-Item den geplanten Reset in der Server-Zeitzone und die ungefähre Restzeit. Die Anzeige wird bei jedem Öffnen aktualisiert; bei höchstens einer Stunde Restzeit ist sie gelb, bei höchstens fünf Minuten rot. Der Info-Kompass erklärt, dass platzierte Blöcke und gelagerte Items beim Reset verloren gehen. Dafür genügt die bestehende Permission `farmwelt.use`.

## Voraussetzungen

| Komponente | Status | Aktueller Stand |
| --- | --- | --- |
| Java | erforderlich | 25 |
| Minecraft/Paper/Folia | erforderlich | 26.3, Folia Build 26, Build gegen `paper-api:26.3.build.256-stable` |
| Worlds | erforderlich | 4.5.1; übernimmt den dynamischen Welt-Lifecycle |
| BetterRTP | optional | Standard-Teleportbefehle verwenden BetterRTP |
| GriefPrevention | optional | Claim-Ausnahmen für den Ressourcenmonitor |

Ohne Worlds lädt Farmwelt nicht. Es gibt keinen Bukkit-Fallback für die Weltregeneration. Ohne BetterRTP muss für jeden GUI-Eintrag ein anderer unterstützter Teleportbefehl konfiguriert werden.

## Installation

1. Worlds 4.5.1 und dessen Servervoraussetzungen installieren.
2. Farmwelt-JAR in den Serverordner `plugins/` legen.
3. Server starten und `plugins/Farmwelt/config.yml` prüfen.
4. Bukkit-Weltnamen, Teleportbefehle und optionale GriefPrevention-Integration anpassen.
5. Mit `/farmwelt info`, `/farmwelt status` und als Spieler mit `/farmwelt` prüfen.

Phase 2.2 hat keine Produktionsfreigabe. FW-004 betrifft die Löschgrenzen von Worlds. Betriebsbedingungen und Versionsnachweise stehen im [Migrationsbericht](docs/testing/phase-2.2.md). Die Mindest-API 26.3 schließt das Laden dieser JAR auf 26.1.2 aus.

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

Die Reset-IDs heißen `overworld`, `nether` und `end`. Config und Befehle akzeptieren auch `Overworld` oder `OVERWORLD`. Alle Schreibweisen teilen sich Zeitplan, Reset-Sperre und Recovery-Journal. Jede ID und jeder physische Zielname darf nur einmal konfiguriert sein, auch bei deaktivierten Plänen. Der Bukkit-Weltname in `reset.world` muss exakt zur geladenen Welt passen.

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

Bei verzögerten Resets zeigt `/farmwelt status <welt>` Ziel, Versuch und letzte Pipeline-Phase. Nach zehn Minuten prüft die Startup-Nachholung weitere Welten. Eine weiterhin offene Worlds-Übergabe kann weitere Regenerationen zurückstellen.

Bei „Worlds-Abschluss ungeklärt“ sperrt Farmwelt sofort weitere Regenerationen bis zur Klärung bei gestopptem Server. Ein bestätigter Worlds-Fehler sperrt über das Recovery-Journal nur das betroffene Ziel.

Die ausgelieferte [`config.yml`](src/main/resources/config.yml) enthält alle verfügbaren Konfigurationsbereiche und konservative Defaults. Eine kommentierte Schlüsselreferenz, Notifications, `reset-state.yml`, Scheduler, Reload, Ressourcenmonitor und Endfarm-Betrieb sind im [Admin Guide](docs/ADMIN_GUIDE.md) dokumentiert.

## Dokumentation

- [Admin Guide](docs/ADMIN_GUIDE.md) – Installation, vollständige Config- und Betriebsreferenz, Commands, Permissions und Wartung.
- [Architektur](docs/ARCHITECTURE.md) – Lifecycle-, Worlds-, State-, Locking-, Notification- und Folia-Verträge.
- [Manuelle Black-Box-Abnahme](docs/testing/black-box-testing.md) – vollständige reale V2-Teststrategie.
- [V2-Abnahmevorlage](docs/testing/v2-acceptance-template.md) – wiederverwendbare Matrix für BB-01 bis BB-26 und die zugehörige Evidenz.
- [Automatisierter Smoke-Test](testing/blackbox/README.md) – Folia-/Worlds-Harness und CI-spezifisches Log-Gate.
- [Release-Prozess](docs/RELEASE.md) – Build-, Versions-, Tag- und GitHub-Release-Ablauf.

## Build

```bash
./gradlew clean test
./gradlew build
```

Unter Windows PowerShell entsprechend `./gradlew.bat`. Der Standardbuild erzeugt aktuell `build/libs/Farmwelt-2.1.0-SNAPSHOT.jar`; ein Release-Build erhält seine Version über `-PreleaseVersion=X.Y.Z`.
