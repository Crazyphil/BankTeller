---
name: agent-driving
description: Drive the running BankTeller app in a browser as an agent — reach the app, pierce the Compose viewport shadow root, read the a11y overlay's nodes, and click them to execute real actions. Use when an agent needs to interact with, verify, or smoke-test the running web app without a human, when probing the accessibility overlay's projections, or when a task says "drive the app" / "agent-driving smoke".
allowed-tools: Bash(./gradlew:*), Bash(curl:*), Bash(docker:*), Bash(md5sum:*), Read(**), Grep(**), Glob(**)
license: MIT
compatibility: Compose Multiplatform ≥1.12 (wasmJs); any embedded/automated browser with a JS-evaluation path against the running app. **Tool-restricted subagents**: if your session has no JS-evaluation/browser tools of your own, do NOT improvise CLI headless browsers — ask the parent/peer session to execute the probe calls on your behalf and hand you the exact observed values. This prerequisite applies to sections 2–4 below: they assume an evaluation channel exists — arrange one before starting.
metadata:
  author: bankteller
  version: "1.0"
---

# Agent-driving: reading and driving BankTeller in a browser

Every method in this skill was tested against the running app in November 2026
(including a full login typed and submitted through this path). Follow it cold;
do not re-derive it.

## 1. Get the app running

- **Container loop (best, matches the deployment artifact):**
  `./gradlew :server:containerUp` → app at **http://localhost:8080** (`.env`'s
  `SERVER_PORT`, default 8080). Recreates the container only on image change; after
  rebuilding the image, `docker compose down && docker compose up -d` to guarantee the
  container runs the NEW image (containerUp's compose call has been observed not to
  recreate — verify with the `web.js` hash: `md5sum app/web/build/dist/wasmJs/productionExecutable/web.js`
  must equal `curl -s http://localhost:8080/web.js | md5sum`).
- **Backend quick loop:** `./gradlew :server:run` (port 8080, own `./data/bankteller.db`).
- Login credentials come from `.env` (`AUTH_USERNAME` / `AUTH_PASSWORD`); login is
  `POST /api/login` JSON `{"username":…,"password":…}` (session cookie). `/api/me`
  returns 200 when authenticated.
- Theme-mode persistence convention: System keeps NO localStorage key (absence
  = System), Light stores `bankteller-theme = "light"`, Dark stores `"dark"` —
  assert on the key's ABSENCE for System, never a value.

## 2. Reach the a11y overlay (the golden path)

The app is a Compose-for-Web canvas inside a `ComposeViewport` custom element with an
**OPEN shadow root**:

```js
// Locate the shadow-host and pierce in ONE step:
const host = [...document.querySelectorAll('*')].find(e => e.shadowRoot?.querySelector('#cmp_a11y_root'));
const root = host.shadowRoot.querySelector('#cmp_a11y_root');   // the a11y overlay
```

- **WASM boot takes seconds** — the overlay may not exist right after load. Wait
  for it with ONE bounded in-page wait loop (the polling pattern below); don't
  conclude "nothing exposed" too early.

**Polling pattern — used everywhere this document says "poll" or "wait":** ONE
`browser.evaluate` call containing an async loop that returns when the
condition is met (or a bound expires). The agent sends the command once and
reads one result. NEVER re-send the same probe from the agent side in a loop —
each round-trip costs tokens and latency for nothing.

```js
// browser.evaluate — returns 'ready' when the overlay exists, null after ~20s
(async () => {
  for (let i = 0; i < 40; i++) {
    const host = [...document.querySelectorAll('*')].find(e => e.shadowRoot?.querySelector('#cmp_a11y_root'));
    if (host) return 'ready';
    await new Promise(r => setTimeout(r, 500));
  }
  return null;
})()
```

- Overlay nodes are plain `div`s carrying `role`, `aria-label` and inline
  `position/white-space/left/top/width/height` styles (`getBoundingClientRect()`
  works too — sizes come from `setSizeAndPosition` in the bridge).
