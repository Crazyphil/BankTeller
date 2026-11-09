# Project knowledge base: "Usability und UX für Dummies" (Elske Ludewig)

This repo ships a searchable knowledge base extracted from the German UX book
*Usability und UX für Dummies* (Elske Ludewig, Wiley-VCH). Use it to ground your
UI/UX recommendations in the book's methods instead of generic heuristics alone.

## How to search it

Use the `semble` MCP tools (available to you) with these **exact parameters**:

- `mcp__semble__search`:
  - `repo`: `aux-docs/usability-ux-fuer-dummies/md` (relative to the repo root)
  - `content`: `"docs"` — **mandatory**; without it the index finds nothing
  - `query`: natural language, German works best (the book is in German)
  - `top_k`: 4–6; `max_snippet_lines`: 6–10 to confirm relevance, then re-run with
    `max_snippet_lines: null` for the full chunk when a hit looks on-topic
- `mcp__semble__find_related`: same `repo` + `content: "docs"`, with `file_path`
  and `line` from a previous hit, to pull adjacent methods from other chapters.

Never grep this directory for content — always go through semble. The index is
cached; repeat searches are cheap.

## Chapter map (files under `aux-docs/usability-ux-fuer-dummies/md/`)

| File | Topic |
|------|-------|
| c01 | Grundbegriffe, Usability vs. UX, Kriterien |
| c02 | Websites und Apps bewerten (Checklisten, Heuristiken) |
| c03 | Optimierungsmethoden (Cognitive Walkthrough etc.) |
| c04 | Informationsverarbeitung und Entscheidungsprozesse des Nutzers |
| c05 | Nutzerverhalten beeinflussen (Persuasion, Conversion) |
| c06 | Nutzerverhalten messen und erheben (Analytics, Tracking) |
| c07 | User Centered Design Prozess, ISO 9241 |
| c08 | Nutzer kennenlernen und verstehen (Personas, Interviews) |
| c09 | Benutzerfreundliche Lösungen entwickeln (Usability-Tests, Remote-Tests) |
| c10 | Kontinuierliche Erfolgskontrolle (A/B-Tests, Metriken) |
| c11 | Website-Aufbau, Navigation, Seitenstruktur |
| c12 | Lesbarkeit und Textgestaltung |
| c13 | Links und Buttons gestalten |
| c14 | Formulare gestalten |
| c15 | Suchen und Finden (Suchfunktion, Findability) |
| c16 | Responsives Design |
| c17 | Touch-Devices und mobile Besonderheiten |
| c18 | Top-Ten-Link-Tipps |
| f01 | Schummelseite (Kompaktübersicht aller Regeln — good first stop) |
| b01 | Stichwortverzeichnis (Index) |

For any UI work (forms, navigation, responsive behavior, text/contrast, search),
check the relevant chapter via semble first and cite the chapter file and line
range in your recommendation.

## Figures

The book's 162 figures are extracted to `aux-docs/usability-ux-fuer-dummies/figures/`
as PNGs, with captions listed in `figures/manifest.md`. Filename pattern:
`cNNfMMM.png` = Abbildung NN.MMM (e.g. `c13f009.png` = Abbildung 13.9, smartphone
button sizes). Most figures are website screenshots whose lesson is already in the
prose/caption, but some are data-bearing diagrams (button dimensions, screen
resolutions, method comparison tables). When a chapter hit references an
»Abbildung« whose specific values matter for your recommendation, look up the
figure in `manifest.md` and view the PNG yourself (read the full path
`aux-docs/usability-ux-fuer-dummies/figures/<name>.png`) — do not guess the
diagram's contents from the caption alone. Only delegate to the observer agent
if your current model cannot process images.
