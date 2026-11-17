# HOLOPIX

Foto-Collage als Bildschirmschoner für Android-Tablets (ab Android 10). HOLOPIX hat nur eine Einstellungsseite, läuft im Hintergrund und zeigt nach der eingestellten Zeit ohne Bedienung eine Vollbild-Collage – über jeder App. Antippen beendet den Bildschirmschoner und man ist wieder in der vorherigen App.

## Funktionen
| Bereich | Umsetzung |
|---|---|
| Hintergrunddienst | Vordergrunddienst mit dezenter Benachrichtigung („HOLOPIX aktiv“, Knopf „Jetzt starten“). Ein unsichtbares 1-Pixel-Overlay erkennt jede Berührung auf dem Bildschirm; nach X Minuten ohne Berührung startet der Bildschirmschoner. Startet nach Neustart des Geräts und nach Updates automatisch. |
| Optionen | Leerlaufzeit, Display nicht ausschalten (solange HOLOPIX aktiv ist), nicht während Musik/Video, nur beim Laden. |
| Display-Zeitplan | Pro Wochentag mehrere An-Zeiträume. Zu Beginn weckt HOLOPIX das Display (exakter Wecker, startet den Bildschirmschoner direkt dank „Über anderen Apps einblenden“; immer über Vollbild-Benachrichtigung wie Wecker-Apps, zusätzlich direkter Start; Android 14 setzt die Berechtigung bei Updates zurück – HOLOPIX erinnert dann per Benachrichtigung) und startet den Bildschirmschoner. Außerhalb eines Zeitraums wird das Display nicht geweckt oder wach gehalten: von Hand eingeschaltet startet der Bildschirmschoner nach der Leerlaufzeit trotzdem, Android schaltet nach seinem Bildschirm-Zeitlimit ab. Tag nicht angehakt oder ohne Zeitraum = Display bleibt aus, ganztägig an = Zeitraum 00:00–00:00. Test-Knopf und Berechtigungs-Checkliste. |
| Besuchermodus | Konfetti-Symbol oben, Schalter oder Knopf in der Benachrichtigung: Display und Bildschirmschoner bleiben unbegrenzt an, Zeitplan pausiert, und der Kiosk-Modus ist automatisch mit an. Beenden mit 4× Tippen (+ PIN, falls vergeben) – danach gilt wieder der Zeitplan. |
| Kiosk-Modus | Schloss-Symbol oben oder Schalter: das Tablet zeigt nur den Bildschirmschoner – sobald das Display an ist, sofort; Antippen und Tasten beenden ihn nicht, erst 4× schnelles Tippen schaltet den Kiosk-Modus aus – optional erst nach Eingabe einer PIN (in den Einstellungen vergeben, ändern, entfernen; gespeichert nur als Hash). Display-Zeitplan, Wecken und Android-Zeitlimit bleiben unverändert. |
| Sperrbildschirm überspringen | Bildschirmschoner erscheint über dem Sperrbildschirm; eine Wischsperre ohne PIN wird beim Einschalten und Beenden automatisch weggeblendet. |
| Fotoquellen | **Ordner am Gerät** (Galerie/Kamera/Album-Ordner über die Android-Ordnerauswahl, mehrere möglich, ohne Speicher-Berechtigung) und/oder **SMB-Freigabe** (SMB2/3 über jcifs-ng, optional SMB1/Gast) mit Schritt-für-Schritt-Verbindungstest. Beide werden gemischt. |
| Smarte Collage | Passt sich an jeden Bildschirm an: aus Größe (dp) und Format folgt die Fotoanzahl (Handy 2, 8"-Tablet 5, 10"+ bis 7, in den Einstellungen begrenzbar) und die Mindestgröße einer Kachel. Bewertet werden alle Anordnungen (Guillotine-Bäume, bis 5 Fotos vollständig, darüber Zufallssuche mit Nachbesserung) mit den echten Kachelgrößen. **Zwei Durchgänge:** erst nach Format, dann – sobald die Fotos geladen und die Gesichter erkannt sind – noch einmal so, dass kein Gesicht abgeschnitten wird; Ausschnitt und Zoom werden auf die Gesichter zentriert. **Abwechslung:** Beschnitt bis ca. 7 % zählt nicht (der Zoom nimmt mehr weg), dafür wechseln Fotoanzahl und Aufbau, die letzten drei Anordnungen werden gemieden, gelegentlich füllt ein Einzelfoto den Bildschirm. **Auswahl:** Ordner werden mit der Wurzel ihrer Fotoanzahl gewichtet (ein riesiger Kamera-Ordner verdrängt kleine Alben nicht), derselbe Ordner kommt nicht zweimal hintereinander, Partner stammen aus demselben Ordner (keine Serienbilder). Passt nichts, wird das Foto ganz mit unscharfem Rand gezeigt. |
| Ordner ausschließen | Einzelne Ordner samt Unterordnern von der Anzeige ausnehmen – für Ordner am Gerät und auf der SMB-Freigabe: Ordnerbaum beider Quellen mit Fotoanzahl und Suche, Haken raus = nicht zeigen. Wirkt sofort; ausgeschlossene Ordner werden auch nicht mehr durchsucht. |
| Overlay | Uhr, Datum, Bildunterschrift (Ordner & Aufnahmedatum) einzeln schaltbar; Position (5 Ecken/Mitte oder geteilt) und Größe wählbar; Einbrennschutz. |
| Wetter-Widget (optional) | Open-Meteo (kostenlos, ohne Schlüssel) als Zeile im Bildschirmschoner – ausführlich (Zustand, Min/Max, Regen) oder kompakt (nur Symbol + aktuelle Temperatur), keine eigene Seite. |
| Updater | Prüft `github.com/b05at/HOLOPIX/releases/latest`, meldet neue Versionen per Benachrichtigung und installiert sie aus den Einstellungen. |
| Absturzprotokoll | Speichert den letzten Absturz (in den Einstellungen anzeigen/kopieren) und startet den Dienst neu. |

Farben farbenblind-freundlich (kein Rot, Hinweise in Orange).

## Einrichtung am Tablet
1. APK aus den Releases installieren, HOLOPIX öffnen.
2. Unter *Automatisch starten* die Berechtigungen erlauben: **Über anderen Apps einblenden** (Pflicht), Akku-Optimierung aus, Benachrichtigungen.
3. Fotoquelle wählen (Ordner und/oder SMB), „Jetzt starten“ zum Testen.
4. „HOLOPIX im Hintergrund aktiv“ einschalten.

## Build & Release
GitHub Actions (`.github/workflows/release.yml`) baut bei jedem Push auf `main` eine signierte APK und veröffentlicht sie als Release `v1.0.<Lauf>`. Signiert wird immer mit `keystore/holopix.jks` (oder eigenem Schlüssel über die Secrets `HOLOPIX_KEYSTORE_*`), damit In-App-Updates funktionieren.
