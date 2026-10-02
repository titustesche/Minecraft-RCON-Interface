# Simplycraft – Minecraft-Serverkonsole

Web-Konsole für Minecraft-Server im Simplyfile/Synthia-Ökosystem. Spring Boot startet die
Server-Jars als eigene Prozesse, streamt die Logs live ins Frontend, verwaltet Mods (inkl.
Modrinth) und sichert Backups direkt nach [Simplyfile](https://github.com/titustesche/simplyfile).

Gleicher Stack wie Simplyfile: Spring Boot 4.1, Java 21, Maven, Vanilla-JS-Frontend unter
`src/main/resources/static` mit denselben Design-Tokens (Akzentfarbe, Fonts, Hell/Dunkel).

## Funktionen

- **Mehrere Server**, jeder in seinem eigenen Ordner: `servers/<id>/server.jar`, `mods/`, `world/`, …
- **Serverdatei** per Versionsauswahl (Vanilla von Mojang, Fabric), per Link oder per Upload
  – Downloads werden geprüft (SHA-1 bei Mojang, Jar-Signatur `PK`) und ersetzen die alte Jar erst, wenn sie vollständig sind
- **Start konfigurieren**: Xms/Xmx, Port, Java-Pfad pro Server, optional eigener Startbefehl mit
  Platzhaltern `{java} {xms} {xmx} {jar}` (wird ohne Shell ausgeführt)
- **Konsole**: Jeder Server läuft in einem eigenen Prozess mit eigenem Konsolen-Thread; die Ausgabe
  geht per Server-Sent-Events ans Frontend (letzte 2000 Zeilen im Speicher, keine Datenbank –
  Minecraft schreibt selbst `logs/latest.log`). Befehle gehen an stdin, Befehlsverlauf mit ↑/↓
- **Spielerliste** aus dem Log (join/leave, `list`), Kicken und Namen einfügen per Klick
- **Status**: Start/Stopp/Neustart, Stopp mit Timeout und anschließendem Kill, Absturzerkennung,
  RAM-Anzeige; beim Beenden von Simplycraft werden alle Server sauber gestoppt
- **Mod-Browser**: `.jar` hochladen (Drag & Drop), aktivieren/deaktivieren (`.jar.disabled`), löschen
- **Modrinth**: Suche gefiltert nach Loader und Minecraft-Version des Servers, Installation der
  neuesten passenden Release-Version inkl. benötigter Abhängigkeiten (z. B. Fabric API), Updates ersetzen die alte Datei
- **Backups** als `.zip` oder `.tar.gz` nach `backups/<id>/`; läuft der Server, wird vorher
  `save-off` + `save-all flush` gesendet und danach `save-on`. Direkt oder nachträglich nach
  Simplyfile hochladbar, der Link landet in der Backup-Liste

## Starten

```bash
just run            # oder: mvn -DskipTests package && java -jar target/simplycraft-*.jar
```

Dann http://localhost:8080 öffnen. Mit Docker (Daten in `./data`, Konsole auf Port 9092):

```bash
cp .env.example .env   # Token setzen!
just start
```

## Konfiguration

Alle Werte stehen in `application.properties` und lassen sich per Umgebungsvariable überschreiben.

| Property | Env | Standard | |
|---|---|---|---|
| `simplycraft.servers-path` | `SIMPLYCRAFT_SERVERS_PATH` | `./servers` | Serverordner |
| `simplycraft.backups-path` | `SIMPLYCRAFT_BACKUPS_PATH` | `./backups` | Backups |
| `simplycraft.java-path` | `SIMPLYCRAFT_JAVA_PATH` | `java` | Java für die Server |
| `simplycraft.default-xms` / `-xmx` | | `1G` / `4G` | |
| `simplycraft.stop-timeout` | | `60s` | danach wird der Prozess beendet |
| `simplycraft.api-token` | `SIMPLYCRAFT_API_TOKEN` | leer | **setzen, sobald die Konsole erreichbar ist** |
| `simplycraft.custom-command-enabled` | | `true` | eigene Startbefehle erlauben |
| `simplycraft.cors-allowed-origins` | `SIMPLYCRAFT_CORS_ALLOWED_ORIGINS` | leer | andere Frontends (z. B. synthia-frontend) |
| `simplycraft.simplyfile.url` | `SIMPLYCRAFT_SIMPLYFILE_URL` | leer | Simplyfile-Instanz für Backups |
| `simplycraft.simplyfile.token` | `SIMPLYCRAFT_SIMPLYFILE_TOKEN` | leer | wird als Bearer-Token mitgeschickt (Simplyfile-Roadmap Phase 2) |

> **Sicherheit:** Wer die API erreicht, kann beliebige Jars ausführen. Ohne `simplycraft.api-token`
> ist die Konsole nur für `localhost` bzw. ein privates Netz gedacht. Mit Token fragt das Frontend
> einmal danach und speichert ihn im Browser.

## Einbindung ins Ökosystem

- **Simplyfile**: Backups werden über die bestehende API (`POST /file/upload`, Feld `file`)
  hochgeladen, die zurückgegebene ID wird als Link `…/file/<id>` angezeigt. Die CI lädt die gebaute
  Jar – wie bei Simplyfile – nach Simplyfile hoch und meldet sich per Discord-Webhook.
- **Synthia / andere Frontends**: Die REST-API liegt unter `/api`, der Health-Check unter `/status`
  (wie bei Simplyfile). Origin in `SIMPLYCRAFT_CORS_ALLOWED_ORIGINS` eintragen; das Frontend kann mit
  `window.SIMPLYCRAFT_API_URL = "https://…"` vor `js/app.js` gegen ein anderes Backend laufen.
- Die Akzentfarbe nutzt denselben `localStorage`-Schlüssel (`accent-color`) wie Simplyfile.

## API (Auszug)

| Methode | Pfad | |
|---|---|---|
| GET/POST | `/api/servers` | Server auflisten / anlegen (`name`, `port`, `xms`, `xmx`) |
| GET/PATCH/DELETE | `/api/servers/{id}` | Details, Einstellungen ändern, löschen |
| GET | `/api/versions?type=VANILLA\|FABRIC&snapshots=` | verfügbare Versionen |
| POST | `/api/servers/{id}/jar/version` · `/jar/url` · `/jar/upload` | Serverdatei installieren |
| POST | `/api/servers/{id}/start` · `/stop` · `/restart` · `/kill` | Prozess steuern |
| POST | `/api/servers/{id}/command` | Befehl senden (`command`) |
| GET | `/api/servers/{id}/console` | SSE: `status`, `backlog`, `line` |
| GET/POST | `/api/servers/{id}/mods` | Mods auflisten / hochladen (`files`) |
| PATCH/DELETE | `/api/servers/{id}/mods/{datei}` | aktivieren/deaktivieren, löschen |
| GET | `/api/servers/{id}/modrinth/search?query=` | Modrinth-Suche |
| POST | `/api/servers/{id}/modrinth/install` | Mod installieren (`projectId`, optional `versionId`) |
| GET/POST | `/api/servers/{id}/backups` | Backups / neues Backup (`format`, `uploadToSimplyfile`) |
| GET/DELETE | `/api/servers/{id}/backups/{name}` | herunterladen, löschen |
| POST | `/api/servers/{id}/backups/{name}/simplyfile` | nach Simplyfile hochladen |

## Tests

`mvn test` – die Integrationstests starten einen kleinen Fake-Minecraft-Server
(`src/test/resources/fake-server/FakeServer.java`) als echten Prozess und ersetzen Mojang, Fabric,
Modrinth und Simplyfile durch einen lokalen HTTP-Server.

## CI/CD

Dieselben Workflows wie in Simplyfile:

- `ci-cd.yml` (jeder Push): Build, Tests, Test-Zusammenfassung, Jar als Workflow-Artefakt, Upload
  der Jar nach Simplyfile (`vars.SIMPLYFILE_URL`, Standard `https://simplyfile.tesche.me`) und
  Discord-Meldung über das Secret `WEBHOOK_URL` (fehlt es, wird nur gewarnt)
- `security_scan.yml` (Pull Requests auf `main`): Gitleaks, OSV-Scanner und Trivy
