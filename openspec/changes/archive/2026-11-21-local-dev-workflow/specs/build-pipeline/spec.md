# Spec Delta

## MODIFIED Requirements

### Requirement: No stale artifacts in the server resources
The task that copies the web distribution into the server resources SHALL use synchronization (mirror) semantics: files in the target directory that are not part of the current distribution SHALL be deleted. The packaged server JAR SHALL contain only the current distribution's assets. The wasmJs distribution SHALL contain `index.html` (copied by KGP from `app/web/src/wasmJsMain/resources/`), so the copy task SHALL need no separate `index.html` source — the pre-change explicit copy duplicated the file and Sync's duplicate detection rejected it.

#### Scenario: Stale hashed artifacts are removed
- **WHEN** a new build produces content-hashed distribution files with names differing from the previous build
- **THEN** the previous build's files are deleted from the server generated-resources directory and are absent from the packaged JAR

#### Scenario: index.html ships once, from the distribution
- **WHEN** the server resources are assembled from the web distribution
- **THEN** `index.html` arrives via the distribution itself (KGP resource copy) — exactly one copy lands in the static resources, with no separate `from()` for it in the copy task

## ADDED Requirements

### Requirement: Development web server serves the app
The wasmJs development server (`wasmJsBrowserDevelopmentRun`, KGP's webpack-dev-server) SHALL serve the application at its root — `index.html` as the entry document with its relative asset URLs intact. The entry page SHALL live in `app/web/src/wasmJsMain/resources/index.html` (the KGP-standard resources location), from which KGP copies it into both the dev server's static content and the production distribution. The dev server exists for frontend-only iteration; it serves no backend.

#### Scenario: Dev server serves the entry page
- **WHEN** the development server runs and a browser requests `/`
- **THEN** the app's `index.html` is served (not a directory listing — the pre-change failure), and the app boots from the dev bundle

#### Scenario: Relative assets resolve at the root
- **WHEN** the entry page loads its scripts and resources
- **THEN** `web.js`, the wasm assets, and `composeResources/` resolve relative to the root path — no 404s, no MIME-type rejections
