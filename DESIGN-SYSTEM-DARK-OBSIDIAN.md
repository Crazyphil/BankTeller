# BankTeller — Design System Proposal ("Dark Obsidian Precision")

> **Status: Future direction — not the current implementation.**
>
> This is a complete UI/UX design proposal for a future app-wide redesign. It was
> generated during the account-linking brainstorming session (Aug 2026) as an
> aspirational design language. BankTeller currently uses standard Material 3
> Compose Multiplatform with default theming; this document is saved for a later
> design-overhaul discussion once Enable Banking onboarding is complete.
>
> **What's in here:**
> - A custom dark-mode-first fintech design system (typography, color tokens,
>   depth/glassmorphism effects)
> - Five screen layouts for the account-linking flow (bank picker, consent scope,
>   SCA redirect/polling, account discovery, success)
> - A reusable `BankPickerComponent` spec with keyboard navigation
> - Micro-animation patterns (staggered entrance, hover trails, optimistic controls)
> - Confirmation dialog microcopy (consent, session expiry, revoke, payment approval)
>
> **Note:** The five-screen flow in this proposal (consent scope, account discovery,
> transaction backfill) assumes a richer integration than the current semi-automatic
> linking flow. The design *language* (colors, typography, atmosphere, animations)
> is the reusable part; the screen flow is aspirational and would need re-scoping
> against the actual API capabilities.

---

## Original Proposal: Account-Linking Flow UI/UX ("Dark Obsidian Precision")

---

## 1. Visual Style & Atmospheric Design System

BankTeller’s design language is **Dark Obsidian Precision** — a refined, dark-mode-first fintech visual system that balances raw technical authority with approachable ease. It instills absolute trust and transparency, reminding the user at every step that BankTeller operates under strict user consent.

### 1.1 Typography Hierarchy
We avoid generic web defaults in favor of deliberate typography choices that balance characterful display headers with ultra-readable numerical presentation.

| Level | Font Family | Weight | Size / Line Height | Tracking | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Display Header** | *Space Grotesk* | Bold (700) | `32px / 40px` | `-0.02em` | Main screen titles, hero headers |
| **Section Header** | *Space Grotesk* | SemiBold (600) | `20px / 28px` | `-0.01em` | Card titles, step headers, modal titles |
| **Body Primary** | *Plus Jakarta Sans* | Regular (400) / Medium (500) | `15px / 22px` | `0` | Default body text, descriptions |
| **Caption / Labels**| *Plus Jakarta Sans* | SemiBold (600) | `12px / 16px` | `+0.03em` | UPPERCASE section caps, badge labels |
| **Data & Financial**| *JetBrains Mono* | Medium (500) / Bold (700) | `14px – 24px` | `-0.01em` | IBANs, amounts, timestamps, status tags |

---

### 1.2 Color Palette & Semantic Design Tokens

The atmosphere relies on a deep obsidian foundation layered with subtle radial gradients, frosted glass transparencies, and crisp signal accents.

```
+-------------------------------------------------------------------------------+
|  BACKGROUND       SURFACE CONTAINER    PRIMARY ACCENT      SUCCESS / SYNC     |
|  #0B0F17          #141923 (Glass)      #6366F1 (Indigo)    #10B981 (Emerald)  |
+-------------------------------------------------------------------------------+
```

* **Background Layer**: `#0B0F17` (Deep Obsidian Slate)
* **Surface Containers**: `#141923` with 80% opacity and subtle backdrop blur (`backdrop-filter: blur(12px)`)
* **Borders & Dividers**: `rgba(255, 255, 255, 0.08)` highlight border, transitioning to `rgba(99, 102, 241, 0.30)` on focus
* **Brand Primary Accent**: Electric Indigo (`#6366F1`) & Violet Blue (`#4F46E5`)
* **Success & Verified**: Emerald Mint (`#10B981`)
* **Warning / Approval Required**: Warm Amber (`#F59E0B`)
* **Destructive / Error**: Soft Crimson (`#EF4444`)
* **Text Tokens**:
  * Primary: `#F8FAFC` (100% Slate)
  * Secondary: `#94A3B8` (Muted Blue-Gray)
  * Tertiary / Disabled: `#64748B`

---

### 1.3 Depth & Texture Effects
* **Ambient Glows**: Soft 250px radial gradients (`rgba(99, 102, 241, 0.08)`) positioned at the top center of active flow containers to guide focal attention.
* **Border Highlights**: Cards feature a 1px top border gradient from `rgba(255, 255, 255, 0.15)` to `transparent`, creating a physical light-edge effect.
* **Glassmorphism**: Modals and floating sheets utilize frosted glass with standard 1px elevated border tokens (`#1E293B`).

---

## 2. End-to-End Account-Linking User Journey

The account-linking flow guides the user from provider selection through explicit PSD2 consent setup, external bank authentication (SCA), and account mapping.

