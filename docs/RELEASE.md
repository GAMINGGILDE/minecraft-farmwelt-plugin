# Release-Prozess

Diese Anleitung bereitet das geplante Release `v2.0.0` vor. In Phase 6.3 wird ausdrücklich noch kein Release erzeugt, kein Tag erstellt und `feature/v2` nicht nach `main` gemergt. Diese Schritte erfolgen erst nach der finalen V2-Abnahme in Phase 6.4.

## Technische Source of Truth

- Java-Toolchain: 25.
- Standard-Projektversion: `2.0.0-SNAPSHOT`.
- Release-Version: Gradle-Property `releaseVersion`.
- Zielplattform und Dependencies: [`build.gradle.kts`](../build.gradle.kts).
- Plugin-Metadaten und Runtime-Abhängigkeiten: [`paper-plugin.yml`](../src/main/resources/paper-plugin.yml).
- JAR-Basisname: `Farmwelt`.

Ein Standardbuild erzeugt `build/libs/Farmwelt-2.0.0-SNAPSHOT.jar`. Für den Release-Build wird `-PreleaseVersion=2.0.0` gesetzt; das Ergebnis heißt `build/libs/Farmwelt-2.0.0.jar`. Die Version in `paper-plugin.yml` wird beim Verarbeiten der Ressourcen aus derselben Gradle-Version eingesetzt.

## Release-Gates für V2

Ein Release darf erst vorbereitet werden, wenn alle folgenden Gates erfüllt sind:

1. Arbeitsbaum und Ziel-Commit sind bekannt; keine unbeabsichtigten Änderungen sind enthalten.
2. `./gradlew clean test` läuft mit Java 25 erfolgreich.
3. `./gradlew build` läuft erfolgreich.
4. Der GitHub-Workflow [Build](../.github/workflows/build.yml) ist für den Release-Commit grün.
5. Der separate [Folia-/Worlds-Black-Box-Workflow](../.github/workflows/blackbox.yml) ist für den Release-Commit grün.
6. Die vollständige manuelle [V2-Abnahmematrix BB-01 bis BB-26](testing/v2-acceptance-checklist.md) ist ohne `NOT RUN`, `FAIL` oder `BLOCKED` abgeschlossen und ihre Evidenz ist gesichert.
7. Version, Changelog/Release Notes, Dokumentation und Runtime-Abhängigkeiten sind nochmals gegengeprüft.
8. Erst in Phase 6.4 wird `feature/v2` kontrolliert nach `main` übernommen.

Der automatisierte Smoke-Test startet einen isolierten Folia-Server, installiert Worlds und die Farmwelt-JAR des aktuellen Commits, erzeugt eine Testwelt und führt einen echten Force-Reset einschließlich State-, Regenerationsmarker-, Seed-, Log- und Shutdown-Prüfung aus. Er ist ein Release-Gate, ersetzt aber nicht die manuelle V2-Abnahme.

## Lokale Vorprüfung

Windows PowerShell:

```powershell
./gradlew.bat clean test
./gradlew.bat build
./gradlew.bat clean build "-PreleaseVersion=2.0.0"
```

Linux/macOS:

```bash
./gradlew clean test
./gradlew build
./gradlew clean build -PreleaseVersion=2.0.0
```

Danach prüfen:

- `build/libs/Farmwelt-2.0.0.jar` existiert.
- Die JAR enthält `paper-plugin.yml` mit Version `2.0.0`.
- Es existiert genau das erwartete Release-Artefakt.
- Optional die SHA-256-Prüfsumme für Release Notes oder Abnahmeprotokoll festhalten.

Der lokale Release-Build ersetzt nicht die beiden GitHub-Workflows und nicht die manuelle Abnahme.

## GitHub-Actions-Workflows

### Build

`.github/workflows/build.yml` läuft bei Push, Pull Request und manueller Ausführung. Der Workflow verwendet Java 25, validiert den Gradle Wrapper, führt `./gradlew build --no-daemon` aus und lädt Testreports bei Fehlern hoch. Der Gradle-`build`-Lifecycle enthält die Unit- und Integrationstests.

### Folia-/Worlds-Smoke-Test

`.github/workflows/blackbox.yml` läuft bei Pull Requests und manuell über `workflow_dispatch`. Er führt zuerst `clean test` und `build` aus, prüft Syntax und Log-Gate-Fixtures und startet danach das echte Harness unter [`testing/blackbox/`](../testing/blackbox/README.md). Diagnosen, Serverlogs, State vor/nach dem Reset und Worlds-Daten werden auch bei Fehlern als Artefakt hochgeladen.

### Release

`.github/workflows/release.yml` wird beim Veröffentlichen eines GitHub Releases oder manuell für einen vorhandenen Tag gestartet. Der Workflow:

1. leitet aus `vX.Y.Z` die Version `X.Y.Z` ab,
2. checkt exakt diesen Tag aus,
3. baut mit Java 25 und `./gradlew clean build -PreleaseVersion=X.Y.Z`,
4. erwartet `build/libs/Farmwelt-X.Y.Z.jar`,
5. lädt die JAR als gleichnamiges Asset in den GitHub Release hoch.

Die manuelle Workflow-Ausführung ist nur zum erneuten Bauen eines bereits vorhandenen Release-Tags vorgesehen; sie ersetzt keinen neuen Tag oder Release.

## Tagging und GitHub Release in Phase 6.4

Nach Merge und finaler Freigabe muss der Tag exakt auf dem abgenommenen `main`-Commit liegen. Für V2 ist vorgesehen:

```powershell
git checkout main
git pull --ff-only
git tag -a v2.0.0 -m "Release v2.0.0"
git push origin v2.0.0
gh release create v2.0.0 --verify-tag --title "Farmwelt v2.0.0" --notes-file RELEASE_NOTES.md
```

`RELEASE_NOTES.md` ist dabei nur ein Beispiel für eine vorab geprüfte lokale Notes-Datei; sie muss nicht dauerhaft im Repository liegen. Alternativ kann der GitHub Release über die Weboberfläche für den bereits gepushten Tag `v2.0.0` veröffentlicht werden.

Nach Veröffentlichung:

1. Workflow `Release` bis zum erfolgreichen Abschluss überwachen.
2. Asset `Farmwelt-2.0.0.jar` herunterladen.
3. Dateiname, eingebettete Plugin-Version und optional Prüfsumme prüfen.
4. Release Notes und dokumentierte Voraussetzungen kontrollieren.
5. Das Asset nicht durch einen Build eines anderen Commits ersetzen.

## Bestehendes Release-Asset neu bauen

Nur wenn Tag und GitHub Release bereits existieren und exakt derselbe Commit erneut gebaut werden soll:

```powershell
gh workflow run release.yml -f tag=v2.0.0
```

Der Workflow lädt das gleichnamige Asset mit `--clobber` erneut hoch. Vorher Ursache, Tag-Commit und Notwendigkeit dokumentieren; für inhaltliche Änderungen ist eine neue Version statt eines stillen Asset-Austauschs zu verwenden.

## Phase-6.3-Abgrenzung

Für die aktuelle Dokumentationsphase bleiben bewusst offen:

- vollständige manuelle BB-01-bis-BB-26-Abnahme,
- finale Release-Notes und Freigabeentscheidung,
- Merge von `feature/v2` nach `main`,
- Erstellen und Pushen von `v2.0.0`,
- Veröffentlichen des GitHub Releases,
- abschließende Prüfung des hochgeladenen Release-Assets.
