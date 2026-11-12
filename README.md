# Fox Ticket 🦊🎟️

Überwacht Eventim-Künstlerseiten (Standard: [Fall Out Boy](https://www.eventim.de/artist/fall-out-boy/)) und schickt eine Benachrichtigung, sobald neue Konzerttermine auftauchen.

- Prüft automatisch ca. alle 30 Minuten im Hintergrund (Android WorkManager, auch wenn die App zu ist)
- Weitere Künstler: einfach einen `eventim.de/artist/…`-Link in der App einfügen
- Zeigt Datum, Halle und Ticketstatus (wenn Eventim die Daten liefert) und öffnet den Termin direkt auf Eventim
- Kauft nichts automatisch – nur Benachrichtigungen

Tipp: In den Android-Einstellungen für Fox Ticket „Akku: Nicht eingeschränkt“ wählen, sonst prüft Android seltener.

## APK und Updates
Bei jedem Upload auf `main` baut GitHub Actions die App und veröffentlicht sie als **Release**.

- Neueste APK: `https://github.com/Djlover007/Fox-Ticket/releases/latest/download/Fox-Ticket.apk`
- Die App zeigt ein Banner, wenn eine neuere Version da ist (Repository muss dafür öffentlich sein).
- Alle Versionen sind mit `fox-ticket.keystore` signiert, Updates lassen sich direkt drüber installieren.

## Technik
Capacitor 7 + eigener Android-Code in `native/` (Prüf-Logik, WorkManager-Hintergrundjob, Benachrichtigungen, Links in Kiwi Browser öffnen). Die Oberfläche steckt in `www/index.html`.

## Windows-Version
Ordner `windows/`: Python-Programm (tkinter + Playwright), steuert den installierten **Microsoft Edge**.

- Neueste EXE: unter [Releases](https://github.com/Djlover007/Fox-Ticket/releases) den neuesten Eintrag „Fox Ticket Windows …“ → `FoxTicket.exe`
- Gleiche Funktionen wie die App: Künstler/Termine überwachen, Kategorien wählen, Auto-Warenkorb, Alarm, Eventim-Anmeldung, Prüfintervall ab 2 Minuten, Autostart, Selbst-Update
- Daten und Edge-Profil liegen in `%APPDATA%\FoxTicket`