- The overlay mirrors ONLY declared semantics: nodes with `contentDescription` get
  `aria-label`; `Role` → `role` (OnClick nodes project `role="button"` even when
  another role is declared — see §6); `collectionInfo` → `role="list"`.
  NO state attributes (`aria-selected`, `aria-current`, `aria-pressed`,
  `aria-disabled`, `aria-checked`, `aria-expanded`) project at this bridge, and
  node-level `liveRegion → aria-live` does not materialize on the DOM either —
  do not probe for either; the container's hardcoded `aria-live="polite"` is
  the tree's ONLY live region.
  Plain text projects geometry + text but no label. The container itself is
  `position:absolute; width/height:100%; opacity:0; pointer-events:none`.

## 3. Read a node

```js
const nodes = [...root.querySelectorAll('div')].map(n => ({
  role: n.getAttribute('role') || '-',
  label: n.getAttribute('aria-label') || '-',
  sel: '-',  // state attrs never project at this bridge (1.13-gated)
  text: (n.textContent || '').slice(0, 50),
  rect: (r => `${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.width)}x${Math.round(r.height)}`)(n.getBoundingClientRect()),
}));
```

Buttons are found by `role="button"` + label/text; textboxes by `role="textbox"` +
`aria-label` (fields carry their label as name since the semantics change).

## 4. Drive an action

**Overlay-node click (preferred, no geometry needed):**

```js
const btn = [...root.querySelectorAll('div')].find(n => n.getAttribute('aria-label')?.startsWith('Theme mode'));
btn.click();   // executes the REAL composable action — verified: theme cycles & persists to localStorage['bankteller-theme']
```

Theme-mode persistence convention: `light`/`dark` are stored as values;
System = **the key is absent** from `localStorage['bankteller-theme']` (no override),
so a null read after a cycle means "System", not "lost".

