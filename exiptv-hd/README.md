# EXIPTV

IPTV-App für Android und Android TV. Kotlin, Jetpack Compose, media3, Room.

Neuentwicklung auf Basis der Befunde aus dem Audit von 3.10.16. Das Logo ist
übernommen, alles andere ist neu geschrieben.

---

## Der erste Build

Das Projekt baut über GitHub Actions, ein lokales Android SDK wird nicht gebraucht.

```bash
cd exiptv-hd
git init
git add .
git commit -m "EXIPTV: Neuentwicklung"
git branch -M main
git remote add origin https://github.com/<dein-konto>/exiptv-hd.git
git push -u origin main
```

Der Workflow `APK bauen` startet bei jedem Push und legt das Debug-APK als
Artefakt ab (`EXIPTV-debug`). Die Debug-Variante hat die Anwendungskennung
`de.exiptv.hd.debug` und lässt sich deshalb parallel zur alten App installieren.

### Signiertes Release

Vier Repository-Secrets anlegen:

| Secret | Inhalt |
|---|---|
| `KEYSTORE_BASE64` | Keystore als Base64 (`base64 -w0 exiptv.jks`, unter Windows `certutil -encode`) |
| `KEYSTORE_PASSWORD` | Passwort des Keystores |
| `KEY_ALIAS` | Alias des Schlüssels |
| `KEY_PASSWORD` | Passwort des Schlüssels |

Neuen Keystore erzeugen:

```bash
keytool -genkeypair -v -keystore exiptv.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias exiptv \
  -dname "CN=EXIPTV, OU=App, O=EXIPTV, L=Hannover, ST=Niedersachsen, C=DE"
```

Danach den Workflow von Hand starten (`Release-APK` anhaken) oder einen Tag
`v1.0.0` pushen. Fehlen die Secrets, wird das Release mit dem Debug-Schlüssel
signiert — die Pipeline scheitert nie an einer fehlenden Datei.

Lokal in Android Studio funktioniert stattdessen eine `keystore.properties` im
Projektwurzelverzeichnis (steht in `.gitignore`):

```properties
storeFile=exiptv.jks
storePassword=…
keyAlias=exiptv
keyPassword=…
```

---

## Was anders ist als in 3.10.16

Die Reihenfolge entspricht der Schwere der Befunde aus dem Audit.

**Der Katalog-Import blockiert die App nicht mehr.** Bisher lief der komplette
HTTP-Abruf innerhalb einer Room-Schreibtransaktion; die Datenbank war damit für
die gesamte Sync-Dauer gesperrt, und jeder Favoriten-Klick hing minutenlang.
Jetzt schreibt der Import in dieselben Tabellen, aber mit `stage = 1`, in Stapeln
von 1.500 Zeilen und ohne umschließende Transaktion. Erst am Ende schaltet
`promoteStage()` in einer Transaktion von Sekunden um. Nebeneffekt: Bricht ein
Sync ab, bleibt der bisherige Katalog vollständig erhalten, statt dass der Nutzer
vor einer leeren Liste steht. Siehe `data/db/Entities.kt` und `data/sync/CatalogSync.kt`.

**Listen blättern über einen Schlüssel statt über einen Zähler.**
`LIMIT ? OFFSET ?` muss die übersprungenen Zeilen jedes Mal durchlaufen; beim
500. Blatt einer Senderliste ist das eine halbe Tabelle pro Seite. `KeysetPager`
merkt sich stattdessen das letzte Paar aus Sortierwert und ID. Mit den passenden
zusammengesetzten Indizes kostet das tausendste Blatt so viel wie das erste.

**Ein HTTP-Client statt sechs.** Geteilter Verbindungspool, geteilter Dispatcher,
gzip aktiv (die Vorgängerversion setzte `Accept-Encoding: identity` und übertrug
Katalogdaten unkomprimiert), keep-alive statt `Connection: close`. Wiederholungen
laufen über `delay` statt über ein blockierendes `Thread.sleep`, und jede Antwort
wird geschlossen — auch die im Wiederholungsfall verworfene.

**Die Zeit ist ein beobachtbarer Zustand.** Fortschrittsbalken lasen bisher
`System.currentTimeMillis()` direkt im Zeichenpfad und standen deshalb still.
Jetzt tickt die Uhr an einer Stelle und wird über `LocalNowMillis` verteilt.

**Kein Oberflächenzustand mehr im anwendungsweiten Container.** Lang laufende
Arbeit liegt im `appScope` von `AppGraph` und fasst ausschließlich
Anwendungsobjekte an. Bildschirmzustand gehört in ViewModels, Navigation in einen
`rememberSaveable`-Stapel — nach einem Prozesstod landet der Nutzer dort, wo er
war, statt auf dem Startbildschirm.

