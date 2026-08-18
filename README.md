<p align="center">
  <img src="assets/logo.png"
       alt="Farmwelt"
       width="360">
</p>
<p align="center">
  <a href="https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/GAMINGGILDE/minecraft-farmwelt-plugin/build.yml?branch=main&amp;label=build&amp;style=flat-square&amp;logo=githubactions&amp;logoColor=white"></a>
  <a href="https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/releases"><img alt="Release" src="https://img.shields.io/github/v/release/GAMINGGILDE/minecraft-farmwelt-plugin?label=release&amp;cacheSeconds=300&amp;style=flat-square&amp;logo=github&amp;logoColor=white"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/GAMINGGILDE/minecraft-farmwelt-plugin?style=flat-square&amp;logo=opensourceinitiative&amp;logoColor=white"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Paper 26.1.2" src="https://img.shields.io/badge/Paper-26.1.2-blue?style=flat-square">
  <img alt="Folia supported" src="https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square">
  <a href="https://discord.minecraft-gilde.de"><img alt="Join Discord" src="https://img.shields.io/badge/Discord-Join-5865F2?style=flat-square&amp;logo=discord&amp;logoColor=white"></a>
</p>

# Farmwelt

Farmwelt ist ein Paper/Folia-Plugin für Minecraft-Server. Es stellt einen zentralen `/farmwelt`-Befehl mit GUI bereit und kann Ressourcenabbau in normalen Welten erkennen, warnen und optional sichtbar blockieren.

Das Plugin soll Spieler in Farmwelten lenken und Moderatoren entlasten. Es ist kein klassisches Anti-Grief-Plugin, ersetzt keine Claim-Protection und schützt keine Grundstücke. Der Ressourcenmonitor erkennt konfigurierten Ressourcenabbau in der Wildnis; Grundstücke/Claims bleiben bei aktivem GriefPrevention-Hook ausgenommen.

## Hauptfunktionen

- Zentrale `/farmwelt`-GUI mit konfigurierbaren Farmwelt-Einträgen.
- Teleport über konfigurierbare Befehle, zum Beispiel BetterRTP.
- Ressourcenmonitor für normale Welten.
- Weltbezogene Ressourcenregeln für Overworld, Nether und End.
- Claim-Ausnahme über GriefPrevention.
- `audit`-Modus zum Beobachten ohne Spielerwarnung und ohne Blockieren.
- `warn`-Modus mit Spielerwarnungen und Staff-Benachrichtigungen.
- `enforce`-Modus mit sichtbarem Blockabbruch ab konfigurierter Schwelle.
- Explosionsschutz im `enforce`-Modus: erkannte Ressourcen werden aus Explosionslisten entfernt.
- Violation-Zähler mit Zeitfenster und Cooldowns.
- Reset-Status und sichere manuelle Resets über den bestehenden `/farmwelt`-Befehl.
- `/farmwelt info`, `/farmwelt reload` und Debug-Befehle.
- GitHub Action für den Gradle-Build.

## Voraussetzungen

- Paper/Folia-kompatibler Server.
- Java 25.
- Minecraft/Paper API 26.1.2, das Projekt baut aktuell gegen `paper-api:26.1.2.build.74-stable`.
- Worlds 4.4.0 ist eine erforderliche Server-Abhängigkeit und übernimmt die dynamische Weltregeneration.
- BetterRTP ist optional, aber für die Standard-Teleportbefehle empfohlen.
- GriefPrevention ist optional, aber für Claim-Ausnahmen empfohlen.
- EssentialsX ist keine Abhängigkeit.

In `paper-plugin.yml` ist Worlds als harte und BetterRTP/GriefPrevention als optionale Server-Abhängigkeit eingetragen:

- `Worlds`: `required: true`, `join-classpath: true`.
- `BetterRTP`: `required: false`, `join-classpath: false`.
- `GriefPrevention`: `required: false`, `join-classpath: true`.