**Canvas pointer events (when a node has no onClick semantics or you need coordinate dispatch):**
the canvas accepts full `PointerEvent` sequences (`pointerdown` → `pointerup` with
`pointerId: 1, pointerType: 'mouse', isPrimary: true, composed: true` + the button
geometry from the overlay node's rect).

**Typing into text fields** (the bridge's backing-input protocol — the ONLY reliable
path; typing raw key events char-by-char without verification causes DOUBLE text):

1. Click the field's overlay node → the hidden `input.compose-backing-field`
   materializes inside the same shadow root next to the canvas.
2. `input.focus()`, then set the full value and dispatch BOTH events:
   ```js
   input.dispatchEvent(new InputEvent('beforeinput', { inputType: 'insertText', data: text, bubbles: true, cancelable: true, composed: true }));
   input.value = text;
   input.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: text, bubbles: true, composed: true }));
   ```
   (`beforeinput` is REQUIRED — the bridge's `NativeInputEventsProcessor` registers
   it; a bare `input` event is ignored.)
3. Verify via the mirror with one bounded in-page wait (§2's polling pattern —
   the mirrored `textContent` updates after the debounce; password fields show dots).
   One backing input serves whichever field is focused — click per field.

The overlay mirror lags composition by a debounce (~hundreds of ms) — read it
through a bounded in-page wait, never a single fire-and-read.

## 5. Verified environment quirks (the traps — read before concluding something is broken)

1. **Browser Tab key is dead** — no DOM tab stops for canvas elements (framework
   gap, WCAG-verified verdicts). Focus does not travel; do not try to Tab into the
   app. (Field focus works via the overlay clicks above.)
2. **CDP accessibility snapshots are useless here** — they show empty generic nodes
   (opacity-0 + shadow-root pruning). The shadow-root overlay in §2 is THE truth.
3. **HEAD requests 404 on static assets** — always GET (e.g. `curl` health checks).
4. **Occlusion frame stalls (a browser-platform fact).** Chromium does not
   deliver `requestAnimationFrame` to hidden/occluded windows, and the Compose
   frame clock waits on rAF — an app in a hidden pane freezes at the last
   composed frame while its JS, network, and state stay alive (probes, fetch,
   and API calls respond normally; only rendering and with it the a11y overlay
   sync stall). If the tab was recently visible, one cheap remedy is a
   frame pump — ONE `evaluate` dispatching a few canvas pointer events
   (`pointerdown`+`pointerup`, §4's flags) in a loop with short in-page
   waits, then reading the tree at the end. General guidance, in order of
   preference:
   - Make the tab/window actually visible before relying on rendered state —
     there is no substitute for a visible surface when frames are needed.
   - Prefer frame-independent state: API responses, localStorage, and the
     last-composed overlay tree are readable without pumping frames; anchor
     time-critical assertions there.
   - If a tab produces no frames for an extended period under pump attempts
     and clicks hang, treat the SURFACE as wedged. STOP: do not open fresh
     tabs or keep probing — a wedged renderer does not recover on its own,
     and any VERIFICATION STATE HELD ONLY IN THE PAGE (in-memory view-model
     state like an un-dismissed caveat panel) is lost with it. End the turn
     and tell the user: the tab froze because the OpenCode window was
     backgrounded/occluded (a browser-platform behavior — Chromium stops
     delivering frames to hidden windows); ask them to make the window
     visible, and if it keeps recurring on Linux/Wayland, suggest relaunching
     OpenCode with `--ozone-platform=x11`, which keeps backgrounded tabs
     rendering. Do NOT diagnose app defects from a wedged tab: the freeze
     is a probing artifact, not app behavior.

5. **Double-text trap**: the backing input syncs BOTH ways; writing values while not
   verifying the compose state (mirror text) per step duplicates input. Always
   clear-verify → type → verify via the mirror.
6. **Compact-width probing** — the browser tools expose no window/viewport-resize
   primitive, and CSS-side reduction does NOT work: the Compose viewport re-measures
   nothing on synthetic `resize` events, so `zoom` on `html`, constraining the host
   element, or `Object.defineProperty` overrides of `window.innerWidth` leave the
   app laid out at its boot-time width. What works: a container page embedding the
   app in a smaller `<iframe>` (e.g. 700×700 for the 768dp Settings breakpoint;
   `evaluate` takes the frame's `frameID` — the shadow-root golden path works
   unchanged inside the frame). Serving the harness:
   - **`./gradlew :server:run` loop**: write the harness HTML anywhere under
     `server/build/generated/resources/static/` — the dev server serves that
     directory live as `http://localhost:8080/<file>`, same origin as the app,
     cookies included. One `browser.tabs.open`, no extra server.
   - **Container loop only**: the static tree is packaged in the image, so serve
     the harness from a one-off local server instead:
     `python3 -m http.server 8123 -d <harness-dir>` and open
     `http://localhost:8123/harness.html`.
   Two traps: (a) NEVER serve the harness from `file:` — that makes
   `localhost:8080` a third-party frame, Chromium blocks its session cookie,
   and the app stays unauthenticated no matter how often you POST /api/login;
   (b) the iframe's render loop stalls like an occluded tab — pump pointer
   events (§5, item 4) or make the tab visible (tabs.focus) before reading
   geometry.

## 6. Bridge behavior at this Compose version (CMP 1.12.2)

These are the CURRENT projections — fact until the Compose version in
`gradle/libs.versions.toml` changes; the dependency-update change that bumps
it updates this section (this skill's living-document rule), so an agent can
trust what is written here without re-verifying.

- The a11y container `#cmp_a11y_root` sizes to the canvas (was 0×0 before
  CMP 1.12) and carries hardcoded `aria-live="polite"` — the ONLY live region
  in the exposed tree. Every overlay content change announces politely
  through it; the node-level `liveRegion→aria-live` mapping does not exist
  in the 1.12 bridge.
- Collections → `role="list"`; headings → `role="heading"` (no `aria-level` —
  Compose `Heading` is level-agnostic at this bridge).
- OnClick nodes project `role="button"` — including nodes that declare other
  roles (`Role.Tab` projects as button). No `aria-selected`/`aria-disabled`
  projections exist in this bridge: control state reaches AT users only via
  labels and visuals; do not probe for them.
- Keyboard traversal is dead (see quirk §5.1) — the framework wires no DOM tab
  stops for canvas elements at this version.

## 7. Limits (honest edges)

- Overlay clicks execute anything with onClick/listener semantics; focus-based-only
  flows (e.g. IME action chains) go through the backing-input protocol in §4.
- Anything needing an authenticated + onboarded state (dashboard, settings) requires a
  real bank connection first (sandbox flow) or an already-onboarded server DB.