**Geräteerkennung im Hintergrund.** Die Codec-Abfrage dauert auf günstigen Boxen
mehrere hundert Millisekunden und lief bisher im Hauptthread in
`Application.onCreate` — direkt vor dem ersten Bild.

**Erwachseneninhalte werden beim Import markiert**, nicht bei jeder Abfrage mit
zwei Dutzend `GLOB`-Ausdrücken auf `LOWER(spalte)` gefiltert. Die Listen filtern
jetzt über eine indizierte Boolean-Spalte.

**Ausgeblendete Kategorien stehen in einer Tabelle**, nicht als SQL-Literale im
Abfragetext. Damit entfällt sowohl die Statement-Cache-Invalidierung bei jeder
Änderung als auch die Längengrenze, die bei vielen versteckten Kategorien
irgendwann gerissen wäre.

**Zugangsdaten tauchen in keiner Protokollzeile auf.** `Diagnostics.redact()`
ersetzt sie in Adressen und in Ausnahmemeldungen, bevor etwas geschrieben wird.
Beim Löschen eines Anbieters verschwinden auch dessen Inhalte — bisher blieben
Verlauf und Favoriten mit eingebetteten Zugangsdaten zurück.

**Wiedergabefehler werden eingeordnet, nicht gezählt.** Ein abgelehnter Zugang
wird nicht achtmal wiederholt, ein Decoder-Fehler schaltet auf Software-Dekodierung
um, statt einen weiteren vergeblichen Versuch zu starten. Dazu ein Wachhund auf
Frame-Ebene für den Fall, den ExoPlayer nicht meldet: Server, die die Verbindung
offen halten, ohne noch Daten zu liefern.

**Der `catchupDays`-Überlauf ist weg** (`Int`-Multiplikation mit `86400000`, die
ab 25 Tagen ins Negative kippte), ebenso die prozessweit geteilten
`SimpleDateFormat`-Instanzen.

---

## Aufbau

```
de.exiptv.hd
├─ AppGraph.kt          Objektgraph, von Hand verdrahtet — was wie lange lebt, steht hier
├─ ExIptvApp.kt         Application, bewusst schlank
├─ MainActivity.kt      einzige Activity, hält keinen Zustand
├─ DeviceProfile.kt     Fernseher? Wenig Speicher? Welche Codecs?
├─ core/                Textnormalisierung, Zeitformate, Diagnose
├─ data/
│  ├─ db/               Room: Entitäten, DAOs, Datenbank
│  ├─ net/              ein OkHttp-Client, Streaming-JSON
│  ├─ xtream/           Xtream-Panel-API
│  ├─ m3u/              M3U-Parser, zeilenweise
│  ├─ epg/              XMLTV-Parser, ereignisbasiert
│  ├─ sync/             Katalog- und EPG-Import
│  ├─ repo/             einziger Zugang der Oberfläche zu den Daten
│  └─ settings/         DataStore
├─ player/              media3-Engine, Fehlerklassifikation, Zustand
└─ ui/
   ├─ theme/            Farben aus dem Logo, Maße nach Gerätebauart
   ├─ Components.kt     Fokus-Modifier, Bilder, Bausteine
   ├─ Navigation.kt     eigener Navigationsstapel, speicherbar
   ├─ ViewModels.kt     je Bildschirm eines
   └─ screens/          Start, Live, Filme/Serien, Suche, Programm, Player, Einstellungen
```

Kein DI-Framework, keine Navigationsbibliothek, kein AppCompat. Für eine App
dieser Größe ist der Objektgraph von Hand die schlankere und besser
nachvollziehbare Lösung — und jede eingesparte Annotationsverarbeitung ist eine
Fehlerquelle weniger im Build.

---

## Stand

Enthalten: Xtream und M3U, Live-TV mit EPG, Filme, Serien mit Staffeln und
Folgen, Suche, Favoriten, Weiterschauen, Programmzeitschrift, Player mit
Spurauswahl, Einstellungen, Anbieterverwaltung mit Verbindungsprüfung, Diagnose.

Noch nicht enthalten: Aufnahmen mit Zeitplanung und Catchup/Replay. Beides war
in 3.10.16 vorhanden und kommt in die nächste Runde, sobald der Kern auf deinem
Gerät läuft. Die Datenbank ist darauf vorbereitet (`catchupDays`, `catchupSource`
an `ChannelEntity`), und `XtreamClient.catchupUrl()` steht bereits.

## Toolchain

Gradle 8.11.1 · AGP 8.10.1 · Kotlin 2.2.0 · Java 17 · compileSdk 36 ·
minSdk 23 · targetSdk 35 · Compose BOM 2024.11.00 · media3 1.6.1 · Room 2.7.2

Die vier Werte der Toolchain hängen voneinander ab und stehen in
`gradle/libs.versions.toml`. Beim Anheben immer im Viererpack prüfen.
