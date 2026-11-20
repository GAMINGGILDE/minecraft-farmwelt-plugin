# Phase 2.2 – Plattformmigration

Basis ist `main` bei `f16478e`, einschließlich der Merge-Commits für FW-001, FW-002 und FW-003. Die Plugin-Version bleibt `2.1.0-SNAPSHOT`.

| Komponente | Bisher | Ziel und Nachweis |
| --- | --- | --- |
| Minecraft / Folia | 26.1.2 Build 8 | 26.3 Build 26 STABLE, Commit `eca40f9`, SHA-256 `99263358903dbc44d4a90d45b5fbb7dfdce532154734489be89a3a94088063dc` |
| Paper API | 26.1.2.build.74-stable | `io.papermc.paper:paper-api:26.3.build.256-stable`, SHA-256 `04891916be204f085b582c21a69a93ad2a5b73353e8bd0275ea3fc49da5b9329` |
| Worlds-API | 4.4.0 | `net.thenextlvl:worlds:4.5.1`, Maven-Releases, Compile- und Test-Classpath, SHA-256 `466d23df49345f300a2a936ebacfadd1040f57448161683ff6b7ddac6ebeafd3` |
| Worlds-Plugin | 4.4.0 | `worlds-4.5.1-all.jar`, Modrinth-Release `4y0Ijnx1` für Paper/Folia 26.3, SHA-512 `b600b5d898eecab53d712b3c0ca93b8d36b87b3815782f51b466ce16996aa8ec28532e30a4b05233dcc61368323acc4227023ba48a3143e5c65134aed886a4e4` |
| Java / Gradle / JUnit Jupiter | 25 / 9.6.1 / 6.1.2 | 25 / 9.6.1 / 6.1.2 |

