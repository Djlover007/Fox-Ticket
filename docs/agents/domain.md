# Domänen-Doku

Wie die Engineering-Skills die Domänen-Dokumentation dieses Repos beim Erkunden des Codes lesen sollen.

## Vor dem Erkunden lesen

- **`GLOSSARY.md`** im Hauptverzeichnis, oder
- **`GLOSSARY-MAP.md`** im Hauptverzeichnis, falls vorhanden: Sie verweist auf eine `GLOSSARY.md` pro Kontext. Lies jede, die zum Thema passt.
- **`docs/adr/`**: Lies ADRs, die den Bereich betreffen, in dem du gleich arbeitest. In Repos mit mehreren Kontexten prüfe auch `<modul>/docs/adr/` auf kontextspezifische Entscheidungen.

Gibt es eine dieser Dateien nicht, **mach still weiter**. Weise nicht auf ihr Fehlen hin; schlag nicht vor, sie vorab anzulegen. Der Skill `/domain-modeling` (erreicht über `/grill-with-docs` und `/improve-codebase-architecture`) legt sie erst an, wenn Begriffe oder Entscheidungen tatsächlich geklärt werden.

## Dateistruktur

Fox Ticket hat **einen Kontext**: Android-App (`native/`, `www/`) und Windows-Version (`windows/`) teilen dieselbe Fachsprache (Künstler, Termin, Kategorie, Warenkorb, Prüfintervall …), also gibt es ein gemeinsames Glossar:

```
/
├── GLOSSARY.md
├── docs/adr/
│   └── 0001-<slug>.md
├── native/      ← Android (Java, Capacitor-Plugin)
├── www/         ← Oberfläche der App
└── windows/     ← Windows-Version (Python + Playwright)
```

Beide Dateien entstehen erst, wenn der erste Begriff bzw. die erste Entscheidung geklärt ist.

## Das Vokabular des Glossars verwenden

Benennt deine Ausgabe ein Domänenkonzept (in einem Issue-Titel, einem Refactoring-Vorschlag, einer Hypothese, einem Testnamen), verwende den Begriff so, wie `GLOSSARY.md` ihn definiert. Weiche nicht auf Synonyme aus, die das Glossar ausdrücklich vermeidet.

Steht das Konzept, das du brauchst, noch nicht im Glossar, ist das ein Signal: Entweder erfindest du Sprache, die das Projekt nicht nutzt (überdenken), oder es gibt eine echte Lücke (für `/domain-modeling` notieren).

## Konflikte mit ADRs benennen

Widerspricht deine Ausgabe einem vorhandenen ADR, sprich es ausdrücklich an, statt es still zu übergehen:

> _Widerspricht ADR-0003 (kein automatischer Kauf), lohnt sich aber neu zu öffnen, weil …_
