## Context

BankTeller determines Enable Banking app status via the data-plane `GET https://api.enablebanking.com/application` (`EnableBankingClient.verifyApplication()`), authenticated with the app's own RSA key. Empirical findings from this change's exploration (all observed live against the EB sandbox + a real production app):

- **Deletion is invisible to the data-plane.** After deleting the app in the EB CP, `GET /application` with the dead kid's JWT still answers `200` with the full record and `active: false` — no 401, no 404, no tombstone. `verifyApplication()`'s `InvalidCredentials` branch can never fire for a deleted app.
- **The CP list only contains existing apps.** `GET https://enablebanking.com/api/applications` (Firebase idToken auth, already used by `EnableBankingControlPlaneClient.getApplication`) omits deleted apps entirely — absence of the configured kid is a positive deletion signal.
- **`active` is effectively a monetization flag.** Per the EB CP front-end source (`app.195ee1cf.js`): `pendingActionType: "activation"` → "request activation" (pay to activate without linking); any other value + active → "request unrestriction" (pay to lift the free-tier linking requirement). There is no user-facing deactivation path; `active` flips to `true` immediately upon linking the first account.
- **`whitelisted_accounts`** appears on the CP list only when accounts have been linked (absent, not `[]`, otherwise) and survives bank authorizations unchanged. Entries carry `created`, `title` (IBAN label), `identification_hash`, `aspsp{name, country}`, `linker` (Firebase user id of the linking user).
- The refresh token is treated as valid for BankTeller's lifetime (all CP functions already depend on it), so a CP-based status check has no availability concern.

Current status flow (`OnboardingService.getStatus`): `verifyApplication()` → `active:true` → persist `enable_banking_previously_active`; `active:false` → disambiguate fresh-vs-deleted via the local `previously_active` marker (fresh → ActivationGuide, previously-active → RegistrationReview). This fails for register→delete-immediately (never was active → marker never set → looks like a fresh app).

## Goals / Non-Goals

**Goals:**
- Positively detect app deletion and route the wizard back to app registration.
- Single source of truth for app existence/status: the CP application list.
- Replace the local `previously_active` heuristic with EB's own data (whitelist presence).
- Surface inactive-but-linked and restricted states as non-blocking, best-effort status information.

**Non-Goals:**
- Dashboard UI for account/bank-connection status (future change; this change only exposes the data and a minimal status note in the onboarding/login-gate context).
- Any use of `pendingActionType` beyond informational display.
- Detecting EB-side deactivation *reliably* — it is assumed to barely occur; best-effort only.
- Changing banking operations (sessions, accounts, transactions, payments) — data-plane usage there is unchanged.
- Migrating/clearing the `enable_banking_previously_active` key (stale value is simply ignored).

## Decisions

### D1 — CP application list is the source of truth for status; `verifyApplication()` is dropped from status determination

`getApplication(idToken, applicationId)` returns an explicit `Deleted` outcome when the kid is absent from the list, instead of the current `Ok(active=false, whitelistedAccounts=[])` collapse. `getStatus` is reworked onto this call.

*Alternative considered:* keep `verifyApplication()` as a cheap first hop and only consult the CP list when it reports `active:false`. Rejected: the data-plane is *provably misleading* for deleted apps (answers 200 forever), its `InvalidCredentials` signal is unreliable for deletion, and the refresh token is guaranteed available anyway — the layered design optimizes a constraint that doesn't exist. The data-plane `/application` call has no remaining role in status; its only unique field (`countries`) is unused by BankTeller.

### D2 — State model derived from the CP list entry

```
kid ABSENT                          → DELETED            → wizard: app registration
present, active:false, no whitelist → INACTIVE_NEVER_LINKED → wizard: ActivationGuide
present, active:false, whitelist    → INACTIVE_BUT_LINKED   → non-blocking status note
present, active:true                → ACTIVE                → proceed
```

`pendingActionType` is parsed and reported alongside (informational only, never gates routing).

### D3 — `previously_active` marker retired; whitelist presence discriminates

"Linked but inactive" is derived from `whitelisted_accounts` presence on an inactive entry — EB's own authoritative record of "linking ever happened". No local marker to persist, no sync drift, survives a local DB wipe. Read/write paths for `enable_banking_previously_active` are removed; the stale key is ignored (no migration).

*Alternative considered:* keep the marker as belt-and-braces. Rejected: two sources of truth for the same fact, and the marker demonstrably fails the register→delete case it was invented for.

*Known limitation:* if a hypothetical EB-side deactivation also cleared the whitelist, the user would see ActivationGuide instead of the status note. Accepted: deactivation barely occurs, and re-linking is a sane best-effort response anyway.

### D4 — Inactive-but-linked is non-blocking, best-effort

No dedicated blocking page, no forced re-onboarding, no credential reset. The state is reported to the SPA (status endpoint + response fields), displayed as a status note, and data fetching is still attempted — if it fails, the user knows why. Rationale: resolution happens in the EB CP (pay/contact EB), not in BankTeller; and we cannot know whether an inactive app still serves data-plane calls (the data-plane is lax — it served a deleted app happily).

