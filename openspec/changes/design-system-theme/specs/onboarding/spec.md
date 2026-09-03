## MODIFIED Requirements

### Requirement: SPA ActivationGuide step
The SPA SHALL update the `ActivationGuide` step (shown after registration, before `BankSelection`) to explain that what follows is a **two-step process** where the user authorizes with their bank **twice**: (1) **Account linking** — connects Enable Banking / BankTeller to the user's financial institution in a new browser tab; (2) **Session authorization** — grants active session permissions in the current tab. Both are required to provide free access to their accounts. The screen SHALL show a primary "Start Bank Setup" button that advances the onboarding state to `BankSelection`, and a secondary "Logout" button. The step SHALL be rendered inside the `WizardScaffold` (see design-system-theme spec): eyebrow ("STEP N OF M"), title, one-liner, content zone, wizard progress indicator, and the quiet-back/loud-forward footer. The two-step explanation SHALL follow the form grammar in `DESIGN-LANGUAGE.md` §4 — explanations adjacent to the controls they describe (Tier 2 group pattern), not a wall-of-text intro. Spacing SHALL use `Dimens` tokens; no hardcoded dp values.

#### Scenario: Two-step explanation shown
- **WHEN** the `ActivationGuide` step is shown
- **THEN** it explains both steps (linking in a new tab, then session authorization in the current tab) adjacent to the relevant controls, and shows a "Start Bank Setup" button in the wizard footer's forward slot

#### Scenario: Wizard frame present
- **WHEN** the `ActivationGuide` step is shown
- **THEN** the step is rendered inside `WizardScaffold` with eyebrow, title, one-liner, and the `WizardProgressIndicator` reflecting the current position in the flow

#### Scenario: User starts bank setup
- **WHEN** the user clicks "Start Bank Setup"
- **THEN** the onboarding state advances to `BankSelection`

### Requirement: SPA BankSelection step
The SPA SHALL add a `BankSelection` screen to the onboarding flow (after `ActivationGuide`). The screen SHALL fetch the full bank list from `GET /api/aspsps` (all banks, no server-side filters), filter it **client-side** by the user's search text (matching bank name, country, or BIC), display the filtered banks in a searchable list, and allow the user to select a bank and PSU type. On selection, the SPA SHALL collect `aspsp_name`, `aspsp_country`, and `psu_type` and advance to `LinkingProgress` (triggering `POST /api/link-accounts`). The screen SHALL handle loading, empty, and error states. The step SHALL be rendered inside the `WizardScaffold` with eyebrow, title ("Select Bank"), one-liner, wizard progress indicator, and unified back affordance (quiet back button in the footer returning to `ActivationGuide`). The search field SHALL be an `OutlinedTextField` following the Tier 1 field grammar (label above, no placeholder, `supportingText` for helper/error); the bank list SHALL be a `LazyColumn` with `BankLogo`/`BankAvatar`, bank name, BIC (in mono), and PSU-type `FilterChip`s themed per the design language (selected = primary container, unselected = surface); `CircularProgressIndicator` in `brass` during load. Errors SHALL follow the flow-level error pattern: `Text` in `danger` color inside the content zone with a retry `Button`. Spacing SHALL use `Dimens` tokens; the PSU-type FilterChips SHALL NOT use ad-hoc per-call color overrides. `BankAvatar` background colors SHALL derive from the theme palette (hash-based selection from theme-compatible colors), not the 12 hardcoded hex values.

#### Scenario: Bank list loads successfully
- **WHEN** the `BankSelection` screen mounts
- **THEN** it fetches `GET /api/aspsps` and displays the full bank list inside the wizard content zone (client-side filtering applies as the user types)

#### Scenario: User searches and selects a bank
- **WHEN** the user types in the search field and selects a bank
- **THEN** the SPA collects the bank's `name`, `country`, and the selected `psu_type`, and advances to `LinkingProgress`

