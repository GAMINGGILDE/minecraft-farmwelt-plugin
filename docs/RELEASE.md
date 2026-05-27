# Release erstellen

Dieses Projekt baut die Plugin-JAR automatisch, sobald ein GitHub Release veröffentlicht wird. Die Action `.github/workflows/release.yml` checkt den Release-Tag aus, baut mit Java 25 und lädt die fertige JAR als Asset in denselben GitHub Release hoch.

## Versionierung

- Release-Tags sollen im Format `vX.Y.Z` angelegt werden, zum Beispiel `v0.1.0`.
- Die Plugin-Version in `paper-plugin.yml` und der JAR-Dateiname werden aus dem Tag abgeleitet.
- Aus `v0.1.0` wird intern `0.1.0`.
- Die Release-Datei heißt dann `Farmwelt-0.1.0.jar`.

## Vor dem Release testen

Windows PowerShell:

```powershell
git checkout main
git pull --ff-only
.\gradlew.bat clean build "-PreleaseVersion=0.1.0"
```

Linux/macOS:

```bash
git checkout main
git pull --ff-only
./gradlew clean build -PreleaseVersion=0.1.0
```

Die lokale Test-JAR liegt danach unter `build/libs/Farmwelt-0.1.0.jar`.

## Release per GitHub CLI

Beispiel für Version `0.1.0`:

```powershell
git checkout main
git pull --ff-only
.\gradlew.bat clean build "-PreleaseVersion=0.1.0"
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
gh release create v0.1.0 --title "Farmwelt v0.1.0" --notes "Release v0.1.0"
```

Nach `gh release create` startet GitHub Actions automatisch den Workflow `Release`. Sobald der Workflow fertig ist, enthält der GitHub Release die Datei `Farmwelt-0.1.0.jar`.

## Release über die GitHub-Weboberfläche

1. Lokal sicherstellen, dass `main` aktuell ist.
2. Lokal mit `.\gradlew.bat clean build "-PreleaseVersion=X.Y.Z"` testen.
3. Tag erstellen: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`.
4. Tag pushen: `git push origin vX.Y.Z`.
5. Auf GitHub im Repository `Releases` öffnen.
6. `Draft a new release` auswählen.
7. Als Tag `vX.Y.Z` auswählen.
8. Titel und Release Notes eintragen.
9. `Publish release` klicken.
10. Warten, bis der Workflow `Release` abgeschlossen ist.

## Workflow überwachen

Mit GitHub CLI:

```powershell
$runId = gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId'
gh run watch $runId
```

Alternativ auf GitHub im Repository unter `Actions` den Workflow `Release` öffnen.

## Release-Asset erneut bauen

Falls die JAR für einen bestehenden Release erneut gebaut und hochgeladen werden soll:

```powershell
gh workflow run release.yml -f tag=v0.1.0
```

Der Workflow überschreibt das bestehende Release-Asset mit demselben Namen.
