# Design

## Context

The machine's own history (verified via podman: the `bankteller` container created 2026-11-16 during the WCAG change's verification, the `bankteller_bankteller-data` volume holding the real dev database, `bankteller:latest` rebuilt repeatedly) shows the team's actual dev workflow is the container loop — build image, `docker compose up`, test on :8080. The documented workflows didn't match reality: `:server:run` crashes on bare startup (database fallback hardcodes the container-only `/data`), and the web dev server has served a directory listing since the 2026-08 restructuring moved `index.html` to the module root. Two agents independently hit the `:server:run` crash and paid the same cold-start cost. Root cause of both: deployment contracts (volume path, resources location) leaked into the application's unconditional defaults.

## Goals / Non-Goals

Goals:
- The container loop becomes a documented, one-command, discoverable workflow (a Gradle task wrapping the existing scripts).
- `:server:run` boots bare and persists data — truthful as the documented quick path.
- Container deployments behave identically (same DB path, same volume).
- The index.html move (already in the working tree, verified) is adopted with its spec story.

Non-Goals:
- Any restructuring of the deployment (compose, Dockerfile, volume strategy stay as-is — the named volume is a production concern and works).
- A local development container with bind mounts (the dev loop emulates production via compose as today; if a deliberately containerized local variant is ever wanted, a compose *override* file is the tool — noted, not built).
- CI workflow implementation (compatibility is preserved by design; the workflows themselves are a future change).

## Decisions

### D1 — Container loop as the primary documented workflow

**Decision**: `:server:containerUp/Down/Logs` Gradle tasks wrap `scripts/build-image.sh` and the compose CLI; AGENTS.md documents the container loop as primary, `:server:run` as the no-container quick path.

**Rationale**: the user's actual history IS this loop; the product is defined as a self-contained container; testing the real deployment artifact is a feature, not overhead. Promoting the real workflow to documentation kills the tribal-knowledge problem (every cold-starting agent rediscovers it).

**Alternatives**: documenting the JVM path as primary — rejected: it retcons the established workflow and would make the dev DB a second divergent database. Overloading `:server:run` itself to build containers — rejected: hijacking the standard Ktor application task's meaning violates Gradle convention and confuses tooling; honest siblings instead.

### D2 — Script reuse for engine selection (CI compatibility by construction)

**Decision**: `containerUp` shells out to `scripts/build-image.sh` rather than reimplementing podman→docker detection.

**Rationale**: both project scripts already share the identical fallback; GitHub Actions runners (verified: containers are fully supported — container jobs, service containers, preinstalled Docker on `ubuntu-latest`) have Docker, never Podman. Reusing the script means the Gradle task inherits the CI story for free. One verified caveat recorded for future workflow design: `ubuntu-slim` single-CPU runners run unprivileged and forbid Docker-in-Docker — a future CI change should use standard Ubuntu runners.

### D3 — Database fallback: persistent project-relative default

**Decision**: resolution order stays `database.path` → `DATABASE_PATH` → `./data/bankteller.db` (project-relative, auto-created, persistent until manually deleted), and compose sets `DATABASE_PATH=/data/bankteller.db` explicitly.

**Rationale**: the user's stated expectation for the dev DB is "survives until cleared manually, not on every run" — a stable named file delivers exactly that, matching the volume's semantics in the container world. The explicit compose env closes the original sin: both worlds previously resolved from the same unconfigured fallback, which happened to be right in exactly one of them. CI note: a future workflow should set `DATABASE_PATH` explicitly per job (configuration-driven paths make explicitness possible); a cold runner without config would auto-create a workspace-local DB — survivable, wasteful, avoidable.

**Alternatives**: fail-fast error on unconfigured runs ("set DATABASE_PATH") — rejected: the user's expectation is a working, persistent default, not an error; tmp/throwaway location — rejected: contradicts the persistence requirement. Bind-mount the compose volume to a host file — rejected: that serves a hypothetical local-container dev variant, not the deployment; the named volume stays a production concern.

### D4 — Container cleanup: compose's recreation is sufficient

**Decision**: no cleanup logic in the tasks. `build-image.sh` already prunes dangling images (the axis that actually leaks — every `:latest` rebuild orphans the previous image); `compose up -d` recreates the service on image drift (verified archaeology: exactly one `bankteller` container exists despite weeks of the loop). Recorded maintenance note for AGENTS.md: intermediate-layer buildup across builds is a `podman build prune` concern, deliberately NOT an automation default (heavier prunes would nuke the browser-tests image and unrelated content).

### D5 — index.html: restore the KGP-standard resources location

**Decision** (already in the working tree, verified): `app/web/index.html` → `app/web/src/wasmJsMain/resources/index.html`. Both serving paths verified: dev root serves the app (was: directory listing); `:server:processResources` green with the static bundle complete; the redundant `copyWebDist` `from()` removed after Sync's duplicate detection rejected the double copy (the distribution now ships the file itself via KGP).

## Risks / Trade-offs

- [Old `.env` files don't set DATABASE_PATH; compose now does] → Mitigation: compose's explicit env is checked into the repo — no user action needed; behavior is byte-identical in the container.
- [Local `./data/` diverges from the volume's data over time (two real databases)] → Mitigation: they serve different loops by design (container = full-stack testing, JVM = backend quick iteration); AGENTS.md documents which is which; for data-relevant work, the container loop is the documented primary.
- [containerUp shells out to `docker compose` — command not found on hosts without compose] → Mitigation: same failure mode as today's manual loop, surfaced at the same point; the build script's engine check catches the image step earlier.

## Migration Plan

Working-tree edits already made and verified (index.html move, copyWebDist simplification) commit with this change. The DatabaseFactory one-line fallback change and the compose env line deploy together; container deployments need no migration (identical behavior). Rollback = revert the commit.

## Open Questions

- Task naming (`containerUp` vs e.g. `up` / `composeUp`) — bikeshed-grade, resolvable at implementation; `containerUp/Down/Logs` matches the repo's `containerWasmJsBrowserTest` naming precedent.