#### Scenario: Bank list fails to load
- **WHEN** `GET /api/aspsps` returns an error
- **THEN** a flow-level error is shown: `Text` in `danger` color inside the content zone with a retry `Button`

#### Scenario: Unified back affordance
- **WHEN** the user clicks the back button in the wizard footer
- **THEN** the onboarding state returns to `ActivationGuide`

### Requirement: SPA LinkingProgress step
The SPA SHALL add a `LinkingProgress` screen to the onboarding flow (after `BankSelection`). After `POST /api/link-accounts` returns `authorization_url` and `psu_id_hash`, the SPA SHALL store the `psu_id_hash` in viewmodel state and show an explanatory message with an "Open linking page" button that the user clicks to open `authorization_url` in a **new browser tab** (the Enable Banking control panel). After completing linking in the other tab, the user closes that tab and returns to BankTeller, then clicks "I've completed linking, authorize now". **No background polling.** When the button is clicked, the SPA SHALL call `GET /api/onboarding/link-status` **once** (a single on-demand check). On `linked: true`, the SPA SHALL transition to `AuthProgress`. On `linked: false`, the SPA SHALL show an error and let the user retry the check or re-initiate linking.

The LinkingProgress screen SHALL also serve as the **resume point** when the SPA loads and `GET /api/onboarding/state` returns `(requires_relogin=false, linking_completed=true, auth_completed=false)`. This covers two cases: (a) the user opened a new tab while the original tab was at the bank's SCA page — the new tab shows the LinkingProgress screen with a "Continue to authorization" button so the user can re-initiate auth; (b) the auth callback failed (invalid code, bank denied, technical error) — the SPA shows the LinkingProgress screen with the `auth_error` message from the state endpoint and a "Continue to authorization" retry button. In both cases, clicking the button triggers `GET /api/onboarding/link-status` to verify linking was completed. On `linked: true`, the SPA then calls `POST /api/auth` to start a fresh authorization, passing the `selected_bank` fields (`aspsp_name`, `aspsp_country`, `psu_type`) from the state endpoint response as the request body. On `linked: false`, the SPA shows an error and offers a "Re-open linking tab" button to re-initiate linking. The server **clears `auth_error` from the user session at the start of that request**, so the error is shown exactly once and does not follow the user into the new attempt or appear in a subsequently opened tab. The step SHALL be rendered inside the `WizardScaffold` with eyebrow, title, one-liner, progress indicator, and unified back affordance ("Back to bank selection" returning to `BankSelection` via cancel-linking). The explanation that linking happens in a separate tab SHALL be placed adjacent to the "Open linking page" button (form grammar Tier 2), and the consequential "I've completed linking, authorize now" action SHALL sit in the wizard footer's forward slot. `auth_error` messages SHALL use the flow-level error pattern (`Text` in `danger` color with the retry button).

#### Scenario: Open linking URL in a new tab
- **WHEN** `POST /api/link-accounts` returns `authorization_url`
- **THEN** the SPA opens the URL in a new browser tab and shows the explanatory message + "I've completed linking, authorize now" button in the original tab

#### Scenario: User clicks the completion button — link completed
- **WHEN** the user clicks "I've completed linking, authorize now" and `GET /api/onboarding/link-status` returns `linked: true`
- **THEN** the SPA transitions to `AuthProgress`

#### Scenario: User clicks the completion button — link not yet completed
- **WHEN** the user clicks "I've completed linking, authorize now" and `GET /api/onboarding/link-status` returns `linked: false`
- **THEN** the SPA shows an error and lets the user retry the check or re-initiate linking

#### Scenario: Resume after new tab opened during authorization
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_error=null)`
- **THEN** the SPA shows the LinkingProgress screen with a "Continue to authorization" button (the user can re-initiate auth — useful when the original tab was at the bank's SCA page, or when auth was cancelled/never completed)

#### Scenario: Resume after auth callback failure
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_error="<reason>")`
- **THEN** the SPA shows the LinkingProgress screen with the error message (e.g. "Bank denied the authorization", "Invalid or expired code", "Technical error") and a "Continue to authorization" retry button