Der Audit-Text vom 19. November enthielt einen verkürzten Folia-Hash und damit einen falschen Objektpfad. Die oben aufgeführten Hashes der beiden Runtime-Downloads wurden an den heruntergeladenen Dateien geprüft. Die vollständigen Download-URLs stehen in [versions.env](../../testing/blackbox/versions.env). [PaperMC-Buildmetadaten](https://fill.papermc.io/v3/projects/folia/versions/26.3/builds/26) · [Worlds-Releasedaten](https://api.modrinth.com/v2/version/4y0Ijnx1)

Folia baut auf Paper-Commit `5184e9c` auf und liefert `dev.folia:folia-api:26.3.build.26-stable` aus. Paper Build 256 gehört zu `1eda666`. Das Server-JAR führt SnakeYAML 2.2 in seiner Bibliotheksliste. Auch Gradle löst 2.2 transitiv über die Paper API auf. Die JAR von Farmwelt enthält ausschließlich eigenen Code und Ressourcen. Die Mindest-API 26.3 schließt 26.1.2 aus. Damit entfällt der laufende Server-Smoke-Nachweis auf Build 8. [Folia-Buildkonfiguration](https://github.com/PaperMC/Folia/blob/eca40f918928e78c2c5f9c944cfb083d7d9851d4/gradle.properties) · [Paper-Buildmetadaten](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/256) · [Plugin-Metadaten](https://docs.papermc.io/paper/dev/plugin-yml/#api-version)

## Worlds und API

Zwischen den Worlds-Tags 4.4.0 und 4.5.1 gibt es keine Änderungen im API-Quellbaum. Worlds ergänzt einen Adapter für 26.3. Der Aufruf `regenerate(World, Consumer<Level.Builder>)` läuft bei Worlds global an. Nach dem abbrechbaren Regenerate-Event folgt der Unload mit Unload-Event. Worlds kopiert die Level-Konfiguration, wendet den Seed-Consumer an und liefert die neu erstellte Welt im Future. Farmwelt validiert diese Instanz vor der Initialisierung und bestätigt den Zeitplan erst anschließend. Upstream kann einzelne Fehler beim Schließen oder bei Dateioperationen lediglich protokollieren. Die weitergehende Behandlung dieses Fehlervertrags gehört zu FW-006. [Worlds-Änderungen](https://github.com/TheNextLvl-net/worlds/compare/4.4.0...4.5.1)

## FW-004 bleibt offen

`PaperLevelView.delete(Path, Set<Path>)` prüft übersprungene Namen mit `toAbsolutePath().normalize()`. Diese lexikalische Normalisierung löst Verzeichnisumleitungen nicht auf. `Files.isDirectory` und anschließend `Files.list` folgen einem Verzeichnislink. Dadurch können die rekursiven Dateioperationen außerhalb des Weltbaums wirken, sofern der Serverbenutzer dort Schreibrechte besitzt. Bei der Regeneration betrifft dies insbesondere die Bäume für Entities, POI und Regionen. Nach einem zweiten Listing entfernt Worlds nur leere Verzeichnisse. Abgefangene IO-Fehler werden als Warnung protokolliert. Restdaten und ein erfolgreich abgeschlossenes Future können daher zusammen auftreten. Die Quellprüfung genügt für den Befund. [Worlds 4.5.1, PaperLevelView](https://github.com/TheNextLvl-net/worlds/blob/bc50f825922c79bf4594795d23b31c73d81a3f87/plugin/src/main/java/net/thenextlvl/worlds/view/PaperLevelView.java#L291-L381)

Destruktive Tests benötigen einen privaten Wegwerf-Runner ohne erreichbare produktive Welten, Backups oder eingebundene Datenträger. Der Testbenutzer kontrolliert den Pfad exklusiv. Das Harness erzeugt pro Ziel einen neuen privaten Laufordner, prüft auf umgeleitete Vorfahren und Mounts und inspiziert vor der Übergabe den Weltbaum einschließlich aller Kinder. Nicht auflösbare Pfade sperren den Lauf. Die Prüfung setzt voraus, dass andere Prozesse weder Pfade austauschen noch nachträglich Mounts hinzufügen können. Für den Betrieb sind getrennte Rechte und Dateisystemgrenzen erforderlich. Ein Upstream-Fix sollte Links ohne Verfolgung behandeln, die physische Grenze bei jedem Dateizugriff durchsetzen und unvollständige Operationen an den Aufrufer melden.

## Testbericht vom 20. November 2026

Der Abgleich der kompilierten Farmwelt-JAR mit den Bibliotheken des tatsächlich gestarteten Servers und dem vollständigen Worlds-Plugin erfasste 123 Plugin-Klassen und 226 unterschiedliche externe Methoden- und Feldreferenzen außerhalb des JDK. Alle Namen und JVM-Deskriptoren waren einschließlich der geerbten Mitglieder auflösbar. 178 unterschiedliche Materialnamen aus Feldern und gebündelter Konfiguration waren vorhanden. Unter den 198 verwendeten Bukkit-/Paper-Signaturen änderte sich die Darstellung von Nullability bei `Player.sendMessage(Component)`, `Player.sendActionBar(Component)` und zwei `ServerBuildInfo`-Methoden. Die verwendeten Komponenten und Rückgaben bleiben als nicht-null gekennzeichnet. Der Clean-Build erforderte keinen Austausch einer API-Signatur und meldete keine neuen Compilerwarnungen. Die statische Prüfung deckt binäre Referenzen ab. Aussagen über Threads und Verhalten stützen sich auf die jeweiligen Laufzeittests.

Beim realen Test des Vanilla-End griff zunächst unerwartet die Versionssperre. Bukkit lieferte bei fehlendem `post-reset.end.dragon` den Wert aus den Ressourcen der JAR. Der Parser prüft deshalb an diesem Schlüssel mit `contains("dragon", true)`, ob in der Server-Konfiguration ein Wert steht. Drei Regressionstests bilden den fehlenden Post-Reset-Bereich, den fehlenden End-Bereich und die fehlende Dragon-Einstellung mit gesetztem Bukkit-Default nach. Alle drei schlugen vor der Änderung fehl. Der ausgelieferte explizite Standardwert bleibt wirksam.

| Prüfung | Beobachtetes Ergebnis |
| --- | --- |
| Java 25 unter Windows | Clean-Test und Build erfolgreich. 419 bestanden, 0 fehlgeschlagen, 2 bestehende Dateisystemtests konnten unter Windows keine Symlinks anlegen. |
| Linux-CI | 421 bestanden, 0 fehlgeschlagen, 0 übersprungen, anhand der JUnit-XML-Dateien zum Implementierungscommit `bbf935b` gezählt. Beide Isolations- und Log-Gates bestanden. [Build](https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/actions/runs/44716372065) · [Aktuelle PR-Prüfungen](https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/pull/18/checks) |
| Overworld | Eine Regeneration. Seed von `-6972096180156758529` auf `290368548202317547`. Neuer Instanz- und Markernachweis, Gamerule `false`, Border 512, passender Journal- und Zeitplanbeleg `fc94c69c-7118-4016-a657-59f1eec2aa8b`, Folgezeitpunkt nach 30 Tagen, sauberer Shutdown. [Job](https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/actions/runs/44716372057/job/136220699708) |
| Nether | Eine Regeneration der separaten Welt `worlds:test_nether`. Seed von `3563772746192694702` auf `1974364550057994421`. Dieselben Prüfungen einschließlich Nether-Dimension und Journal `09ece8fe-8961-4564-9c74-0450a82e2f58`. [Job](https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin/actions/runs/44716372057/job/136220700442) |
| Reale Negativprüfung nach der Parserkorrektur | Folia 26.3 Build 26 und Worlds 4.5.1 lehnten die Hauptwelt, Vanilla-Nether, Vanilla-End, eine falsch zugeordnete Dimension und ein unregistriertes Ziel vor Worlds ab. Zeitplan und Journale blieben unverändert. Ein ausschließlich im Prüflauf installierter Listener hätte jede versehentliche Regeneration abgebrochen. Er erhielt kein Regenerate-Event. Veraltete Instanzen deckt zusätzlich der Engine-Regressionstest ab. |
| End-Gate | Auf einem frischen Server ohne geladene Ziel-Endfarm wurden vier Varianten vor der Weltprüfung abgelehnt: explizit `false`, explizit `true`, Override bei `false` und Override ohne End-Abschnitt. Jeweils null Regenerationen, unveränderter State und `READY`. Scope, Listener und Saved-Data-Zugriffe sind zusätzlich durch FW-001-Regressionstests abgedeckt. |
| Optionale Plugins | Start und Reload funktionierten ohne BetterRTP und GriefPrevention. Mit der ausgelieferten Konfiguration meldete `/farmwelt info` drei Menüwelten und beide optionalen Plugins als inaktiv. Wegen des fehlenden Claim-Providers griff `disable-monitor`. Eine Installation der optionalen Plugins mit Spieleraktionen gehört zur späteren Mehrspielerabnahme. |

### Dateien im Pull Request

| Dateien | Änderung |
| --- | --- |
| `build.gradle.kts`, `src/main/resources/paper-plugin.yml` | Compile-/Test-Pins und Mindest-API. |
| `FarmworldPostResetConfigParser.java`, `FarmworldResetConfigParserTest.java`, `FarmworldResetEngineTest.java` | Explizite End-Policy und frühe Ablehnung mit unverändertem Journal. |
| `.github/workflows/blackbox.yml`, `testing/blackbox/versions.env` | Getrennte CI-Jobs und Artefakte, feste Runtime-Pins und Prüfsummen. |
| `testing/blackbox/run-blackbox.sh`, `fixtures/config.yml` | Isolierte Testziele, Kontrolle der Vanilla-Regionen, Serverkennung, Seedwechsel und Post-Reset-Abfragen. |
| `testing/blackbox/check-isolation.py`, `test-isolation.py` | Physische Pfade und Mount-Grenzen prüfen, zulässige und gesperrte Testpfade abdecken. |
| `testing/blackbox/assert-state.py`, `assert-log.sh` | UUID, Zeitplan, Ziel und Phase sowie Logs und Regenerationsanzahl prüfen. |
| Log-Fixtures unter `testing/blackbox/fixtures/`: `log-foreign-watchdog-thread.log`, `log-foreign-watchdog.log`, `log-known-worlds-watchdogs.log`, `log-real-exception.log`, `log-too-long-worlds-watchdog.log`, `log-too-many-known-watchdogs.log`, `log-worlds-watchdog-outside-phase.log` | Versionszeile der synthetischen Logs aktualisiert. |
| `AGENTS.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/ADMIN_GUIDE.md`, `docs/RELEASE.md` | Zielplattform, Freigabegrenzen und Betrieb. |
| `testing/blackbox/README.md`, `docs/testing/black-box-testing.md`, `docs/testing/v2-acceptance-template.md`, `docs/testing/phase-2.2.md` | Testanleitung, Abnahmekriterien, Versionsmatrix und Befunde. |

## Abnahmegrenzen

FW-001 prüft die Version vor Evakuierung, Journal und Worlds. Die interne Freigabe bleibt exakt 26.1.2. Auf 26.3 sind `dragon: false`, `dragon: true` und `--dragon` einschließlich des Overrides ohne End-Abschnitt gesperrt. Ohne wirksame Policy gilt `PRESERVE`. FW-002 und FW-003 sichern Versuch, Zeitplan und beide Zielidentitäten weiterhin. BetterRTP bleibt ein optionales Befehlsziel, GriefPrevention ein optionaler reflektiver Claim-Provider.

Die technische Endfarm-Abnahme folgt in Phase 2.4. DragonBattle-Threading (FW-005), Worlds-Fehlervertrag (FW-006), Scheduler-/Future-Härtung (FW-007) und die manuelle Mehrspielerabnahme bleiben offen. Eine Produktionsfreigabe steht aus.