```
 ┌────────────────┐     ┌────────────────┐     ┌────────────────┐     ┌────────────────┐     ┌────────────────┐
 │ 1. Bank Picker │ ───▶│   2. Consent   │ ───▶│  3. Bank SCA   │ ───▶│ 4. Discovery   │ ───▶│   5. Success   │
 │   & Provider   │     │    Scope &     │     │  Redirect /    │     │   & History    │     │  Confirmation  │
 │    Search      │     │  Permissions   │     │    Polling     │     │     Sync       │     │     Modal      │
 └────────────────┘     └────────────────┘     └────────────────┘     └────────────────┘     └────────────────┘
```

---

## 3. Screen-by-Screen Layouts & Visual Specs

### Screen 1: Bank Selection Hub (`BankPickerScreen`)

The entry point allows instantaneous lookup across thousands of European financial institutions supported by Enable Banking.

#### Visual Layout Diagram
```
+-----------------------------------------------------------------------------------+
|  [<- Back]                   CONNECT BANK ACCOUNT                    Step 1 of 4  |
|  Select your financial institution to establish a secure Open Banking link.       |
|                                                                                   |
|  [ Search by bank name, BIC, or country...                           (Ctrl+K) ]   |
|                                                                                   |
|  REGION: [ All (EU) ]  [ DE Germany ]  [ AT Austria ]  [ CH Switzerland ] [...]  |
|                                                                                   |
|  POPULAR BANKS IN YOUR REGION                                                     |
|  +-------------------+  +-------------------+  +-------------------+              |
|  |  [Logo]           |  |  [Logo]           |  |  [Logo]           |              |
|  |  Deutsche Bank    |  |  Commerzbank      |  |  ING DibA         |              |
|  |  DE - DEUTDEFF    |  |  DE - COBADEFF    |  |  DE - INGDDEFF    |              |
|  +-------------------+  +-------------------+  +-------------------+              |
|  +-------------------+  +-------------------+  +-------------------+              |
|  |  [Logo]           |  |  [Logo]           |  |  [Logo]           |              |
|  |  Sparkasse        |  |  N26 Bank         |  |  Revolut          |              |
|  |  DE - SPKADE21    |  |  DE - N26BDE21    |  |  LT - REVO21XX    |              |
|  +-------------------+  +-------------------+  +-------------------+              |
|                                                                                   |
|  ALL PROVIDERS (Showing 482 institutions)                                         |
|  +-----------------------------------------------------------------------------+  |
|  | (Logo) Santander Germany              BIC: SANDDE21          [Connect ->]  |  |
|  |-----------------------------------------------------------------------------|  |
|  | (Logo) Targobank                      BIC: TARODE11          [Connect ->]  |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

#### Key UI Components & Specs
1. **Search Bar**: Sticky at top, featuring auto-focus, live filter debounce (150ms), keyboard shortcut helper badge (`Ctrl + K`).
2. **Country Pills**: Segmented pill filter bar with flag icons and counter badges.
3. **Popular Banks Grid**: 3-column responsive grid highlighting the top 6 regional institutions with high-contrast bank logos and official BIC tags.
4. **Empty State**: If search yields zero matches, renders a clean fallback card: *"Can't find your bank? Request a provider or check your search term/BIC."*

---

### Screen 2: Consent Scope & Permission Config (`ConsentScopeScreen`)

Prior to transferring the user to their bank's OAuth/SCA portal, BankTeller explicitly requests and details all data access scopes.

#### Visual Layout Diagram
```
+-----------------------------------------------------------------------------------+
|  [<- Back]                     AUTHORIZATION SCOPE                   Step 2 of 4  |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |  (Bank Logo)  N26 Bank SE                                                   |  |
|  |               Official Open Banking Integration via Enable Banking API      |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  DATA ACCESS PERMISSIONS                                                          |
|  +-----------------------------------------------------------------------------+  |
|  |  [x] Read Account Details & IBANs                                            |  |
|  |      Allows BankTeller to display account numbers and account holder name.   |  |
|  |                                                                             |  |
|  |  [x] Read Real-Time Balances                                                |  |
|  |      Used to evaluate automation rules and display balance dashboards.      |  |
|  |                                                                             |  |
|  |  [x] Fetch Transaction History (Up to 90 Days initial backfill)             |  |
|  |      Required for transaction triggers and automation conditions.           |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  CONSENT DURATION                                                                 |
|  [ (o) 90 Days (Standard) ]     [ ( ) 180 Days (Maximum) ]     [ ( ) Single Access ]|
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | (i) BANKTELLER USER PROMISE                                                 |  |
|  | BankTeller will NEVER initiate payments or transfer money without your      |  |
|  | explicit approval. This connection is strictly Read-Only for monitoring.    |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  [ Cancel ]                                       [ Authorize & Continue to Bank -> ]|
+-----------------------------------------------------------------------------------+
```

#### Key Microcopy & Elements
* **Explicit User Promise Box**: Highlighting the read-only nature of AISP (Account Information Service Provider) access in a styled emerald-tinted container (`#10B981` at 10% opacity with 1px border).
* **Consent Duration Selection**: Options clearly detailing PSD2 regulatory bounds (90 vs 180 days) and automated re-consent notification behavior.