### Requirement: SPA AuthProgress step
The SPA SHALL add an `AuthProgress` screen to the onboarding flow (after `LinkingProgress`). The screen SHALL show a consent preview explaining what the bank's consent page will ask for (accounts, balances, transactions), and a "Continue to your bank" button that triggers `POST /api/auth` and redirects the **current tab** (not a new tab) to the returned `url` (bank SCA for session). On return (callback with `code`+`state`, processed server-side) the SPA checks the callback result. If session authorization succeeded, the SPA SHALL forward to the existing dashboard (unchanged, out of scope for this change). On callback errors (invalid code, state mismatch, authorization failure), the SPA SHALL show the LinkingProgress screen with the error message and a "Continue to authorization" retry button (via the `GET /api/onboarding/state` resume flow). The missing-session-cookie case is handled server-side by a 302 redirect to login — the SPA does not render an error screen for it. The step SHALL be rendered inside the `WizardScaffold` with eyebrow, title, one-liner, progress indicator, and unified back affordance. The consent preview (`ConsentItem` rows) SHALL be grouped in a card per form grammar Tier 2 with a single adjacent explanation line, and the security guarantee ("BankTeller will never transfer money without your explicit approval.") SHALL be rendered in a `DecisionBox` with emerald tint. The redirect to the bank happens on user button click (footer forward slot), not automatically.

#### Scenario: Redirect current tab to bank SCA for session
- **WHEN** `POST /api/auth` returns `authorization_url`
- **THEN** the SPA redirects the current tab (not a new tab) to that URL

#### Scenario: Session authorized
- **WHEN** the user returns from the bank SCA and the callback succeeds
- **THEN** the SPA forwards to the existing dashboard

#### Scenario: Session authorization fails
- **WHEN** the callback returns an error (invalid code, state mismatch, authorization failure)
- **THEN** the SPA loads, calls `GET /api/onboarding/state`, sees `(linking_completed=true, auth_completed=false, auth_error="<reason>")`, and shows the LinkingProgress screen with the error message and a "Continue to authorization" retry button

#### Scenario: Consent preview uses wizard frame and decision box
- **WHEN** the `AuthProgress` step is shown
- **THEN** the consent preview is rendered inside `WizardScaffold` with the progress indicator, the guarantee text appears in an emerald-tinted `DecisionBox`, and "Continue to your bank" occupies the footer's forward slot

### Requirement: Consent Preview Info Box (Tier 4)
Gray-zone informational panels (e.g., the consent preview) SHALL use an `OutcomeBox` styling: `OutlinedCard` with `surfaceContainer` fill, 1dp outline hairline border, and 4dp corners — not `surfaceVariant`. This documents the info-box pattern added during verification.

#### Scenario: Tier 4 info box styling
- **WHEN** gray-zone informational panels are shown (e.g., consent preview)
- **THEN** they use OutcomeBox: OutlinedCard with surfaceContainer fill, 1dp outline hairline border, 4dp corners — not surfaceVariant

### Requirement: Linking Open/Re-open Flow
The linking step SHALL provide an always-available way to (re-)open the bank linking URL, even when the original URL was already consumed (e.g., the tab was closed accidentally). A quiet "Re-open linking page" action SHALL re-request the URL and auto-open it (`pendingAutoOpen`), and the forward action ("I've completed linking") SHALL always be rendered regardless of URL state. When a linking error is shown, exactly one retry path SHALL be offered (the forward action) — no duplicate inline retry button.

#### Scenario: Always-available linking action
- **WHEN** the user is on the linking step and the link URL was already consumed (tab closed accidentally)
- **THEN** a quiet "Re-open linking page" action is available in content which re-requests the URL and auto-opens it (pendingAutoOpen), and the forward action ("I've completed linking") is always rendered regardless of URL state

#### Scenario: Duplicate retry removal
- **WHEN** the linking step shows a linking error
- **THEN** only one retry path is offered (the forward action), no duplicate inline retry button
