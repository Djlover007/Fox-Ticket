# Issue-Tracker: GitHub

Issues und Specs dieses Repos liegen als GitHub-Issues in `Djlover007/Fox-Ticket`. Nutze für alle Vorgänge die `gh`-CLI. Schreibe Titel und Texte auf Deutsch.

Issues gehören immer hierher, in das Repo mit dem Code – nicht in ein Download- oder Release-Repo. Android- und Windows-Version teilen sich den Tracker; nenne im Titel die Plattform, wenn ein Issue nur eine betrifft (z. B. „Windows: …“).

## Konventionen

- **Issue anlegen**: `gh issue create --title "..." --body "..."`. Für mehrzeilige Texte ein Heredoc verwenden.
- **Issue lesen**: `gh issue view <nummer> --json number,title,body,labels,comments`.
- **Issues auflisten**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` mit passenden `--label`- und `--state`-Filtern.
- **Issue zum Sub-Issue machen**: `gh issue create --parent <eltern> ...` oder nachträglich `gh issue edit <eltern> --add-sub-issue <kind>` (`gh` 2.94+). Älteres `gh`: `gh api --method POST repos/<owner>/<repo>/issues/<eltern>/sub_issues -F sub_issue_id=<kind-db-id>` (Datenbank-ID, wie unter **Blockieren**). Ohne Sub-Issues `Teil von #<eltern>` an den Anfang des Kind-Textes setzen.
- **Blockieren**: GitHubs **native Issue-Abhängigkeiten**, die maßgebliche, in der Oberfläche sichtbare Darstellung. Kante hinzufügen mit `gh issue create --blocked-by <n>,<n> ...` oder `gh issue edit <kind> --add-blocked-by <blocker>` (`gh` 2.94+). Älteres `gh`: `gh api --method POST repos/<owner>/<repo>/issues/<kind>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, wobei `<blocker-db-id>` die numerische **Datenbank-ID** des Blockers ist (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _nicht_ die `#nummer` oder `node_id`). Wo Abhängigkeiten nicht verfügbar sind, auf eine Zeile `Blockiert durch: #<n>, #<n>` am Anfang des Kind-Textes ausweichen. Ein Ticket ist frei, wenn jeder Blocker geschlossen ist.
- **Offene Blocker zählen**: `gh api repos/<owner>/<repo>/issues/<n> --jq .issue_dependencies_summary.blocked_by` (nur offene Blocker; 0 heißt startbar). `gh issue view <n> --json blockedBy` listet auch geschlossene Blocker, also filtern: `--jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`.
- **Kommentieren**: `gh issue comment <nummer> --body "..."`
- **Labels setzen / entfernen**: `gh issue edit <nummer> --add-label "..."` / `--remove-label "..."`
- **Schließen**: Begründung mit `gh issue comment <nummer> --body "..."` posten, dann `gh issue close <nummer>`. Bei einem schon geschlossenen Issue verwirft `gh issue close --comment` den Kommentar und endet trotzdem mit 0.

Leite das Repo aus `git remote -v` ab; `gh` macht das automatisch, wenn es in einem Klon läuft.

Lege Issues nur im eigenen Repo des Nutzers an (unter `Djlover007`). Lege nie Issues in `mattpocock/skills` an (dem Repo, aus dem diese Skills stammen). In einem Fork kann `gh` standardmäßig auf das Original zeigen; prüfe deshalb vor dem ersten `gh issue create` einer Sitzung das Ziel mit `gh repo view --json nameWithOwner` und bestätige es mit dem Nutzer, wenn es nicht sein Repo ist.

## Pull Requests als Triage-Kanal

**PRs als Anfrage-Kanal: nein.** _(Auf `ja` setzen, wenn dieses Repo externe PRs als Feature-Wünsche behandelt; `/triage` liest diesen Schalter.)_

Bei `ja` laufen PRs durch dieselben Labels und Zustände wie Issues, mit den `gh pr`-Entsprechungen:

- **PR lesen**: `gh pr view <nummer>`, dann `gh pr view <nummer> --comments` (ohne TTY gibt `--comments` nur die Kommentare aus, nicht den Text) und `gh pr diff <nummer>` für den Diff.
- **Externe PRs für die Triage auflisten**: `gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`.
- **Kommentieren / Labels / Schließen**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub teilt sich einen Nummernraum zwischen Issues und PRs, ein bloßes `#42` kann also beides sein: mit `gh pr view 42` auflösen und auf `gh issue view 42` ausweichen.

## Wenn ein Skill sagt „im Issue-Tracker veröffentlichen“

Lege ein GitHub-Issue an.

## Wenn ein Skill sagt „das passende Ticket holen“

Lies es wie oben unter **Issue lesen**.

## Wayfinder-Vorgänge

Genutzt von `/wayfinder`. Die **Karte** ist ein einzelnes Issue mit **Kind**-Issues als Tickets.

- **Karte**: ein einzelnes Issue mit Label `wayfinder:map`, das den Text Notizen / Bisherige Entscheidungen / Nebel enthält. `gh issue create --label wayfinder:map`.
- **Kind-Ticket**: ein Issue, das als GitHub-Sub-Issue mit der Karte verknüpft ist (siehe **Issue zum Sub-Issue machen**). Wo Sub-Issues nicht aktiv sind, das Kind in eine Aufgabenliste im Kartentext aufnehmen und `Teil von #<karte>` an den Anfang des Kind-Textes setzen. Labels: `wayfinder:<typ>` (`research`/`prototype`/`grilling`/`task`). Sobald es beansprucht ist, wird das Ticket dem steuernden Entwickler zugewiesen.
- **Blockieren**: siehe **Blockieren** unter Konventionen.
- **Abfrage der Front**: die offenen Kinder der Karte auflisten (`gh issue list --state open`, beschränkt auf die Sub-Issues / Aufgabenliste der Karte), alle mit offenem Blocker (siehe **Offene Blocker zählen**, oder ein offenes Issue in der Zeile `Blockiert durch`) oder einer Zuweisung verwerfen; das erste in Kartenreihenfolge gewinnt.
- **Beanspruchen**: `gh issue edit <n> --add-assignee @me`, der erste Schreibvorgang der Sitzung.
- **Auflösen**: `gh issue comment <n> --body "<antwort>"`, dann `gh issue close <n>`, dann einen Kontextverweis (Kern + Link) an „Bisherige Entscheidungen“ der Karte anhängen.
