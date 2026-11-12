# Fox Ticket

Überwacht Eventim-Künstlerseiten und benachrichtigt bei neuen Terminen/Tickets. Android-App (Capacitor 7 + eigener Java-Code in `native/`, Oberfläche in `www/index.html`) und Windows-Version (`windows/`, Python + Playwright mit Microsoft Edge). Details in `README.md`.

## Agent-Skills

### Issue-Tracker

GitHub Issues in `Djlover007/Fox-Ticket`, per `gh`-CLI. Siehe `docs/agents/issue-tracker.md`.

### Triage-Labels

Die fünf Standard-Rollen mit gleichnamigen Labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). Siehe `docs/agents/triage-labels.md`.

### Domänen-Doku

Ein Kontext: ein `GLOSSARY.md` und `docs/adr/` im Hauptverzeichnis für Android und Windows gemeinsam. Siehe `docs/agents/domain.md`.

### Build & Tests

- Android: Der Ordner `android/` wird nicht eingecheckt, sondern in CI erzeugt (`npm install`, `npx cap add android`, `npx cap sync android`, Einbau von `native/` per `.github/workflows/build-apk.yml`, dann `./gradlew assembleDebug`). Lokal denselben Ablauf wie im Workflow nutzen.
- Windows: `pip install -r windows/requirements.txt`; Rauchtest wie in CI: `cd windows && python -c "import engine, json; print(engine.analyze_event('Kategorie 1 - Stehplatz\nNormalpreis\n€ 88,50\nzurzeit nicht verfügbar\n', []))"`.
- Es gibt noch keine automatischen Tests.
- Jeder Push auf `main` baut und veröffentlicht ein Release (APK bzw. Windows-EXE). Arbeit deshalb auf Branches und per PR.
