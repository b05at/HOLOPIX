# HOLOPIX

Foto-Collage als Bildschirmschoner für Android-Tablets (ab Android 10). HOLOPIX hat nur eine Einstellungsseite, läuft im Hintergrund und zeigt nach der eingestellten Zeit ohne Bedienung eine Vollbild-Collage – über jeder App. Antippen beendet den Bildschirmschoner und man ist wieder in der vorherigen App.

## Funktionen
| Bereich | Umsetzung |
|---|---|
| Hintergrunddienst | Vordergrunddienst mit dezenter Benachrichtigung („HOLOPIX aktiv“, Knopf „Jetzt starten“). Ein unsichtbares 1-Pixel-Overlay erkennt jede Berührung auf dem Bildschirm; nach X Minuten ohne Berührung startet der Bildschirmschoner. Startet nach Neustart des Geräts und nach Updates automatisch. |
| Optionen | Leerlaufzeit, Display nicht ausschalten (solange HOLOPIX aktiv ist), nicht während Musik/Video, nur beim Laden. |
| Display-Zeitplan | Pro Wochentag mehrere An-Zeiträume. Zu Beginn weckt HOLOPIX das Display (exakter Wecker + Vollbild-Benachrichtigung wie Wecker-Apps) und startet den Bildschirmschoner. Außerhalb eines Zeitraums wird das Display nicht geweckt oder wach gehalten: von Hand eingeschaltet startet der Bildschirmschoner nach der Leerlaufzeit trotzdem, Android schaltet nach seinem Bildschirm-Zeitlimit ab. Tag nicht angehakt oder ohne Zeitraum = Display bleibt aus, ganztägig an = Zeitraum 00:00–00:00. Test-Knopf und Berechtigungs-Checkliste. |
| Besuchermodus | Konfetti-Symbol oben, Schalter oder Knopf in der Benachrichtigung: Display und Bildschirmschoner bleiben unbegrenzt an, Zeitplan pausiert – bis er wieder ausgeschaltet wird. |
| Kiosk-Modus | Schloss-Symbol oben oder Schalter: das Tablet zeigt nur den Bildschirmschoner – sobald das Display an ist, sofort; Antippen und Tasten beenden ihn nicht, erst 4× schnelles Tippen schaltet den Kiosk-Modus aus. Display-Zeitplan, Wecken und Android-Zeitlimit bleiben unverändert. |
| Sperrbildschirm überspringen | Bildschirmschoner erscheint über dem Sperrbildschirm; eine Wischsperre ohne PIN wird beim Einschalten und Beenden automatisch weggeblendet. |
| Fotoquellen | **Ordner am Gerät** (Galerie/Kamera/Album-Ordner über die Android-Ordnerauswahl, mehrere möglich, ohne Speicher-Berechtigung) und/oder **SMB-Freigabe** (SMB2/3 über jcifs-ng, optional SMB1/Gast) mit Schritt-für-Schritt-Verbindungstest. Beide werden gemischt. |
| Smarte Collage | Passt sich an jeden Bildschirm an: aus Größe (dp) und Format folgt die Fotoanzahl (Handy 2, 8"-Tablet 5, 10"+ bis 7) und die Mindestgröße einer Kachel. Bewertet werden alle Anordnungen (Guillotine-Bäume, bis 5 Fotos vollständig, darüber per Zufallssuche) mit den echten Kachelgrößen: Beschnitt je Foto, keine Briefmarken, ausgewogene Größen, wechselnde Fotoanzahl, nie zweimal hintereinander dieselbe Anordnung. Fotos werden in der Auflösung ihrer Kachel geladen; beim Drehen wird sofort neu angeordnet. Serienbilder nicht nebeneinander, Gesichter bleiben im Bild, Ken-Burns-Zoom. Passt nichts, wird das Foto ganz mit unscharfem Hintergrund gezeigt. |
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