---

### Screen 3: SCA External Redirect & Polling State (`BankRedirectScreen`)

Handles the handoff when the user is sent to their bank's mobile app or web portal for Strong Customer Authentication (SCA).

#### Visual Layout Diagram
```
+-----------------------------------------------------------------------------------+
|                                                                                   |
|                                                                                   |
|                                     (( (o) ))                                     |
|                              [ Pulsing Gradient Ring ]                            |
|                                                                                   |
|                         AUTHENTICATING WITH DEUTSCHE BANK                         |
|                                                                                   |
|  We have redirected you to Deutsche Bank to complete authentication.               |
|  Please approve the login request in your bank's photoTAN or mobile app.          |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |  Status: Waiting for bank callback...               [ Live Sync Indicator ] |  |
|  |  Elapsed time: 00:24                                                        |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  [ Re-open Bank Portal ]                        [ I've Approved in Mobile App ]   |
|                                                                                   |
|  -------------------------------------------------------------------------------  |
|  Having trouble? [ Copy Direct Redirect Link ]  or  [ Cancel Authorization ]      |
+-----------------------------------------------------------------------------------+
```

#### Key Micro-Animations & Polling Logic
* **Glowing Pulse Ring**: Concentric glowing rings animating with a gentle breathing curve (`ease-in-out`, 2s duration) using primary accent indigo colors (`#6366F1`).
* **Cross-Device / Multi-Tab Synchronization**: Automatically polls `GET /api/onboarding/enable-banking/wait` every 1.5s. As soon as the callback is captured on any device/tab, this screen smoothly transforms into Screen 4 without full-page reloads.

---

### Screen 4: Account Discovery & Initial History Backfill (`AccountDiscoveryScreen`)

Displays all accounts returned by the bank session, allowing the user to pick which accounts to monitor and watch the 90-day backfill progress.

#### Visual Layout Diagram
```
+-----------------------------------------------------------------------------------+
|  [<- Back]                     DISCOVERED ACCOUNTS                   Step 3 of 4  |
|                                                                                   |
|  Select accounts to import into BankTeller:                                        |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | [x] MAIN CHECKING ACCOUNT                                                   |  |
|  |     IBAN: DE89 3704 0044 0532 0130 00  |  EUR  |  Balance: € 4,820.50          |  |
|  |     Nickname: [ Checking Account                 ]                          |  |
|  |-----------------------------------------------------------------------------|  |
|  | [x] INSTANT SAVINGS                                                         |  |
|  |     IBAN: DE89 3704 0044 0532 0130 99  |  EUR  |  Balance: € 12,450.00         |  |
|  |     Nickname: [ Emergency Fund                   ]                          |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  INITIAL HISTORICAL DATA SYNC (90-DAY PSD2 WINDOW)                                |
|  +-----------------------------------------------------------------------------+  |
|  | Syncing transactions: 248 items fetched...            [================>  ]  |  |
|  | Current Status: Parsing entry references for Checking Account...            |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|                                                     [ Save & Complete Setup -> ]  |
+-----------------------------------------------------------------------------------+
```

---

### Screen 5: Success & Workflow Prompt (`LinkingSuccessScreen`)

The final confirmation screen providing immediate feedback and onboarding transition into rule creation.

#### Visual Layout Diagram
```
+-----------------------------------------------------------------------------------+
|                                                                                   |
|                                    ( Check mark )                                 |
|                                [ Emerald Success Shield ]                         |
|                                                                                   |
|                             BANK LINKED SUCCESSFULLY!                             |
|        2 accounts from Deutsche Bank are now connected and actively syncing.      |
|                                                                                   |
|  LINKED SUMMARY                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | [Checking Account]  DE89 ... 3000   EUR 4,820.50   [90 Days Synced: 184 txns] |  |
|  | [Emergency Fund]    DE89 ... 3099   EUR 12,450.00  [90 Days Synced: 12 txns]  |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  NEXT STEPS                                                                       |
|  +-----------------------------------------------------------------------------+  |
|  |  (⚡) Create your first automation workflow                                  |  |
|  |      Set up rules to track salary deposits, balance thresholds, or alerts.  |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  [ View Accounts Dashboard ]                      [ Create Automation Workflow -> ]|
+-----------------------------------------------------------------------------------+
```

---

## 4. Reusable Bank Picker Component (`BankPickerComponent`)

