## Context

BankTeller's onboarding wizard ends with an authorization step: `POST /api/auth` → redirect the current tab to Enable Banking (EB) → bank SCA → EB redirects back to `GET /enable-banking-callback` with `code`+`state` (success) or `error`+`state` (failure). The failure case was never specified: the callback route's catch-all branch (neither `oobCode` nor `code`) serves the SPA without recording anything, and the SPA's `CallbackScreen` renders a generic "Login link incomplete" page.

A production incident (2026-09-20, ARZ / Schelhammer Capital, AT) established the real failure shape: the bank's UI reported success, but the bank's `/accounts` endpoint returned HTTP 500 to EB, EB marked the session `INVALID`, and redirected with `error=server_error`. The EB control panel showed the ASPSP as "red" ("major issues detected") that day. Investigation of EB's (undocumented) CP endpoints showed:

- `GET https://enablebanking.com/api/get_today_stats` (Firebase idToken auth, same as other CP endpoints we already call) returns per-bank status: `{connector_name, brand, country, psu_type, service, status}` with status green/yellow/red, per psu_type.
- The CP request log (filterable by `authorization_id`) is the only post-mortem diagnostic; no public EB API explains *why* an authorization failed. `error_description` is optional on the redirect and was absent in our incident.
- The `authorization_id` from `POST /auth` is not echoed in the callback `state` (the state is purely BankTeller's own JWT) — it must be stored server-side at auth initiation to be available at callback time.

Existing infrastructure this builds on:

- One-shot `auth_error` session field with clearing semantics already specified (`POST /api/auth` clears it at attempt start; callback success clears it belt-and-suspenders; `cancel-linking` clears it).
- `EnableBankingControlPlaneClient` already authenticates to CP endpoints via Firebase idToken (persisted refresh token in `system_config`, `refreshIdToken` rotation) — e.g. `GET https://enablebanking.com/api/aspsps`, `/api/applications`, `POST /api/link_accounts`.
- The auth callback runs in the same browser tab that started the flow, so the `SameSite=Strict` session cookie is present (verified: the success flow works in production).
- Designer-reviewed UI spec exists for all new surfaces (badges, DecisionBox warnings, error panel, copy) — see "Designer-reviewed UI specification" below; DESIGN-LANGUAGE.md is binding.

## Goals / Non-Goals

**Goals:**
- Handle the `error` auth-callback redirect explicitly: record a differentiated, one-shot failure message (+ authorization ID) server-side, and land the user on the AuthProgress step with a calm explanation and a frictionless retry.
- Add a "Choose a different bank" back action to AuthProgress (mirroring LinkingProgress) so the user can abort and pick another bank.
- Surface per-bank ASPSP availability status (green/yellow/red) from EB's CP `get_today_stats` as advisory information: badge in the bank list, warning at bank commitment points, enrichment of the technical-failure message.
- Keep the callback page a thin intermediary with no own functionality.

**Non-Goals:**
- Automatic diagnosis of failure causes from CP request logs (deferred; candidate future feature for post-onboarding bank-connection errors).
- Any gating/blocking behavior based on ASPSP status — it is advisory only.
- Changes to the success flow, linking flow, or the resume-card fallback for session-less users.
- Detailed per-day status history (`get_past_stats`); only today's status is used.

## Decisions

### D1 — Error branch in the callback route, session-carried error, 302 to `/`
**Decision:** `GET /enable-banking-callback` gains an explicit auth-failure branch: `error` present (+ `state`, no `oobCode`, no `code`) → require session (same as the success branch), verify the `state` JWT against the session, write `authError` + `authErrorKind` to the user session — attaching the `authorization_id` that `POST /api/auth` stored at auth initiation (the callback itself receives no ID from EB; see D2) — **log the raw `error` value, `error_description` (when present), and the `authorization_id` server-side at callback time** (the session is ephemeral and dies with logout, so the log line is the only durable record for post-mortems), then **302 redirect to `/`** instead of serving the SPA at the callback URL.
**Rationale:** The SPA boots cold after the bank redirect (same-tab navigation destroyed all ViewModel state). The httpOnly session cookie survives the navigation (proven by the working success flow), so the session is the natural carrier — no new state-transport mechanism is needed. Redirecting to `/` keeps the callback a thin intermediary: the SPA boots at the canonical route, calls `GET /api/onboarding/state`, and routes itself.
**Alternatives considered:** Serving the SPA at the callback URL and letting it render the error (rejected: puts rendering responsibility on the callback page, which we explicitly want to avoid); encoding the error in a query parameter on `/` (rejected: duplicates the session mechanism, leaks error details into URLs/history).
**Edge case:** Missing session cookie or invalid `state` on the error branch → nothing can be recorded; 302 to `/` anyway. The user lands on the resume card (linked-but-never-authorized) without an error message — acceptable because this is rare (session timeout mid-authorization) and retry from the resume card is already frictionless.

### D2 — Authorization ID stored in the user session
**Decision:** `POST /api/auth` stores the `authorization_id` from EB's `POST /auth` response in the user session (identity-only cookie plus the one-shot failure fields — see the auth delta). The callback error branch includes it in the recorded error context; the state endpoint exposes it as a display-only field so the SPA can render "Authorization ID: …" in small mono text.
**Rationale:** It is the only key into EB's CP request-log diagnostics (manual inspection / support tickets). Session storage is "good enough": if the session dies mid-authorization, the user falls back to the resume card without the ID — acceptable for the same rarity reasoning as D1. The ID is not in the callback `state` (verified: state is purely BankTeller's JWT), so server-side storage is the only option.
**Alternatives considered:** Persisting to `system_config`/DB (rejected: outlives its usefulness after the next attempt; session lifetime matches the diagnostic window).