Ohne Worlds wird Farmwelt nicht geladen. Es gibt keinen Fallback auf Bukkit-Unload, eigene Dateilöschung oder `WorldCreator`. Das Plugin startet weiterhin ohne BetterRTP; dann schlagen aber die standardmäßig konfigurierten BetterRTP-Befehle fehl, bis andere Teleportbefehle konfiguriert werden.

## Installation

1. Worlds 4.4.0 und seine Servervoraussetzungen installieren.
2. Farmwelt bauen oder eine fertige JAR verwenden.
3. Die JAR aus `build/libs/` in den `plugins`-Ordner des Servers legen.
4. Server starten.
5. `plugins/Farmwelt/config.yml` prüfen.
6. BetterRTP-Ziele und Welt-Namen prüfen.
7. GriefPrevention-Hook prüfen, falls Claim-Ausnahmen genutzt werden.
8. `/farmwelt info` ausführen.
9. `/farmwelt` als Spieler testen.

## Build

Linux/macOS:

```bash
./gradlew build
```

Windows PowerShell:

```bat
.\gradlew.bat build
```

Die Plugin-JAR wird unter `build/libs/` erzeugt. Der Archivname beginnt mit `Farmwelt`, aktuell zum Beispiel `Farmwelt-0.1.0-SNAPSHOT.jar`.

## Release

GitHub Actions baut automatisch eine Release-JAR, wenn ein GitHub Release veröffentlicht wird. Der Workflow `.github/workflows/release.yml` verwendet den Release-Tag als Plugin-Version und lädt die fertige JAR direkt als Asset in den GitHub Release hoch.

Die vollständige Reihenfolge mit Befehlen steht in [docs/RELEASE.md](docs/RELEASE.md).

## Befehle

| Befehl | Zweck | Permission | Empfohlen für |
| --- | --- | --- | --- |
| `/farmwelt` | Öffnet die Farmwelt-GUI. | `farmwelt.use` | Spieler |
| `/farmwelt status` | Zeigt den Reset-Status aller konfigurierten Farmwelten. | `farmwelt.admin.status` | Admins |
| `/farmwelt status <welt>` | Zeigt Reset-Details für eine logische ID wie `overworld`. | `farmwelt.admin.status` | Admins |
| `/farmwelt info` | Zeigt Version, geladene Farmwelten, Monitor-Modus, Hook-Status und Jail-Modus. | `farmwelt.admin` | Admins |
| `/farmwelt reload` | Lädt GUI-, Reset- und Ressourcenmonitor-Konfiguration neu. | `farmwelt.admin.reload` | Admins |
| `/farmwelt reset force <welt>` | Startet sofort die vollständige sichere Reset-Pipeline. | `farmwelt.admin.reset` | Admins |
| `/farmwelt reset force end --dragon` | Initialisiert nur für diesen End-Reset einen frischen Vanilla-Drachenkampf. | `farmwelt.admin.reset` | Admins |
| `/farmwelt debug claim` | Prüft den Claim-Provider und ob die aktuelle Spielerposition in einem Claim liegt. | `farmwelt.admin` | Admins/Technik |
| `/farmwelt debug monitor` | Schaltet einen Debug-Modus um; danach kann ein Block per Rechtsklick geprüft werden. | `farmwelt.admin` | Admins/Technik |
| `/farmwelt debug violations [spieler]` | Zeigt den aktuellen Violation-Status des eigenen oder eines online Spielers. | `farmwelt.admin` | Admins/Moderation |

Status, Reload und Force-Reset können auch aus der Konsole ausgeführt werden. Die Debug-Befehle sind Spielerbefehle, weil sie Positionen, Rechtsklicks oder online Spieler verwenden. Bei Status und Reset ist `<welt>` immer die logische ID `overworld`, `nether` oder `end`, niemals ein frei eingegebener Bukkit-Weltname.

