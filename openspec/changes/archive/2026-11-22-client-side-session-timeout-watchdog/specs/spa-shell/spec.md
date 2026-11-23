# Spec Delta

## MODIFIED Requirements

### Requirement: Login screen

The SPA SHALL present a login screen with username and password fields and a submit button. The login screen SHALL be the default route when no authenticated session exists. The login screen SHALL render inside `BankTellerTheme` and use the theme mode persisted in browser storage (defaulting to the system preference). It SHALL use the ScreenShell layout with width `Form` and display the brand logo lockup above the credentials form. The login screen SHALL have no top app bar (per DESIGN-LANGUAGE §5.1); the theme toggle SHALL float at the top-end corner over the content, not in a top bar. The submit button SHALL be styled as a pill (fully rounded) primary action per the design system's button standards. When the SPA's client-side timeout detection confirmed a session expiry (per the `session-management` capability's session-timeout feedback requirement — the probe verdict, the cold-boot evaluation, or the reasonless-401 backstop while the SPA was in-session), the login screen SHALL render the one-shot, dismissible brass `DecisionBox` panel (Tier 3 — leading 18dp `Icons.Outlined.WarningAmber` outlined glyph, trailing dismiss affordance, the remove-caveat panel's grammar) above the credentials form — never inside the dashboard shell or the wizard — with the copy naming the inactivity window (or an untimed expiry copy when the client's TTL is unknown — never a fabricated window); the panel does not alter the form's behavior or layout contract otherwise.

#### Scenario: Unauthenticated user sees login
- **WHEN** a user navigates to the application without an authenticated session
- **THEN** the SPA displays the login screen with username and password fields

#### Scenario: Successful login redirects to dashboard
- **WHEN** a user submits valid credentials on the login screen
- **THEN** the SPA navigates to the dashboard

#### Scenario: Failed login shows error
- **WHEN** a user submits invalid credentials on the login screen
- **THEN** the SPA displays an error message without revealing whether username or password was incorrect

#### Scenario: Rate-limited login shows retry message
- **WHEN** the login endpoint returns HTTP 429
- **THEN** the SPA displays a message indicating the user should wait before retrying

#### Scenario: Login screen respects theme preference before server contact
- **WHEN** an unauthenticated user opens the login screen
- **THEN** the screen renders in the theme mode resolved from browser storage / system preference without waiting for any server response

#### Scenario: Login uses ScreenShell with form width
- **WHEN** the login screen renders
- **THEN** `ScreenShell(widthMode = Form, showLogo = true)` is used, the logo lockup is visible above the form, and no top bar is present

#### Scenario: Login screen has theme toggle
- **WHEN** the login screen renders
- **THEN** a theme toggle is visible at the top-end corner over the content (not in a top bar)

#### Scenario: Dashboard top bar has theme toggle and logout
- **WHEN** the dashboard renders
- **THEN** the `BrandedTopBar` actions row contains the `ThemeToggle` followed by the logout control and nothing else

#### Scenario: Onboarding top bar has theme toggle and logout
- **WHEN** the onboarding gate renders
- **THEN** the `BrandedTopBar` actions row contains the `ThemeToggle` followed by the logout control and nothing else

#### Scenario: Session-expiry panel renders above the credentials form
- **WHEN** the login screen renders with the SPA's client-side timeout detection having confirmed a session expiry (probe verdict, cold-boot evaluation, or in-session backstop latch)
- **THEN** the brass DecisionBox panel (leading warning glyph, trailing dismiss affordance) renders between the brand lockup and the credentials form, and the form fields and submit button behave as before
