# BankTeller — System Specification

## 1. Vision

BankTeller is a **self-hosted banking automation assistant** — a Docker-deployable web service that connects to a user's bank accounts via the Enable Banking Open Banking API and lets them configure automation workflows in the style of IFTTT or n8n. It monitors accounts, detects conditions, and prepares actions — but **always requires explicit user approval for any payment**. The product is positioned as giving the user full control: the system never silently moves money.

**Core promise**: *Your banking assistant that never acts without you.*

## 2. Product Principles

1. **User control is absolute** — No payment is ever initiated without the user's explicit approval. The system prepares, notifies, and waits.
2. **Single-user by design** — BankTeller serves one user. Enable Banking's free production tier only permits linking your own accounts; multi-user would require a "bring your own key" model that adds complexity without proportional value for this product.
3. **Self-hosted and private** — The user owns their data. No cloud dependencies beyond Enable Banking API calls. SQLite database, local Docker deployment.
4. **Simple by default** — Background sync happens automatically. No manual refresh buttons. The system works without the user thinking about it.
5. **Incrementally configurable** — Essential config (auth credentials, port) in `.env`. Everything else (bank connections, notifications, workflows) configured through a friendly onboarding UI after login.

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Container                          │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   Ktor Server (Netty)                      │   │
│  │                                                           │   │
│  │  ┌───────────┐  ┌──────────────────┐  ┌────────────────┐ │   │
│  │  │  Static   │  │  Internal Routes │  │  Background    │ │   │
│  │  │  Assets   │  │  (SPA-only)      │  │  Sync Engine   │ │   │
│  │  │  (SPA)    │  │                  │  │  (scheduled)    │ │   │
│  │  └─────┬─────┘  └──────┬───────────┘  └────────┬───────┘ │   │
│  │        │               │                       │         │   │
│  └────────│───────────────│───────────────────────│─────────┘   │
│           │               │                       │             │
│  ┌────────│───────────────│───────────────────────│─────────┐   │
│  │        ▼               ▼                       ▼         │   │
│  │  ┌──────────────────────────────────────────────────┐    │   │
│  │  │              Browser (wasmJs SPA)                 │    │   │
│  │  │  Login → Dashboard → Onboarding → Workflows      │    │   │
│  │  └──────────────────────────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────┐  ┌────────────────────────────────────┐   │
│  │   SQLite DB      │  │   Key Store (JWT + VAPID keys)     │   │
│  │   (persistent    │  │   (auto-generated, stored in DB)   │   │
│  │    volume)       │  │                                    │   │
│  └──────────────────┘  └────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────┐                                           │
│  │   .env            │  ← Only what's needed to boot container  │
│  │   AUTH_USERNAME   │     (auth, port)                         │
│  │   AUTH_PASSWORD   │                                           │
│  │   SERVER_PORT     │                                           │
│  └──────────────────┘                                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Optional: Apprise Sidecar (Docker Compose)                     │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  Apprise Container (single HTTP POST → 100+ channels)    │   │
│  │  Telegram, Discord, Slack, Matrix, etc.                  │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.1 No Public API

BankTeller is an integrated SPA + server product. The frontend and backend are co-developed and co-deployed. There is no formal REST API designed for third-party clients. Internal Ktor routes exist solely to serve the SPA. Implications:

- No `/api/v1/...` formal contract
- No CORS configuration (same-origin)
- No API documentation for external consumption
- Session-based auth (httpOnly SameSite=Strict cookies; Bearer tokens for future WebSocket)
- Real-time updates via SSE or WebSocket (future, for sync status, workflow results)

This can be reconsidered if a legitimate need for external clients arises, but it is not an initial design goal.

### 3.2 Module Structure