`/farmwelt reset force <welt>` führt sofort einen vollständigen Reset aus und sollte nur von Administratoren verwendet werden. `force` überspringt ausschließlich den zukünftigen Termin. Deaktivierung, Reset-Lock, API-basierter Hauptweltschutz, Evakuierung, Worlds-Regeneration, Ergebnisvalidierung, Post-Reset-Initialisierung und State-Persistenz bleiben Teil der normalen sicheren Pipeline. `--dragon` ist nur für die End-Farmwelt zulässig. Es setzt den DragonBattle-Zustand für diesen Reset auf einen frischen Erstkampf und hält die einmalige Spawn-Freigabe bei `dragon: false` bis zum tatsächlichen Vanilla-Spawn offen; die Config wird dabei nicht geändert. Ohne `--dragon` beendet `dragon: false` den geladenen Kampf vollständig, blendet die Bossbar aus und erzeugt ein aktives End-Ausgangsportal. Nach dem Tod eines freigegebenen Drachen wird das aktive Ausgangsportal in der End-Farmwelt nochmals verifiziert und bei Bedarf aufgebaut.

Nach einer Regeneration können ausschließlich konfigurierte Gamerules, eine WorldBorder-Größe und für das End die Dragon-Policy angewendet werden:

```yaml
reset:
  post-reset:
    gamerules:
      players_sleeping_percentage: 50
      show_advancement_messages: false
    world-border:
      size: 20000
    end:
      dragon: false
```

Fehlende Unterabschnitte verändern die jeweilige Einstellung nicht. Gamerules werden über die Bukkit-Registry aufgelöst und entsprechend ihrem API-Typ gesetzt; es werden dafür keine Minecraft-Commands ausgeführt.

Die Verantwortungsgrenze ist bewusst schmal: Farmwelt orchestriert Konfiguration, Lock, Teleport-Sperre, Spieler-Evakuierung, Fehler und Reset-State. Worlds besitzt den versionsspezifischen dynamischen Welt-Lifecycle einschließlich Entladen, Regenerieren und erneutem Laden unter Folia.

## Permissions

| Permission | Bedeutung | Empfohlene Gruppe | Hinweis |
| --- | --- | --- | --- |
| `farmwelt.use` | Darf `/farmwelt` verwenden. | Spieler | Standardmäßig `true`. |
| `farmwelt.admin` | Darf Info-/Debug-Befehle und über Child-Permissions alle Admin-Funktionen verwenden. | Admins/Technik | Standardmäßig `op`. |
| `farmwelt.admin.status` | Darf Reset-Status und Details anzeigen. | Admins/Technik | Standardmäßig `op`. |
| `farmwelt.admin.reload` | Darf die Konfiguration neu laden. | Admins/Technik | Standardmäßig `op`. |
| `farmwelt.admin.reset` | Darf sofortige manuelle Farmwelt-Resets starten. | Admins/Technik | Standardmäßig `op`. |
| `farmwelt.bypass` | Wird vom Ressourcenmonitor ignoriert. | Admins, ggf. Builder | Spieler mit Bypass erhalten keine Warnungen und werden nicht blockiert. |
| `farmwelt.notify` | Erhält Staff-Benachrichtigungen. | Moderation/Admins | Wird für Audit- und Violation-Meldungen verwendet. |

Die tatsächlich verwendeten Permission-Namen können für Bypass und Notify in der Config angepasst werden:

```yaml
resource-monitor:
  bypass-permission: farmwelt.bypass
  notify-permission: farmwelt.notify
```

## Betriebsmodi

Der Modus wird über `resource-monitor.mode` gesetzt.

### `audit`

`audit` erkennt Ressourcenabbau, loggt Ereignisse und kann Staff informieren. Spieler werden nicht gewarnt und Blöcke werden nicht blockiert. Dieser Modus ist für die Einführung und Fehlersuche gedacht.

### `warn`

`warn` zählt Verstöße im konfigurierten Zeitfenster. Ab den Schwellen in `actions.warning` und `actions.notify-staff` werden Spieler gewarnt und Staff kann informiert werden. Blöcke werden nicht blockiert.

### `enforce`

