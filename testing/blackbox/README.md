# Automatisierter Folia-/Worlds-Smoke-Test

Dieses Harness startet einen vollständig isolierten Folia-Testserver, installiert die Farmwelt-JAR aus dem aktuellen Build sowie das echte Worlds-Plugin und führt genau einen Overworld-Reset über die öffentliche Serverkonsole aus. Es ergänzt die Gradle-Tests und die [vollständige manuelle Black-Box-Anleitung](../../docs/testing/black-box-testing.md), ersetzt aber weder diese noch die [V2-Abnahmematrix BB-01 bis BB-26](../../docs/testing/v2-acceptance-checklist.md).

## Abgedeckter Ablauf

Der Lauf verwendet Java 25, Folia 26.1.2 Build 8 und Worlds 4.4.0. Alle Downloads, Hashes und Testweltnamen stehen zentral in [`versions.env`](versions.env); das Harness gleicht die Java-, Minecraft-/Folia- und Worlds-Pins vor dem Start mit der technischen Source of Truth `build.gradle.kts` ab. Folia stammt aus dem offiziellen PaperMC-Download-Service; das vollständige Worlds-Plugin `worlds-4.4.0-all.jar` stammt aus dem offiziellen Modrinth-Projekt von TheNextLvl. Es werden keine `latest`-Links oder Secrets verwendet.

Das Harness:

1. prüft Java und die zuvor gebaute Farmwelt-JAR,
2. erzeugt ein frisches temporäres Serververzeichnis unter `build/blackbox/`, einschließlich ausschließlich dort akzeptierter EULA und minimaler `server.properties`,
3. lädt die gepinnten Binaries mit Download-Timeout und prüft SHA-256 beziehungsweise SHA-512,
4. startet Folia ohne interaktive JLine-Schicht als Bash-Coprozess mit kontrollierter STDIN-Konsole und wartet logbasiert auf `Done (...)`, Worlds und Farmwelt,
5. erzeugt über `world create test_farmwelt` die separate Worlds-Welt `worlds:test_farmwelt` mit dem Bukkit-Namen `worlds_test_farmwelt`,
6. sendet `farmwelt status overworld` und `farmwelt reset force overworld`,
7. wartet auf den eindeutigen `SUCCESS`-Logeintrag, vergleicht `reset-state.yml` vor und nach dem Reset und prüft `last-reset < next-reset`,
8. verlangt genau einen Farmwelt-Aufruf der Worlds-Regeneration und genau einen Worlds-Erfolg,
9. weist die echte Regeneration durch das Verschwinden einer vorher im `region`-Bereich angelegten Markerdatei nach; unterschiedliche Seeds sind ein zusätzlicher, aber wegen der theoretisch möglichen Gleichheit nicht alleiniger Nachweis,
10. sendet `stop`, wartet auf Prozessende und führt erst danach das vollständige Log-Gate aus.

Das Log-Gate schlägt bei `SEVERE`/`ERROR`, Exception- und Stacktrace-Zeilen, ungefangenen Eventfehlern, relevanten Scheduler-/Lifecycle-Fehlern, Farmwelt-Selbstdeaktivierung, abgebrochenen oder doppelten Resets sowie kontextbezogenen Thread-/Region-Verstößen fehl. Die normalen Begriffe `Thread` oder `Region` reichen bewusst nicht aus. Die kleine [`log-allowlist.txt`](log-allowlist.txt) enthält ausschließlich bekannte Meldungen der isolierten Offline-Umgebung und des normalen Shutdowns.

## Lokale Ausführung

Voraussetzungen sind Linux oder eine kompatible Bash, Java 25, Python 3, `curl`, Standard-GNU-Werkzeuge, ungefähr 2 GiB freier Arbeitsspeicher, ausreichend Plattenplatz und Netzwerkzugriff zu PaperMC und Modrinth.

```bash
./gradlew clean test
./gradlew build
bash ./testing/blackbox/run-blackbox.sh
```

Alternativ kann die exakt zu testende JAR gesetzt werden:

```bash
BLACKBOX_PLUGIN_JAR=/absoluter/pfad/Farmwelt-2.0.0-SNAPSHOT.jar \
  bash ./testing/blackbox/run-blackbox.sh
```

Timeouts, Heap und Ausgabepfad sind über `BLACKBOX_DOWNLOAD_TIMEOUT_SECONDS`, `BLACKBOX_STARTUP_TIMEOUT_SECONDS`, `BLACKBOX_WORLD_TIMEOUT_SECONDS`, `BLACKBOX_RESET_TIMEOUT_SECONDS`, `BLACKBOX_SHUTDOWN_TIMEOUT_SECONDS`, `BLACKBOX_JAVA_XMS`, `BLACKBOX_JAVA_XMX` und `BLACKBOX_OUTPUT_DIR` anpassbar. Falls Python lokal anders heißt, kann `BLACKBOX_PYTHON=python` gesetzt werden. Jeder Lauf verwendet unabhängig davon ein neues `mktemp`-Verzeichnis. Bei Erfolg und Fehler bleiben Metadaten, Harness-Ausgabe, Serverkonsole, `latest.log`, Farmwelt-Config, State vor/nach dem Reset und Worlds-Laufzeitdaten unter `build/blackbox/run-*/artifacts/` erhalten.

Bei jedem Exit versucht das Harness zuerst einen sauberen Konsolen-Stop. Erst nach dem Shutdown-Timeout wird der Prozess als reiner Cleanup-Schritt beendet; ein solcher Lauf bleibt fehlgeschlagen.

## CI-Ausführung und Grenzen

Der Workflow `.github/workflows/blackbox.yml` verwendet denselben Entry-Point. Er läuft branch-unabhängig bei Pull Requests und manuell über `workflow_dispatch`. Ein zusätzlicher Lauf bei jedem Push ist bewusst nicht aktiviert, weil bereits der normale Build-Workflow jeden Push prüft und ein echter Minecraft-Serverlauf deutlich teurer ist. Der Job ist insgesamt auf 20 Minuten begrenzt und lädt seine Artefakte auch bei Fehlern hoch.

Nicht automatisiert werden Spieler-Login und -Evakuierung, persönliche Meldungen, GUI/Teleport, BetterRTP, GriefPrevention, DragonBattle und `--dragon`, Portal-/Kristallprüfung, mehrere parallele oder fällige Welten, Restart-Catch-up, Notification-Schwellen und persistente Langzeit-Scheduler-Szenarien. Diese Fälle bleiben Bestandteil der manuellen BB-01-bis-BB-26-Abnahme.