```
BankTeller/
├── core/                   ← Shared domain logic
│   └── commonMain/
│       ├── domain/         ← Account, Transaction, Payment models
│       ├── workflow/       ← Trigger, Condition, Action definitions
│       └── sync/           ← Sync strategy logic
│
├── server/                 ← Ktor JVM backend
│   ├── routes/             ← Internal SPA routes
│   ├── auth/               ← JWT session middleware
│   ├── sync/               ← Background sync engine
│   ├── notifications/      ← Notification dispatch service
│   ├── config/             ← .env + DB config reader
│   └── keys/               ← Auto-generate + store JWT/VAPID keys
│
├── app/web/                ← Compose Multiplatform web frontend
│   ├── commonMain/         ← Shared UI code (login, dashboard, etc.)
│   ├── wasmJsMain/         ← wasmJs platform specifics
│   ├── jsMain/             ← JS fallback platform specifics
│
├── Dockerfile              ← Single container: Ktor + static assets
├── docker-compose.yml      ← Optional Apprise sidecar
├── .env                    ← Minimal bootstrap config
└── openspec/               ← Spec-driven development artifacts
```

The `:app:shared` and `:app:webApp` modules are merged into a single `:app:web` module. The web frontend is a PWA only — no cross-platform (Android/iOS) sharing is planned, so separate shared modules are unnecessary.

## 4. Technical Decisions

### 4.1 Frontend: wasmJs with JS Fallback

| Decision | Rationale |
|----------|-----------|
| **Primary target**: wasmJs (WasmGC) | ~3x faster UI than JS, JetBrains' strategic direction, ~95% browser coverage |
| **Fallback**: Kotlin/JS | `composeCompatibilityBrowserDistribution` auto-serves JS to browsers without WasmGC |
| **Compose Multiplatform** | Canvas-based rendering. No SEO/accessibility concerns — this is an authenticated app shell, not a public website |
| **Excluded browsers** | ~5-10% mobile (mainly iOS <18.2). Fallback covers these |

Browser support: Chrome 119+, Firefox 120+, Safari 18.2+, Edge 119+, Samsung Internet 25+.

### 4.2 Database: SQLite via SQLDelight

| Decision | Rationale |
|----------|-----------|
| **SQLite only** | Single-user, self-hosted, no concurrency needs beyond one user |
| **SQLDelight** | KMP-compatible (goes in `:core`), built-in schema migrations, type-safe SQL access, SQLite-first-class |
| **No encryption** | Standard SQLite — security is the admin's responsibility (same model as Nextcloud, Home Assistant, Firefly III). Admin protects the Docker volume via filesystem permissions, LUKS, or other mechanisms. Technology-neutral, keeps Docker image simple |
| **WAL mode + busy_timeout (5s)** | Background sync + on-demand sync + workflow evaluation + payment submission run concurrently. WAL mode enables safe concurrent reads/writes. Busy timeout prevents immediate failures under contention |
| **No DB config in .env** | Fixed convention: file in persistent Docker volume |

SQLite is sufficient for a single-user application. No configuration options are offered — the database lives at a fixed path inside the container, mapped to a persistent Docker volume. SQLDelight provides both the DB access layer and migration framework — `.sq` files define queries, migrations are versioned and applied automatically via `SqlSchema.migrate()`. No external migration tool (Flyway, dbmate) is needed.

**PostgreSQL escape hatch**: If SQLite proves insufficient in the future (e.g., heavy concurrent write patterns), SQLDelight supports PostgreSQL as a JVM driver. The migration would require a different Dockerfile but the query code in `:core` remains unchanged.

### 4.3 Authentication: Username + Password from .env

| Decision | Rationale |
|----------|-----------|
| **Single user** | Deliberate — EB free production tier is own-accounts only |
| **Username + password** | From .env only. Approximates a real login system. Expandable to multi-user later without architectural change |
| **Session transport** | httpOnly SameSite=Strict cookies. Secure for same-origin SPA. Bearer tokens can be added later for WebSocket if needed |
| **No OAuth2** | Not needed — single-user, self-hosted, no third-party clients |

**Future extension**: The auth system is designed so that MFA (WebAuthn, TOTP, or similar) can be added as an optional second factor. This is particularly relevant for sensitive actions like payment approval — step-up authentication could require re-verification before authorizing a transfer. The architecture supports this without restructuring: the session middleware can be extended to require step-up for specific routes, and the onboarding/settings UI can register second-factor credentials.

### 4.4 Key Management: Auto-Generate, Store in DB

| Key Type | Generation | Storage | User Visibility |
|----------|-----------|---------|----------------|
| JWT signing key | First startup | SQLite | Never |
| VAPID keys (Web Push) | First startup | SQLite | Never (public key sent to frontend internally) |
| EB RSA private key | User input | SQLite | Entered during onboarding UI |