To promote modularity across onboarding, settings, and multi-bank connection flows, the bank picker is architected as a self-contained, high-performance UI component.

### 4.1 Kotlin Compose Component Interface
```kotlin
@Composable
fun BankPickerComponent(
    providers: List<AspspProvider>,
    selectedCountry: String?,
    searchQuery: String,
    isLoading: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onCountrySelect: (String?) -> Unit,
    onProviderSelect: (AspspProvider) -> Unit,
    modifier: Modifier = Modifier
)
```

### 4.2 Key Features & Accessibility Standards
* **Fuzzy Instant Filter**: Searches simultaneously across Provider Name, Country Code, BIC/SWIFT, and ASPSP Group ID.
* **Keyboard Navigation**:
  * `Arrow Down` / `Arrow Up`: Navigates through search results list.
  * `Enter`: Selects currently focused institution.
  * `Escape`: Clears active search or closes component overlay.
* **Fallback Provider Avatars**: When an official SVG bank logo is missing or slow to load, the component dynamically renders a stylized two-letter brand avatar with a deterministic background tint generated from the provider's BIC string hash.

---

## 5. Interaction Patterns & Micro-Animations

1. **Staggered Entrance Animation**: When loading lists of banks or accounts, item cards enter with a staggered vertical rise (`translateY`: `12px` → `0px`) and fade-in opacity (`0.0` → `1.0`) with a `30ms` offset per item.
2. **Hover Light Trailing**: Interactive cards utilize subtle border-color acceleration on hover (`rgba(255, 255, 255, 0.08)` to `rgba(99, 102, 241, 0.40)` over `150ms ease-out`).
3. **Optimistic Interactive Controls**: Checkboxes and toggle switches reflect state changes instantly (`0ms` response) accompanied by a scale pulse (`1.0` → `0.95` → `1.0` transform).

---

## 6. Confirmation Popup Wording & Content Matrix

To uphold BankTeller's core promise — *"Your banking assistant that never acts without you"* — all microcopy across consent, warning, revocation, and payment approval dialogs uses clear, unambiguous language.

---

### Dialog 1: Consent Confirmation (Before External Bank Redirect)

* **Title**: Authorize Read-Only Bank Connection
* **Sub-title**: You are about to be redirected to **[Bank Name]** to authorize access.
* **Body Copy**:
  > BankTeller will request permission to read your account details, current balances, and transaction history (up to 90 days).
  > 
  > **Security Notice**: This connection is strictly read-only. BankTeller will **never** have access to your bank login credentials, and cannot initiate payments or move funds without your explicit authorization on every transaction.
* **Primary CTA**: Proceed to [Bank Name]
* **Secondary CTA**: Review Permissions

---

### Dialog 2: Session Expiration & Re-Authorization Warning

* **Title**: Bank Connection Expired
* **Sub-title**: Access consent for **[Bank Name]** reached its 90-day PSD2 security limit.
* **Body Copy**:
  > To ensure continuous sync for your active automated workflows, please re-authenticate your connection with **[Bank Name]**.
  > 
  > Your existing account settings, workflow rules, and historical transaction logs remain completely safe and untouched.
* **Primary CTA**: Re-Authenticate Connection
* **Secondary CTA**: Remind Me Later

---

### Dialog 3: Revoke Consent / Unlink Account Confirmation

* **Title**: Unlink [Bank Name] Account?
* **Sub-title**: Are you sure you want to disconnect **[Account Name - IBAN]**?
* **Body Copy**:
  > Disconnecting this account will:
  > • Pause **[N] active automation workflows** referencing this account.
  > • Stop background balance updates and transaction sync.
  > 
  > *Historical transaction data previously imported will remain in your local self-hosted database until manually purged.*
* **Primary CTA**: Disconnect & Pause Workflows *(Destructive styling: `#EF4444` background)*
* **Secondary CTA**: Keep Connected

---

### Dialog 4: Payment Approval Modal (Core Safety Principle)

* **Title**: Payment Approval Required
* **Sub-title**: An automated workflow has prepared a payment for your explicit authorization.
* **Payment Summary Card**:
  ```
  +-------------------------------------------------------------------+
  | WORKFLOW:       Salary Overflow Transfer                          |
  | AMOUNT:         € 450.00 EUR                                      |
  | FROM:           Checking Account (DE89 ... 3000)                  |
  | TO:             Savings Vault (DE89 ... 3099)                     |
  | REASON:         Monthly excess balance transfer                   |
  +-------------------------------------------------------------------+
  ```
* **Body Copy**:
  > **User Approval Guarantee**: BankTeller never moves funds automatically. Review the transfer details above carefully before authorizing submission to your bank.
* **Primary CTA**: Authorize & Execute Payment *(Accent styling: `#10B981` Emerald)*
* **Secondary CTA**: Reject Payment Request