### D5 — `Deleted` does not auto-reset credentials

Detection of `Deleted` only changes wizard routing (back to registration). Stored credentials (`enable_banking_application_id`, private key, refresh token) are left untouched; the existing explicit `/api/onboarding/enable-banking/reset` endpoint remains the only credential-clearing path, invoked when the user actually starts re-registration. This keeps detection side-effect-free and reversible.

### D6 — Status endpoint response shape

`/api/onboarding/status` reports the derived state explicitly: `enableBankingConfigured`, `appState` (`"deleted" | "inactiveNeverLinked" | "inactiveButLinked" | "active" | null`), `pendingAction` (nullable string) — replacing the `active`/`previouslyActive` pair. The `verified` field is **removed**: its old meaning ("the data-plane `verifyApplication` validated the app id + private key") cannot survive D1 (the CP path never touches the private key), and keeping it would be actively harmful — the SPA routes `verified == false` to EmailEntry before anything else, so any CP outage would mis-route every user to email re-entry, contradicting the unknown-state degradation (a previously-authorized user must still reach the Dashboard). Absence of `appState` (null) is the sole "unknown" signal. The spec pins the semantics (four states + informational flag + null-unknown); the JSON keys are fixed as above.

*Accepted regression:* after this change nothing validates the private key at login — a corrupted/mismatched key surfaces only when a data-plane banking call 401s at runtime. Exposure is small: the key is written once at registration and checked by the completion endpoint's post-registration verification (`verifyFreshCredentials`), and the key cannot be edited in place afterwards.

### D7 — `fetchWhitelist` reuses the same call

`AccountLinkingRoutes.fetchWhitelist` already calls `getApplication`; it now inherits existence detection for free. A `Deleted` result maps to a **definitive empty snapshot** (`WhitelistSnapshot(active=false, entries=[])`, cached like a success) — a deleted app has no linked accounts by definition. It deliberately does NOT map to the failure path: that would make `/api/onboarding/link-status` answer 502 "Failed to fetch Enable Banking application state" for a *successful* CP call and shift it to the 10 s failure cache. `Error` keeps the failure path. No extra request is introduced anywhere.

The `/api/onboarding/state` endpoint (whose `linking_completed` derivation also calls `getApplication`) needs **no spec delta**: the derivation text ("`getApplication` reports `active == true` AND a non-empty `whitelisted_accounts` list") remains satisfiable via the `Ok` variant, and a `Deleted` result maps to "no report", landing in the requirement's already-specified conservative degradation (`linking_completed: false`, `selected_bank: null`).

## Risks / Trade-offs

- [CP API is undocumented and may drift] → The CP list response shape changing degrades deletion detection. Accepted risk (precedent: `registerApplication`, `linkAccounts`, `getAspspStatus` already use CP internals). Failure mode is "unknown status", not a false positive: parse defensively, treat unexpected shapes as an unknown state (no `appState`), never as `Deleted`.
- [Private-key validation no longer happens at login] → After D1, nothing validates the private key until a data-plane banking call 401s at runtime (accepted regression, see D6). Exposure is small: the key is written once at registration and checked by the completion endpoint's post-registration verification.
- [Account-scoped list: app absent because the refresh token belongs to a different EB user] → Apps cannot be transferred between EB accounts, and BankTeller is single-user-by-design (re-linking to a different EB account means re-onboarding anyway). The `linker` field on whitelist entries (Firebase user id) is available as a future guard if this ever proves necessary; not built now (YAGNI).
- [EB-side deactivation semantics unverified] → We have never observed active→inactive. Best-effort per D4; if it turns out deactivation clears the whitelist, the state degrades to ActivationGuide (acceptable, see D3).
- [Request count per cold gate evaluation] → The CP list is now called twice (status path, 30 s cache; state path, 60 s whitelist cache — independent caches, and the status snapshot needs `pendingActionType` + existence, which `WhitelistSnapshot` does not carry), plus two idToken refreshes. Data-plane `/application` load drops to zero. Negligible at single-user scale.
- [SPA contract change on `/api/onboarding/status`] → Breaking for the SPA's login gate; both are deployed together from the same image, so no version-skew window exists.
- [Cross-change sequencing] → The in-flight `persist-onboarding-view-position` change is written against this change's post-archive vocabulary (it references `eb-application-status`, the `appState` values, and the retired marker) and MODIFIES the same login-gate requirement with a different branch ordering. Archive `deleted-app-detection` FIRST, then rebase the sibling's gate delta on the archived text and reconcile the ordering deliberately — otherwise the second archive silently rewrites the first.

## Migration Plan

Single-image deploy (server + SPA build together). No DB migration: `enable_banking_previously_active` rows may linger in `system_config` and are ignored. Rollback = previous image; the lingering key is harmless to the old code (it would simply be re-populated on the next active status).

## Open Questions

- None blocking. (During implementation: whether the SPA's RegistrationReview screen is removed entirely or repurposed for the deleted-state messaging. JSON field names are pinned by D6 and the delta specs.)