The user is never asked to generate or manage cryptographic keys. JWT and VAPID keys are created automatically on first startup. The EB private key is the only key the user provides, and they do so through a friendly onboarding screen — not a configuration file. The EB key is stored in the standard SQLite database; data protection is the admin's responsibility (volume encryption, filesystem permissions — see §4.2).

### 4.5 .env Philosophy: Minimal Bootstrap Only

The `.env` file contains **only what is needed to get a running container**. Everything else is configured through the onboarding UI after login.

```
# .env — BankTeller container bootstrap
AUTH_USERNAME=admin
AUTH_PASSWORD=changeme
SERVER_PORT=8080
```

Items NOT in .env (configured via onboarding or auto-generated):
- Enable Banking private key → onboarding UI
- VAPID keys → auto-generated
- JWT signing key → auto-generated
- Notification preferences → onboarding UI
- Apprise URL → onboarding UI (optional sidecar)
- Workflow configurations → in-app UI
- Database path → fixed convention (Docker volume)
- Bank connections → onboarding UI

### 4.6 Payment Strategy: Prepare & Notify (Never Auto-Approve)

| Strategy | Description | Priority |
|----------|-------------|----------|
| **Prepare & Notify** | `defer_submission=true` on Enable Banking. PSU does SCA at payment creation time. TPP-only `POST /submit` later after user approval. | Primary. Best for variable-amount reactive payments (e.g., "when salary arrives, prepare a transfer for the excess") |
| **Batch Approval** | `BULK_SEPA` with single SCA for multiple payments. User chooses batch or individual based on bank support. | Secondary. Support both modes, let user choose |
| **Standing Orders** | PSU authorizes once, ASPSP executes schedule automatically. | NOT prioritized. Too rigid, no room for variability. Option remains open for future implementation |

**Critical rule**: Every payment requires explicit user approval. The system never silently initiates a transfer.

### 4.7 Sync Strategy: Background + On-Demand

| Mode | PSU Headers | Frequency | Rate Limit |
|------|------------|-----------|------------|
| **Background sync** | None (offline mode) | ~4x/day (scheduled) | ASPSP rate limit (~4 fetches/day) |
| **On-demand sync** | All required (online mode) | Unlimited when user is in app | No rate limit |
| **Initial sync** | All required (online mode) | Immediate after account connection | Captures 90-day history window |

The system does not offer a manual "refresh" button. Sync happens automatically — background by schedule, on-demand when the user opens the app. This keeps the experience simple.

**90-day history constraint**: Under PSD2, ASPSPs are only required to provide 90 days of transaction history after initial PSU authorization. This is an Enable Banking / regulatory limitation, not a design choice — more than 90 days is only available at the moment of first connection. The system must sync aggressively with PSU headers right after connection to capture this window. The sync engine should use `strategy=longest` when fetching initial transactions to request the maximum available history from each ASPSP. Whatever the bank provides at connection time is all we'll ever get for that period — after the window closes, only ongoing transactions are accessible.

### 4.8 Notifications: Native Core + Apprise Sidecar

| Channel | Implementation | Notes |
|---------|---------------|-------|
| **Email** | Simple Java Mail (Apache 2.0) | SMTP config during onboarding. No Kotlin-native alternatives exist (Kotlinmailer is archived upstream with tiny community). Simple Java Mail has the cleanest API, active maintenance, and wraps easily in `withContext(Dispatchers.IO)` for Ktor coroutines |
| **Web Push** | interaso/webpush (native Kotlin, zero deps) | VAPID keys auto-generated |
| **Everything else** | Apprise sidecar (Docker Compose) | Telegram, Discord, Slack, 100+ services. Single HTTP POST fans out |

Apprise is an optional Docker Compose sidecar — not required to run BankTeller. Users who want Telegram, Discord, etc. include it in their Compose config and provide the Apprise URL during onboarding.

**Onboarding integration**: Notification channel setup is part of the onboarding flow. The user selects desired channels, provides credentials per channel, receives a test notification, and preferences are stored in the database.

## 5. System Modules — Detailed

### 5.1 Enable Banking Client (Future Spec)