### D3 — Cold-entry routing: `auth_error` routes to AuthProgress
**Decision:** `GET /api/onboarding/state` gains a routing flag (`auth_failed`: true iff `auth_error` != null ∧ linking_completed ∧ ¬auth_completed). The SPA's `checkOnboardingStatus` routes to `OnboardingStep.AuthProgress` (instead of BankSelection/resume card) when that flag is set. AuthProgress on cold entry displays the error and does **not** auto-run `POST /api/auth`; the user clicks "Continue to your bank" to retry, which calls `POST /api/auth` (clearing `auth_error` per existing one-shot semantics) and redirects to the fresh URL.
**Rationale:** The user returns from their bank expecting to continue exactly where they left off — the authorization step — with zero re-selection friction. Displaying the error *before* any new attempt is an ordering constraint: `POST /api/auth` clears `auth_error` at attempt start, so an auto-retry would erase the message before the user sees it. The retry is the same button with the same label as the normal flow ("Continue to your bank") — label stability across states is calmer than a button that renames itself.
**Alternatives considered:** Routing to LinkingProgress (the pre-existing spec text routes auth errors there; superseded — LinkingProgress is about linking, and the user's mental position is the authorization step); auto-running `startAuth` on cold entry (rejected: violates the display-before-clear ordering and would redirect the user to their bank without them reading why the first attempt failed).

### D4 — Four error message variants, keyed on the `error` parameter
**Decision:** The callback error branch classifies the failure into a structured error kind (not a pre-baked display string) using the full RFC 6749 §4.1.2.1 error-code enum, and records it in the session together with the raw `error` value (and `error_description` when present) for server-side diagnostics:
1. **cancelled** — `access_denied`: user cancelled or declined at the bank (`error_description`, when present, may distinguish "Cancelled by user" from "Denied data sharing consent" — both map to `cancelled`).
2. **technical** — `server_error` and `temporarily_unavailable`: provider-side technical problem, typically transient; "you did nothing wrong, try again later"; enriched with the selected account type's current non-green status when available.
3. **configuration** — `invalid_request`, `unauthorized_client`, `unsupported_response_type`, `invalid_scope`: the bank rejected the authorization request itself — a setup/integration issue between BankTeller and that specific bank; not the user's fault and not transient (retrying is unlikely to help; choosing a different bank might).
4. **unexpected** — any other or missing `error` value, plus the code-exchange failures from the success branch (invalid code, state mismatch, session persist failure).
The SPA maps the kind to the designer-specified copy. **The raw `error` value is never rendered in the UI** — only the labeled "Authorization ID: <id>" mono footer is shown; the raw value stays in the session for server-side diagnostics.
**Rationale:** The `error` parameter is a standardized OAuth enum, so mapping all defined codes explicitly is more honest than folding known semantics into "unexpected" — that kind is reserved for genuinely unknown values. Raw machine tokens (`server_error`, `invalid_scope`) are developer jargon the user cannot act on; the kind-based copy covers every case the enum defines. Structured kind (not display string) preserves the existing spec rule that the SPA never parses `auth_error` strings for routing.
**Alternatives considered:** Passing EB's raw error text through (rejected: not user-facing language, and couples UI copy to an undocumented contract); a single generic message (rejected: user cancellation and configuration failures deserve distinctly non-alarming, distinctly actionable copy).

### D5 — ASPSP status via CP `get_today_stats`, 60s TTL, worst-of aggregation
**Decision:** `EnableBankingControlPlaneClient` gains `getAspspStatus()` calling `GET https://enablebanking.com/api/get_today_stats` (Firebase idToken auth, same pattern as the existing CP calls). The server caches the parsed result for **60 seconds** (not daily — status can change intra-day; e.g. a bank green at midnight can fail by lunch). The bank-list endpoint (`GET /api/aspsps`) attaches two per-bank status fields to its response: `status` — the **worst status across psu_types** for that `{name, country}` (red > yellow > green; used for the list badge, where no account type has been chosen yet) — and `status_by_psu_type` — a map of per-type statuses (e.g. `{"personal": "red", "business": "green"}`), used by the commitment-point warnings and the technical-failure enrichment to tailor the message to the user's **selected account type** (per the user's review decision); when the selected type has no status entry, the generic copy is used. AuthProgress reuses the same cached data for its warning/error enrichment. On fetch failure or cache miss with fetch error: no status fields at all, never blocks, never errors the bank list.
**Rationale:** 60s TTL matches the repo's standard "don't hammer the server" cache logic and keeps status fresh enough to be meaningful; the today-stats endpoint is the small one (the past-stats endpoint is 7.8 MB and multi-second — explicitly not used). Worst-of aggregation keeps the list badge single and conservative (a bank red for personal but green for business shows red — the user hasn't chosen a type yet at list time).
**Alternatives considered:** Daily TTL (rejected: intra-day changes are exactly the failures we want to catch); per-psu-type badges in the list (still rejected: clutter — the list badge stays worst-of; type-specific status is surfaced at the commitment points instead, per the user's review decision).

### D6 — Status surfacing per the designer-reviewed specification
**Decision:** Three touchpoints, one data source, all advisory:
1. **Bank list**: compact status tag in the bank card's top-right meta row (next to the country) — yellow: brass-tinted "Issues" badge; red: danger-tinted "Outage" badge (`labelSmall`, 12% alpha background, `shapes.small`). Green and no-data render **nothing** ("calm by default").
2. **Commitment points**: DecisionBox (3px semantic left border, 12% tint) below the PSU-type chips on the resume card / selected-bank card, and above the consent card on AuthProgress, when the **selected psu_type's** status (from `status_by_psu_type`) is non-green — generic fallback copy when that type has no status entry. Copy uses EB CP's own wording and names the account type: yellow = "possible issues detected", red = "major issues detected" (full copy in the designer specification below).
3. **AuthProgress error panel**: the technical variant's message is enriched with the selected account type's current status when non-green.
**Rationale:** Designer (des-1) produced the full specification following DESIGN-LANGUAGE.md; the user approved the placements and the CP-verbatim wording. Green rendering nothing keeps a typically-all-green list scannable.

### D7 — AuthProgress back action
**Decision:** AuthProgress gains a back action in the `WizardScaffold` back slot: QuietButton "Choose a different bank", wired to `cancelLinking()` (same as LinkingProgress's back), which clears the one-shot `authError` (and its kind) and returns to BankSelection.
**Rationale:** The authorization-failure case is exactly the "abort midway and try another bank" scenario; previously there was no way back once auth started (`onBack = null`). Reusing `cancelLinking` keeps one canonical abort path with existing clearing semantics.

## Risks / Trade-offs

- [CP `get_today_stats` is undocumented] → It follows the same Firebase-idToken auth as CP endpoints we already use in production (`/api/aspsps`, `/api/applications`); a smoke-check task verifies reachability before building on it. If it silently changes or disappears, the failure mode is "no status shown" — advisory only, no flow breaks.
- [Session dies mid-authorization → error never recorded, user lands on resume card without explanation] → Accepted (rare; resume-card retry is frictionless). Not worth persisting error state beyond session lifetime.
- [`server_error` classification is heuristic] → We claim only "technical problem on the bank/EB side, you did nothing wrong" — never the specific "your bank confirmed then failed" sequence (which we observed but cannot guarantee in general).
- [Worst-of list badge can over-warn (bank red only for the unchosen psu_type)] → Conservative by design for the badge (no account type is chosen at list time); the commitment-point warnings and failure enrichment are type-specific, so over-warning is confined to the badge.
- [Cold-entry AuthProgress with `auth_error` but stale consent preview] → The consent card remains valid (same bank, same scope); the error panel renders above it, and the retry click produces a fresh authorization URL.

## Designer-reviewed UI specification (binding for implementation)

From des-1 (grounded in DESIGN-LANGUAGE.md):

- **Bank list badge**: top-right meta row, before the country separator; `labelSmall`, `FontWeight.Medium`, `shapes.small`, padding `horizontal Dimens.xs / vertical 2.dp`; yellow = `brass` text + `brass.copy(alpha=0.12f)` background + 6dp brass dot, label "Issues"; red = `danger` text + `error.copy(alpha=0.12f)` background, label "Outage". Tooltip copy: yellow "Possible issues detected", red "Major issues detected" (CP-verbatim per user decision).
- **Commitment warning (DecisionBox, Tier 3)**: 3px semantic left border + 12% tint; keyed on the **selected psu_type's** status (generic fallback copy when that type has no status entry). Yellow: headline "Bank reporting possible issues detected", body "Connecting may take longer than usual or fail temporarily for [personal|business] accounts. You can still proceed or choose another bank." (fallback: "Connecting may take longer than usual or fail temporarily. You can still proceed or choose another bank."). Red: headline "Bank reporting major issues detected", body "Authorizations for [personal|business] accounts at this bank are currently failing. We recommend choosing a different bank or trying again later." (fallback: "Authorizations for this bank are currently failing. We recommend choosing a different bank or trying again later.").
- **AuthProgress error panel (DecisionBox)**: danger 3px left border, `error.copy(alpha=0.12f)` tint; `Icons.Filled.Error` (18dp, danger) + `titleMedium` danger title; `bodyMedium` body; optional status-enrichment line; "Authorization ID: <id>" in JetBrains Mono `labelSmall` `onSurfaceVariant`. Consent card stays below, reoriented toward the retry.
- **Footer**: primary "Continue to your bank" (forward slot, doubles as retry — label unchanged from the normal flow per user decision); QuietButton "Choose a different bank" (back slot).
- **Copy variants**:
  - *Cancelled*: title "Authorization cancelled"; body "The authorization request was cancelled at your bank. No accounts were connected and no data was shared." Guidance: "If this was unintended, you can try connecting again. You can also pick a different bank."
  - *Technical*: title "Bank connection unavailable"; body "Your bank could not complete the connection request. You did nothing wrong — this is usually a temporary disruption on the bank's side." Status enrichment (if the selected account type is non-green): "[Bank] is currently reporting [possible issues detected|major issues detected]." Guidance: "You can try again in a few moments, or return to the bank list to connect another institution."
  - *Configuration*: title "Bank connection not supported"; body "This bank rejected the connection request due to an integration setup issue. You did nothing wrong — BankTeller cannot complete the connection with this institution at this time." Guidance: "Retrying is unlikely to resolve this. We recommend returning to the bank list to connect a different bank."
  - *Unexpected*: title "Connection incomplete"; body "BankTeller could not verify the connection with your bank. Your credentials and accounts remain secure." Guidance: "Please try starting the authorization again. If the issue persists, try connecting a different bank or check back later."
  - All variants: "Authorization ID: <id>" mono footer (when available).

## Migration Plan

Purely additive server + SPA changes; no data migration, no schema changes (session field additions are backward-compatible — the session serializer ignores unknown keys, and old cookies without the new fields decode fine). Deploy as a normal container image build. Rollback = previous image; no persisted state depends on the new fields.

## Open Questions

- None blocking. (Deferred by explicit user decision: automatic request-log diagnosis as a future standalone feature for post-onboarding bank-connection errors.)
