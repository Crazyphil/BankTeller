## MODIFIED Requirements

### Requirement: Public routes rendered by the SPA without auth gate

The existing requirement is modified: the SPA SHALL render **four** public routes — `/privacy`, `/terms`, `/enable-banking-callback`, and `/sandbox-credentials` — WITHOUT going through the authenticated `checkAuth()` + `Screen`-enum flow. The single early-return mechanism at the top of `App()` is unchanged (path inspection before any auth check; no routing library). The three existing public screens are unchanged (ScreenShell with floating ThemeToggle, no API calls). The new `/sandbox-credentials` screen renders the sandbox credentials popup per the `onboarding` capability's sandbox credentials popup route requirement and deviates from the existing public-screen conventions in two scoped ways: (1) it uses a compact brand lockup with a quiet `SANDBOX` chip and NO theme toggle (the theme is inherited from the origin tab via shared localStorage — a utility window is not a theme-switching surface); (2) it is the SOLE public screen that makes an API call — the session-cookie-authenticated `GET /api/aspsps` — because its content is bank data, not static prose. The endpoint enforces its own session check: a 401 renders the in-window error presentation (per the popup route requirement's edge-case matrix), never a redirect to the login screen. The route's URL parameters carry bank identity only, never credentials, and untrusted parameter values SHALL NOT be echoed in any rendered copy.

#### Scenario: Credentials popup route renders without the auth gate
- **WHEN** the SPA loads at `/sandbox-credentials?name=...&country=...`
- **THEN** the popup screen renders via the public-route early-return, without `checkAuth()` — an expired or absent session surfaces later as the in-window error presentation when the list fetch returns 401

#### Scenario: Credentials popup is the sole API-calling public screen
- **WHEN** the `/sandbox-credentials` screen renders
- **THEN** it fetches the bank list via the session-cookie-authenticated `GET /api/aspsps` — the other three public screens remain API-free, exactly as before

#### Scenario: Non-public path falls through to auth-gated flow
- **WHEN** the SPA loads at any path other than the four public routes (e.g., `/`, `/login`, `/dashboard`)
- **THEN** the public-route early-return does NOT fire; the SPA falls through to the existing `checkAuth()` + `when (viewModel.currentScreen)` flow, exactly as before this change

## REMOVED Requirements

### Requirement: Welcome dashboard
**Reason**: The welcome dashboard is superseded by the account overview — the first product feature. The dashboard's requirements (account cards, total balance, metadata strip, refresh affordance, state matrix, gate precedence) now live in the `account-overview` capability; the sync behavior behind them lives in the `balance-sync` capability. Keeping a stub requirement here would duplicate those specs.
**Migration**: The dashboard remains the post-login landing screen rendered inside `ScreenShell` with `BrandedTopBar` (brand logo, theme toggle, logout — unchanged, still covered by the Login screen requirement's top-bar scenario). The onboarding-gate precedence behavior (dashboard shown only after onboarding is complete) is re-specified in the `account-overview` capability's "Account overview dashboard" requirement. No behavior is lost; ownership moves to the new capabilities. The capability's Purpose paragraph is adjusted accordingly when the change is archived: "a post-login welcome dashboard" becomes "the post-login account overview (per the `account-overview` capability)" — the shell itself (login screen, session-aware routing, logout, onboarding gate, public routes) is unchanged.