Responsibilities:
- Authenticate with Enable Banking API (RS256 JWT signed with app's RSA private key)
- Start PSU authorization flows (redirect to bank, exchange code for session)
- Fetch account details, balances, transactions (with and without PSU headers)
- Create payments (with `defer_submission=true` for Prepare & Notify pattern)
- Submit deferred payments after user approval
- Monitor session expiry and prompt re-authorization
- Match accounts across sessions using `identification_hash`

Key constraints:
- Sessions expire (max 180 days, can be earlier) — PSU must re-authorize
- Account IDs (`uid`) are session-scoped — use `identification_hash` for stable matching
- `entry_reference` is only unique per account (not globally) — compound key needed
- PSU headers (Psu-Ip-Address, Psu-User-Agent, etc.) — either all or none
- ~4 background fetches/day without PSU headers; unlimited with PSU headers
- 90-day transaction history window after initial authorization

### 5.2 Sync Engine (Future Spec)

Responsibilities:
- Scheduled background sync (~4x/day, no PSU headers)
- On-demand sync when user is in app (with PSU headers, unlimited)
- Aggressive initial sync after account connection (with PSU headers, capture 90-day history)
- Track which transactions have been synced (avoid duplicates using `entry_reference` + `identification_hash`)
- Handle session expiry gracefully (mark session as expired, notify user to re-authorize)

### 5.3 Workflow Engine (Future Spec)

Responsibilities:
- Evaluate rules against synced data after each sync cycle
- Trigger actions when conditions match
- Queue payment preparations for user approval
- Send notifications per configured channels

**Workflow model**: IFTTT-style rule builder — trigger + conditions + actions. JSON data model:

```json
{
  "id": "rule-1",
  "name": "Salary overflow transfer",
  "enabled": true,
  "trigger": {
    "type": "transaction_received",
    "filters": [
      { "field": "creditor.name", "operator": "contains", "value": "Employer" },
      { "field": "amount", "operator": "greater_than", "value": 3000 }
    ]
  },
  "conditions": [],
  "actions": [
    {
      "type": "prepare_payment",
      "params": {
        "amount_formula": "transaction.amount - 3000",
        "destination_iban": "DE89370400440532013000",
        "destination_name": "Savings Account"
      }
    },
    {
      "type": "send_notification",
      "params": {
        "message": "Salary received: {{amount}}. Transfer of {{excess}} prepared for approval.",
        "channels": ["email", "web_push"]
      }
    }
  ]
}
```

**UI evolution plan**:
- Phase 1: IFTTT-style rule builder (trigger → conditions → actions, form-based)
- Future: Full node-graph visual editor (like n8n/Node-RED) with JSON import/export

The JSON data model is portable — the same workflow definitions work regardless of which UI creates them.

### 5.4 Notification Service (Future Spec)

Responsibilities:
- Dispatch notifications through configured channels
- Native: Email (Simple Java Mail), Web Push (interaso/webpush)
- Sidecar: Apprise for Telegram, Discord, Slack, and 100+ other services
- Store notification preferences in DB
- Test notifications during onboarding to verify channel configuration

### 5.5 Onboarding Flow (Future Spec)

Responsibilities:
- Collect Enable Banking private key (friendly UI, not .env)
- Guide bank account connection (redirect to bank via EB API)
- Configure notification channels (email SMTP, Apprise URL, per-channel setup)
- Send test notifications to verify configuration
- Store all onboarding data in database

**Onboarding detection**: Derived from actual data, not stored state. On each login, the system checks what's present in the database:

```
  Is EB private key in DB?           → No? → Ask for it
  Are bank accounts connected?       → No? → Guide bank connection  
  Are notification channels set up?  → No? → Guide notification setup
  All present?                       → Yes → Show dashboard
```

This is self-correcting — if a bank session expires and accounts disconnect, the system detects missing data and redirects to re-auth. No stored state machine that can get out of sync.

## 6. User Experience

### 6.1 User Flow

```
┌─────────┐     ┌───────────┐     ┌──────────────┐     ┌─────────────┐
│  Login   │────▶│ Dashboard │────▶│  Onboarding  │────▶│  Dashboard  │
│  Screen  │     │  (empty)  │     │  (first run) │     │  (active)   │
└─────────┘     └───────────┘     └──────────────┘     └─────────────┘
                                         │                     │
                                         │                     │
                    ┌─────────────────────┘                     │
                    │                                           │
                    ▼                                           ▼
              ┌───────────┐                              ┌───────────┐
              │  Connect  │                              │  Manage   │
              │  Bank     │                              │  Workflows │
              │  Account  │                              │            │
              └───────────┘                              └───────────┘
                                                          ┌───────────┐
                                                          │  View     │
                                                          │  Accounts │
                                                          │  & Txns   │
                                                          └───────────┘
                                                          ┌───────────┐
                                                          │  Settings │
                                                          │  (notif,  │
                                                          │   auth)   │
                                                          └───────────┘
```

### 6.2 Navigation Structure

- **Accounts** — View connected bank accounts, balances, recent transactions
- **Workflows** — Create, edit, enable/disable automation rules
- **Settings** — Notification preferences, bank connection management, auth configuration
- **Onboarding** — First-run setup (bank connection, notifications, EB key)

### 6.3 Login Screen

Simple username + password form. On successful login, JWT session token issued (cookie or header). If onboarding not completed, redirect to onboarding flow.

### 6.4 Dashboard

Central view showing:
- Connected accounts overview (balances, recent activity)
- Active workflows summary
- Pending approvals (payments awaiting user decision)
- Recent notifications

### 6.5 Workflow Editor

Phase 1: IFTTT-style rule builder:
- Select trigger type (transaction received, transaction missed, balance threshold, date-based)
- Add condition filters (amount, creditor/debtor, category, date range)
- Configure actions (prepare payment, send notification, flag transaction)
- Enable/disable rules
- JSON export for archival

Future: Full visual node-graph editor (iframe + ReactFlow, or Compose canvas implementation).

## 7. Foundation Spec Scope (First Change)

The first change establishes the infrastructure skeleton. It does NOT implement business features.

### Included

- Docker packaging (Dockerfile, docker-compose.yml with optional Apprise sidecar)
- `.env` file with AUTH_USERNAME, AUTH_PASSWORD, SERVER_PORT
- Auth system: login UI (username + password) → httpOnly SameSite=Strict cookie session → session validation middleware → login rate limiting (5 attempts/15min)
- Module restructuring: merge `:app:shared` + `:app:webApp` into `:app:web`
- SQLDelight integration: DB driver hookup (JdbcSqliteDriver), WAL mode + busy_timeout enabled, migration framework wired up (even with zero initial migrations)
- Ktor server: serve static SPA assets, login route, session validation, health check
- Infrastructure hooks (structural placeholders, not implementations):
  - Config service (reads .env + future DB config)
  - Key store (auto-generate JWT + VAPID on first startup, store in DB)
  - Navigation structure with placeholder routes
  - Empty routes for: accounts, workflows, settings, onboarding
  - Navigation items in SPA for: Accounts, Workflows, Settings, Onboarding

### NOT Included (Future Specs)

- Enable Banking client
- Database schema and business migrations (only migration framework in foundation)
- Workflow engine and rule evaluation
- Notification system (Email, Web Push, Apprise)
- Onboarding UI content
- Sync engine (background + on-demand)
- Account/transaction display
- Payment initiation and approval flow

## 8. Open Questions

These are decisions not yet made or areas needing further exploration:

1. **Session expiry UX** — When an EB session expires, what exactly does the user see? Banner? Email? Redirect to re-auth?
2. **Payment approval UX** — How does the user approve a prepared payment? Dashboard notification → detail view → approve/reject button?
3. **Formula expressions in actions** — `amount_formula: "transaction.amount - 3000"` needs an expression evaluator. Which library? Security considerations?
4. **Workflow conflict resolution** — What happens when two rules produce contradictory actions on the same transaction?
5. **Backup/restore** — Should BankTeller support DB backup/export? What about disaster recovery for the Docker volume?
6. **Compose Multiplatform wasmJs beta stability** — The web target is beta. What's the fallback plan if critical bugs are encountered?
7. **Multi-currency handling** — Enable Banking returns transactions in various currencies. How does BankTeller handle currency conversion in workflow conditions?
