## ADDED Requirements

### Requirement: Login screen
The SPA SHALL present a login screen with username and password fields and a submit button. The login screen SHALL be the default route when no authenticated session exists.

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

### Requirement: Welcome dashboard
The SPA SHALL present a simple welcome dashboard as the post-login landing page. The dashboard SHALL display a welcome message. No navigation items or business data are included — future changes add screens and navigation incrementally.

#### Scenario: Authenticated user sees welcome dashboard
- **WHEN** an authenticated user lands on the dashboard after login
- **THEN** the SPA displays a welcome message

### Requirement: Session-aware routing
The SPA SHALL check authentication state before rendering the dashboard. Unauthenticated users SHALL be redirected to the login screen. The login screen SHALL NOT be accessible to already-authenticated users (they are redirected to the dashboard).

#### Scenario: Unauthenticated access to dashboard
- **WHEN** an unauthenticated user navigates to the dashboard
- **THEN** the SPA redirects to the login screen

#### Scenario: Authenticated user navigates to login
- **WHEN** an authenticated user navigates to the login screen
- **THEN** the SPA redirects to the dashboard

### Requirement: Logout action
The SPA SHALL provide a logout action on the dashboard that calls the server logout endpoint and clears the local session state.

#### Scenario: User logs out
- **WHEN** an authenticated user clicks the logout action
- **THEN** the SPA calls the logout endpoint, clears session state, and redirects to the login screen