Geschützte Item-Loots aus `protected-items`, zum Beispiel Elytren in End-City-Item-Frames, werden in `enforce` sofort blockiert, wenn `actions.cancel-break.enabled` aktiv ist.

`enforce` zählt Verstöße, warnt Spieler und kann Ressourcenabbau ab der `cancel-break`-Schwelle abbrechen. Zusätzlich entfernt der Ressourcenmonitor erkannte Ressourcenblöcke aus Explosionslisten, wenn `actions.cancel-break.enabled` aktiv ist. Explosionen selbst werden dabei nicht komplett abgebrochen; nur die geschützten Ressourcen bleiben stehen. Es gibt keinen Kick. Eine Jail-Eskalation ist zwar als optionale Config-Stufe vorhanden, aber standardmäßig deaktiviert und sollte nicht ohne Tests aktiviert werden.

## Weltregeln

Weltregeln stehen unter `resource-monitor.world-rules`.

```yaml
resource-monitor:
  world-rules:
    world:
      type: overworld
      resources: []

    world_nether:
      type: nether
      resources: []

    world_the_end:
      type: end
      protected-items:
        - ELYTRA
      resources: []
```

- Overworld, Nether und End verwenden jeweils die Liste `resources`.
- `protected-items` schützt Item-Loot aus Item Frames in überwachten Welten. Die Standardconfig nutzt das für Elytren im normalen End.
- Es gibt keine Höhenprüfung: Ein Material in `resources` wird auf jeder Y-Höhe erkannt.
- Nur Materialien in diesen Listen zählen als relevante Ressourcen.
- Eine Welt muss in `monitored-worlds` stehen und darf nicht in `ignored-worlds` stehen.
- Die Standardconfig nutzt bewusst breite Materiallisten für Minecraft/Paper 26.1.2, unter anderem Holz/Stämme, Erze, Amethyst, Sand/Gravel/Clay/Mud, Terracotta, Eis, Nether- und End-Ressourcen. Entferne Materialien, die in deiner Hauptwelt ausdrücklich erlaubt sein sollen.

## Claims / GriefPrevention

Wenn `resource-monitor.claim-protection.enabled` aktiv ist, prüft das Plugin GriefPrevention per Hook. Entscheidend ist beim Ressourcenmonitor die Position des abgebauten Blocks, nicht die Spielerposition. Abbau innerhalb von Claims wird ignoriert, sofern `skip-inside-claims: true` gesetzt ist.

Dadurch bleiben Grundstücke nutzbar und der Monitor greift vor allem in der Wildnis. Die Debug-Ausgabe `/farmwelt debug claim` prüft dagegen die aktuelle Spielerposition, damit Admins den Hook schnell testen können.

Standardauszug:

```yaml
resource-monitor:
  claim-protection:
    enabled: true
    provider: GriefPrevention
    skip-inside-claims: true
    fail-mode: disable-monitor
    ignore-height: true
```

Bei `fail-mode: disable-monitor` bleibt der Ressourcenmonitor sicherheitshalber inaktiv, wenn der konfigurierte Claim-Provider fehlt oder nicht verfügbar ist.

## BetterRTP-Integration

Farmwelt implementiert keine eigene Random-Teleport-Logik. Ein Klick in der GUI führt den pro Farmwelt konfigurierten Befehl aus.

Standardbeispiel:

```yaml
farmworlds:
  overworld:
    enabled: true
    display-name: "Farmwelt"
    icon: GRASS_BLOCK
    slot: 11
    lore:
      - "Normale Farmwelt"
      - "Für Holz, Sand, Erde und weitere Ressourcen"
    teleport:
      type: command
      sender: player
      command: "betterrtp:rtp world Farmwelt"
```

`sender: player` führt den Befehl als Spieler aus. Dadurch können BetterRTP-Permissions, Cooldowns und Limits normal greifen. `sender: console` ist ebenfalls implementiert und führt den Befehl über die Konsole aus; dann müssen Platzhalter und Zielbefehl entsprechend sicher konfiguriert werden.

Unterstützte Platzhalter im Teleportbefehl:

- `{player}`
- `{world}` und `{display-name}`: Anzeigename des GUI-Eintrags
- `{id}`: Config-ID des Farmwelt-Eintrags

## Beispiel-Config

Gekürztes Beispiel mit den wichtigsten Bereichen:

```yaml
farmworlds:
  overworld:
    enabled: true
    display-name: "Farmwelt"
    icon: GRASS_BLOCK
    slot: 11
    lore:
      - "Normale Farmwelt"
    teleport:
      type: command
      sender: player
      command: "betterrtp:rtp world Farmwelt"

resource-monitor:
  enabled: true
  mode: audit
  monitored-worlds:
    - world
    - world_nether
    - world_the_end
  ignored-worlds:
    - farmwelt
    - netherfarm
    - endfarm
  bypass-permission: farmwelt.bypass
  notify-permission: farmwelt.notify
  violation-window-seconds: 600

  claim-protection:
    enabled: true
    provider: GriefPrevention
    skip-inside-claims: true
    fail-mode: disable-monitor
    ignore-height: true

  audit:
    notify-staff: true
    log-to-console: true
    log-cooldown-seconds: 120

  actions:
    warning:
      enabled: true
      after-blocks: 5
      cooldown-seconds: 60
      message: "&eBitte nutze für Ressourcen die Farmwelten mit &6/farmwelt&e."
    notify-staff:
      enabled: true
      after-blocks: 10
      cooldown-seconds: 60
      message: "&e[Farmwelt] &f{player} baut Ressourcen in &7{world} &fab."
    cancel-break:
      enabled: true
      after-blocks: 15
      cooldown-seconds: 10
      message: "&cDer Ressourcenabbau in dieser Welt ist jetzt blockiert."
      actionbar-message: "&cRessourcenabbau blockiert! Nutze &e/farmwelt&c."
    jail:
      enabled: false
      mode: notify-only

  world-rules:
    world:
      type: overworld
      resources:
        - OAK_LOG
        - PALE_OAK_LOG
        - SAND
        - GRAVEL
        - MUD
        - COAL_ORE
        - DEEPSLATE_COAL_ORE
        - IRON_ORE
        - DIAMOND_ORE
        - AMETHYST_CLUSTER
    world_nether:
      type: nether
      resources:
        - NETHERRACK
        - NETHER_QUARTZ_ORE
        - ANCIENT_DEBRIS
        - GLOWSTONE
    world_the_end:
      type: end
      protected-items:
        - ELYTRA
      resources:
        - END_STONE
        - CHORUS_PLANT
```

## Empfohlener Live-Betrieb

1. Zuerst `mode: audit` verwenden.
2. Logs und Staff-Meldungen prüfen.
3. Weltregeln, Claim-Hook und Materiallisten korrigieren.
4. Danach `mode: warn` aktivieren.
5. Schwellenwerte und Cooldowns beobachten.
6. Erst nach Tests `mode: enforce` aktivieren.
7. Optionale harte Sanktionen wie `actions.jail` zunächst deaktiviert lassen.

`enforce` sollte erst aktiviert werden, wenn die wichtigsten Welten, Claims und Ressourcenlisten auf dem Live-Setup getestet wurden.

## Performance-Hinweise

- Der Ressourcenmonitor reagiert auf Blockabbau-Events sowie im `enforce`-Modus auf Block- und Entity-Explosionen.
- Bei Explosionen werden nur erkannte Ressourcenblöcke aus der Explosionsliste entfernt; andere Blöcke der Explosion bleiben unverändert.
- Der Ressourcenmonitor bricht früh ab, wenn der Monitor deaktiviert ist, die Welt nicht überwacht wird oder der Spieler Bypass hat.
- Die Materiallisten werden beim Laden der Config in Material-Sets vorbereitet und nicht pro Event aus der Config gelesen.
- Die Claim-Prüfung erfolgt erst nach Welt-, Bypass- und Ressourcenprüfung.
- Audit-, Warn-, Notify- und Blockiermeldungen haben konfigurierbare Cooldowns. Der Audit-Cooldown gilt pro Spieler, Material und Kategorie; wiederholte Treffer setzen die Ruhezeit zurück.
- Debug-Befehle sind Diagnosewerkzeuge und sollten nur für Admins verfügbar sein.

