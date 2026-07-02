## Why

BankTeller is a greenfield project with only scaffold code (a "Hello Ktor" route, placeholder Compose UI, and empty shared core module). Before any business feature can be built — bank connections, workflows, notifications — the project needs a foundation: Docker packaging, authentication, database infrastructure, module restructuring, and a bootstrapped SPA with login and a simple welcome dashboard. Without this, every future change would need to independently solve deployment, auth, persistence, and module layout, leading to inconsistent patterns and rework.

## What Changes

- **Docker packaging**: Add Dockerfile, docker-compose.yml (with optional Apprise sidecar), and `.env` bootstrap template
- **Module restructuring**: Merge `:app:shared` + `:app:webApp` into a single `:app:web` module (PWA-only, no cross-platform sharing needed)
- **Authentication system**: Username + password from `.env` → login UI → httpOnly SameSite=Strict cookie sessions → session validation middleware → rate limiting (5 attempts/15min)
- **Database infrastructure**: SQLDelight integration — JDBC driver hookup, WAL mode + busy_timeout, migration framework wired up (zero initial migrations, framework ready)
- **Ktor server backbone**: Serve static SPA assets, login/logout routes, session validation
- **Auto-generated JWT signing key**: Generated on first startup, stored in database (used for session token signing)
- **SPA shell**: Login screen → post-login welcome dashboard (no navigation or placeholder routes — future changes add screens and navigation incrementally)

## Capabilities

### New Capabilities
- `auth`: Login/logout with username+password from `.env`, httpOnly cookie sessions, rate limiting
- `database`: SQLDelight driver setup, WAL mode, migration framework, auto-generated JWT signing key storage
- `docker-deployment`: Dockerfile, docker-compose.yml (BankTeller service only), `.env` template, persistent volume
- `module-structure`: Gradle module restructuring (`:app:web`), source layout conventions, static asset serving, SPA fallback routing
- `spa-shell`: Compose Multiplatform wasmJs/JS web frontend — login screen, welcome dashboard, session-aware routing, logout

### Modified Capabilities
(none — this is the first change on a greenfield project)

## Impact

- **All Gradle modules** restructured (`:app:shared` and `:app:webApp` removed, `:app:web` created)
- **Server**: New routes, auth middleware, SQLDelight integration, JWT key generation on startup
- **Dependencies added**: SQLDelight (JDBC + SQLite driver), ktor-server-sessions, ktor-server-forwarded-header, bcrypt, compose-multiplatform web targets
- **New files**: Dockerfile, docker-compose.yml, `.env.example`, `.gitignore` entries
- **No breaking changes** (no existing functionality to break)