## Troubleshooting

### `/farmwelt` öffnet keine GUI

- Spieler hat `farmwelt.use` nicht.
- `farmworlds` fehlt oder enthält keine aktivierten gültigen Einträge.
- Ein Eintrag hat ein ungültiges Icon, einen ungültigen Slot oder keine Teleport-Konfiguration.
- Es gibt Config-Fehler im Serverlog.

### Klick teleportiert nicht

- BetterRTP ist nicht installiert oder nicht aktiv.
- Der konfigurierte Befehl ist falsch.
- Der Spieler hat bei `sender: player` keine BetterRTP-Permission.
- BetterRTP-Cooldown oder Limit blockiert den Teleport.
- Der Weltname im BetterRTP-Befehl stimmt nicht.

### Ressourcen werden nicht erkannt

- `resource-monitor.enabled` ist `false`.
- `mode` ist kein gültiger Wert (`audit`, `warn`, `enforce`).
- Welt steht nicht in `monitored-worlds`.
- Welt steht in `ignored-worlds`.
- Es gibt keine passende `world-rules`-Regel für die Welt.
- Block ist nicht in der passenden Ressourcenliste.
- Spieler hat `farmwelt.bypass`.
- Block liegt in einem Claim und Claim-Ausnahmen sind aktiv.
- GriefPrevention fehlt und `fail-mode: disable-monitor` deaktiviert den Monitor.

### Ressourcen werden in Claims erkannt

- GriefPrevention ist nicht installiert oder nicht aktiv.
- `claim-protection.enabled` ist `false`.
- `skip-inside-claims` ist `false`.
- `provider` ist falsch geschrieben.
- Der Hook ist laut `/farmwelt info` nicht aktiv.

### Enforce blockiert nicht

- `resource-monitor.mode` ist nicht `enforce`.
- Die `cancel-break`-Schwelle wurde noch nicht erreicht.
- `actions.cancel-break.enabled` ist `false`.
- Spieler hat Bypass.
- Der Block wird nicht als Ressource erkannt.
- Der Block liegt in einem Claim.

### Explosionen zerstören Ressourcen

- `resource-monitor.mode` ist nicht `enforce`.
- `actions.cancel-break.enabled` ist `false`.
- Welt steht nicht in `monitored-worlds` oder steht in `ignored-worlds`.
- Der Block wird nicht als Ressource erkannt.
- Block liegt in einem Claim und Claim-Ausnahmen sind aktiv.
- Ein anderes Plugin verändert die Explosion nach Farmwelt erneut.

## Entwicklungshinweise

- Java/Gradle-Projekt mit Java 25 Toolchain.
- Hauptpackage: `de.minecraftgilde.farmwelt`.
- Hauptklasse: `FarmweltPlugin`.
- Build: `./gradlew build` bzw. `.\gradlew.bat build`.
- CI: `.github/workflows/build.yml` führt den Gradle-Build mit Temurin Java 25 aus.
- Release: `.github/workflows/release.yml` baut bei veröffentlichten GitHub Releases eine JAR und lädt sie als Release-Asset hoch.
- Wichtige Bereiche:
  - `command/`: `/farmwelt` und Subcommands.
  - `gui/`: Farmwelt-GUI.
  - `listener/`: GUI-Klicks und Ressourcenmonitor.
  - `service/`: Teleport, Claims, Ressourcen-Erkennung, Violations, Nachrichten, Jail-Aktion.
  - `config/`: Config-Laden und vorbereitete Regeln.
- Falls Code-Kommentare ergänzt werden, sollen sie auf Deutsch sein.

Weitere operative Details stehen in [docs/ADMIN_GUIDE.md](docs/ADMIN_GUIDE.md). Der technische Aufbau ist in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) dokumentiert.
