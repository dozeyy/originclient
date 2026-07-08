# Orbit Launcher — Memory Log

Append-only log of real outcomes, decisions, and root-caused fixes. Not loaded
every session — read at session start alongside `./CLAUDE.md`.

---

## 2026-07-06 — Project init
- Defined via init interview. Full original product spec (all pages, mod
  system, crash system, file layout) captured in the initiating conversation;
  distilled into phased roadmap in `./CLAUDE.md`.
- Decisions locked in:
  - Stack: C# / .NET 8 / WPF (chosen over Electron/Tauri/WinUI3 for startup
    speed + native fit with Deskify XAML theming — performance-first priority)
  - Mojang/Fabric/Forge handling via CmlLib.Core rather than hand-rolled
    manifest/download/launch-arg logic
  - Auth via MSAL (MSA device code) → Xbox Live → XSTS → MC token chain;
    tokens encrypted at rest via Windows DPAPI (naturally device-bound)
  - Scope: phased roadmap, not a full upfront scaffold — Phase 1 is the
    account+launch core loop, mods/loaders in Phase 2, crash system in Phase 3
  - Branding: original monochrome "orbit ring + dot" mark, no existing assets
- Blocker for real OAuth testing: Azure AD app registration (client ID +
  redirect URI) not yet created — Will needs to do this at
  portal.azure.com → App registrations before Phase 1's auth step can be
  tested against a live Microsoft account. Everything else in Phase 1 can
  proceed without it.

## 2026-07-06 — Phase 1 shell built (app + theme + nav + settings)
- Scaffolded solution: `OrbitLauncher.sln` + `src/OrbitLauncher.App`
  (WPF, net8.0-windows).
- Deskify theme ported to WPF ResourceDictionaries under `Theme/`: Colors,
  Typography (Bahnschrift), Spacing (8px grid + corner-radius tiers), Buttons
  (press/hover motion), Inputs, Brand (Orbit ring+dot mark), Icons (custom
  deterministic line-icon geometries — Home/Settings/Account/Minimize/Close;
  avoided guessing at a third-party icon font's codepoints).
- Root cause hit and fixed: setting `IsChecked="True"` on a nav ToggleButton
  directly in XAML fires its `Checked` handler mid-`InitializeComponent`,
  before later-declared named elements exist yet → `NullReferenceException`
  on first launch. Fix: set initial nav state in code-behind after
  `InitializeComponent()` completes, not in XAML.
- Custom window chrome via `System.Windows.Shell.WindowChrome` (not
  `AllowsTransparency`, to keep hardware acceleration) — borderless title bar
  with drag region, minimize/close, and an account-avatar button that toggles
  a right slide-out panel (scrim + `TranslateTransform` slide, not yet wired
  to real accounts — see Azure blocker above).
- Version manager uses CmlLib.Core 4.0.6's `MinecraftLauncher` +
  `GetAllVersionsAsync()` (NOT `CMLauncher`, which doesn't exist in v4 — API
  changed across major versions; confirmed the real v4 surface by reflecting
  over the installed package in a throwaway console project rather than
  guessing from older docs/tutorials).
- Settings persist to `%LOCALAPPDATA%\OrbitLauncher\settings.json` (RAM,
  resolution, install path, selected version) via a plain
  `System.Text.Json`-backed `SettingsStore` — no framework, matches
  "simplicity" priority.
- Computer-use screenshot tooling can't target a `dotnet run` dev process
  (it only resolves Start-Menu-registered app names), so visual QA of WPF
  builds during dev currently relies on Will eyeballing the live window
  himself rather than an automated screenshot pass.
- Next up: launch pipeline (JVM args + RAM/resolution → process launch).
  Real account auth stays blocked until Will completes the Azure AD app
  registration noted above.

## 2026-07-06 — Visual/UX revision: chromeless center-focus redesign
- Will pivoted the UI direction mid-build: away from the Deskify nav-rail/
  card layout toward a minimal, center-focused launcher (big Play button,
  animated orbital background, chromeless window) — confirmed via plan mode
  before touching code since it was a full visual-layer replacement.
- Two explicit brand decisions confirmed with Will: glow/rings stay
  **monochrome** (no new hue), and secondary nav (Mods/Settings) lives as a
  **floating left-edge icon column**, not a rail or bottom bar.
- Core layer (`OrbitPaths`, `SettingsStore`, `VersionManager`, `MinecraftAccount`)
  was reused untouched — only the UI/Theme layer and `SettingsPage` additions
  were new. Confirms the Phase 1 Core/UI split was the right call: a full
  visual pivot didn't require touching any of the backend logic.
- Technical corrections made while building (worth remembering for future
  WPF glow/animation work in this project):
  - `DropShadowEffect`/glow can't be applied inside a `DrawingImage` resource
    (Effect is a Visual-level property, not part of the Drawing object model)
    — the original single-ring+dot mark was a `DrawingImage`; the new 3-ring
    mark had to become a real `UserControl` of `Ellipse` shapes instead.
  - Rotating a perfect circle is a visual no-op (uniform stroke looks
    identical at any angle) — rings need to be tilted ellipses (or dashed)
    for rotation to actually read as motion. Went with tilted ellipses
    (atom/orbit pattern) for both the static mark and the animated
    background, reusing the same visual language at different scales.
  - Only animate `RenderTransform`/opacity, never re-run layout — all 4
    background rings animate just `RotateTransform.Angle`, geometry/blur/
    opacity stay static per ring, so ring count doesn't add per-frame cost.
  - Style `Setter.Value` objects (like a shared `DropShadowEffect`) are
    reused across every control using that style — animating one shared
    instance's property would animate it everywhere at once. `Button.PlayHero`
    gets its own literal (non-shared) `DropShadowEffect` for this reason
    (moot today since there's only one Play button, but correct regardless).
  - `System.Drawing`/Pillow-free `.ico` generation: rendered the mark via
    WPF `RenderTargetBitmap` → PNG per size (16/32/48/256), hand-wrote the
    ICO container (standard format, supports embedded PNG frames) — done via
    a throwaway STA console project (needed `[STAThread]` explicitly; WPF
    elements throw if constructed off an STA thread, which top-level
    statements don't give you by default).
  - JVM performance preset uses "Aikar's flags" — the real, widely-published
    G1GC tuning set from the Minecraft server community — for Performance
    mode; not a fabricated flag set.
- Deferred (flagged in plan, not built yet): actual GPU-preference registry
  write for hybrid-GPU laptops (`HKCU\...\UserGpuPreferences`) — real,
  documented Windows mechanism, scoped as a Phase 2 item, not blocking.

## 2026-07-06 — UI bug-fix pass (post-redesign)
Will filed a list of layout/interaction bugs against the new redesign. Root
causes found and fixed:
- `Button.NavItem`'s `ContentPresenter` hardcoded `HorizontalAlignment="Left"`
  instead of binding `TemplateBinding HorizontalContentAlignment` — sidebar
  icons rendered packed to the left edge of their own hover-highlight square
  instead of centered. Same bug existed in `Input.ComboBox`'s template; fixed
  both. Also added the missing hover-transition animation on `Button.NavItem`
  (was an instant `Setter`, now animates like every other button style).
- **Real, higher-impact bug**: `Button.Base`, `Button.Primary`, and
  `Button.PlayHero`'s `ControlTemplate`s all set a `Padding` property via
  `Setter` but never bound it to the `ContentPresenter`'s `Margin` — so
  `Padding` did nothing on any button using those templates (only
  `Button.NavItem` had it wired correctly). This is why "Add Microsoft
  Account" looked small — my earlier size estimate assumed padding was
  applied and never checked the template itself. Fixed all three at once.
  Lesson: when estimating a control's rendered size, check the actual
  `ControlTemplate`, don't infer from the `Padding` Setter's presence alone.
- `PageHost` had a top inset (from the earlier mark-overlap fix) but no left
  inset — the floating left-edge icon column (x:16-52) overlapped Settings'
  left-aligned content (started at x:32). Scoped the extra left margin to
  `SettingsPage` itself (not a global `PageHost` change) since Home/Mods
  center their own content and a global left shift would've thrown that off.
- Version dropdown "empty" report: verified live against the real Mojang
  manifest (102 valid releases, filter logic correct) — not reproducible in
  that moment. But confirmed a real latent bug either way: the load code
  only caught `HttpRequestException`, so a timeout/DNS blip throws a
  different exception type that got silently swallowed, leaving the dropdown
  blank with zero user-facing indication. Widened the catch and added a
  disabled placeholder ("No versions found — check your connection") so a
  future failure is visible instead of silent.
- Also fixed (from Will's RAM report earlier the same session): RAM slider's
  `Maximum` was hardcoded to 16384 in XAML regardless of actual system RAM —
  added `Core/SystemInfo.cs` (real `GlobalMemoryStatusEx` P/Invoke) and wired
  both the slider max and the auto-default off real detected RAM.

## 2026-07-06 — MSA auth chain implemented (Azure AD registration done)
- Will completed the Azure AD app registration (client ID
  `de37d9e5-82d5-43a7-8f66-ebac788e8ba5`, personal Microsoft accounts only,
  public client flows enabled, redirect URI `http://localhost`).
- Built `Core/Auth/MicrosoftAuthenticator.cs`: full PKCE authorization-code
  flow (browser + loopback `HttpListener` on a dynamically-chosen free
  port — the registered bare `http://localhost` redirect matches any port,
  a documented Microsoft behavior for native/public client loopback
  redirects) → Xbox Live user token → XSTS (scoped to
  `rp://api.minecraftservices.com/`) → Minecraft access token → profile
  (username/UUID). Returns a `CmlLib.Core.Auth.MSession` directly, so it
  slots straight into the existing `LaunchProfileBuilder` without a
  translation layer.
- Key correctness decision: uses the `/consumers` authority
  (`https://login.microsoftonline.com/consumers/...`), NOT Will's Directory
  (tenant) ID — personal MSA accounts don't belong to that org tenant, and
  using it would be the wrong endpoint for a "personal accounts only" app.
  Also added PKCE (code_verifier/code_challenge) even though the user's
  original spec didn't ask for it — public clients can't hold a secret, and
  Azure AD effectively requires PKCE on the code exchange now; skipping it
  would likely make the token exchange fail against the real endpoint.
- Surfaces the well-known XSTS error codes (2148916233 = no Xbox profile,
  2148916238 = child account needs Family group, 2148916235 = region
  restriction) as readable messages instead of a raw error blob.
- No external OAuth/HTTP package needed — `HttpListener`, `HttpClient`,
  `System.Text.Json`, all BCL.
- Not done yet: wiring `AccountSwitcherPanel`'s "Add Microsoft Account"
  button to actually call `SignInAsync()`, and DPAPI-encrypted persistence
  of the resulting session/account across restarts (button is still
  disabled). Natural next step.
- Also compacted `SettingsPage` — its content genuinely overflowed the
  window's `MinHeight` (600px): measured ~595px of content against ~472px
  available. Tightened card/field spacing (~128px saved) and switched the
  scrollbar to `Hidden` rather than `Disabled`, so wheel-scroll still works
  as a safety net if a future addition pushes past budget again, instead of
  silently clipping content.

## 2026-07-06 — End-to-end sign-in + launch wired up
- Added refresh-token capture to `MicrosoftAuthenticator` (requested via
  `offline_access`, which was already in scope) plus `SignInSilentlyAsync` —
  redoes MSA refresh → XBL → XSTS → MC → profile without a browser. Split
  the shared back half of the chain into `CompleteMinecraftAuthAsync` so
  `SignInAsync`/`SignInSilentlyAsync` don't duplicate it.
- New `Core/Accounts/{StoredAccount,AccountStore}.cs`: accounts persist to
  `accounts.json` under the Orbit root; the MSA refresh token is DPAPI-
  encrypted (`System.Security.Cryptography.ProtectedData`, CurrentUser
  scope — added as a NuGet package; it's not in the BCL even on
  `net8.0-windows`). `TryUnprotectRefreshToken` returns null instead of
  throwing on a corrupt/foreign-profile blob, so a stale account just
  prompts re-login instead of crashing.
- `AccountSwitcherPanel`: "Add Microsoft Account" now actually calls
  `SignInAsync()`, persists the result, shows a busy state during sign-in
  and a visible error message on failure (was previously a permanently
  disabled button). Account rows are clickable to switch which one is
  active; an "ACTIVE" label + background tint show the current selection.
  Fires `AccountsChanged`, which `MainWindow` forwards into
  `HomePage.RefreshAccountState()` so Home updates immediately without
  navigating away and back.
- `HomePage`: Play button now does a real launch — silent token refresh
  (rotating and re-persisting the refresh token each time, since Microsoft
  rotates them on use), builds the launch options via the existing
  `LaunchProfileBuilder`, and calls a new
  `VersionManager.InstallAndBuildProcessAsync` (installs the version via
  CmlLib.Core if needed, builds the process, caller starts it) then
  `Process.Start()`. Status text reflects each stage (signing in →
  launching → launched, or a specific error) instead of a generic spinner.
- **Not yet tested against a real Microsoft account** — everything compiles
  and the app runs without crashing, but nobody has actually clicked through
  a live sign-in. That's the natural first real-world test.

## 2026-07-06 — First real sign-in attempt: 2 real bugs found and fixed
Will actually clicked through the flow. Browser login worked (got a real
auth code back), but Xbox Live rejected the exchange.
- Response HTML had `ContentType = "text/html"` with no charset — browser
  guessed the wrong one and rendered the em dash as `â€"` mojibake. Added
  `; charset=utf-8`.
- First error report came back as literally "Xbox Live authentication
  failed:" with nothing after the colon — traced to `DescribeFailure`-less
  code building the message as `$"...failed: {json}"` where `json` was an
  empty string, producing a dangling colon. Added `DescribeFailure()` (now
  used at every failure site in the chain) that always includes the HTTP
  status code and explicitly says "(empty response body)" when there's
  nothing else — never again just a bare colon.
- With that fix, the real error surfaced: `HTTP 400 BadRequest (empty
  response body)` from `user.auth.xboxlive.com/user/authenticate`. Verified
  the actual root cause with a throwaway probe rather than guessing:
  `HttpClient.PostAsJsonAsync` with no explicit `JsonSerializerOptions`
  silently camelCases every property (`Properties` -> `properties`,
  `AuthMethod` -> `authMethod`, etc.) — confirmed by serializing the exact
  payload shape and printing the JSON. Xbox Live's API requires the exact
  PascalCase keys from its docs and returns a bodiless 400 if they don't
  match exactly, which is exactly what was happening. Fixed by passing a
  shared `JsonSerializerOptions { PropertyNamingPolicy = null }` to every
  `PostAsJsonAsync` call in `MicrosoftAuthenticator.cs` (XBL, XSTS, and MC
  login — MC login's `identityToken` field happened to already be
  camelCase so it wasn't actually broken, but made consistent anyway).
- Lesson for future HTTP-calling code in this project: never rely on
  `PostAsJsonAsync`'s default serializer options when the target API has
  case-sensitive/exact-casing requirements — always pass explicit options
  (or use typed DTOs with `[JsonPropertyName]`, as already done for every
  response type in this file).
- Not yet re-tested end to end after this fix — next real test.

## 2026-07-06 — Second sign-in attempt: "Invalid app registration" (403), unresolved at Minecraft login step
MSA -> Xbox Live -> XSTS all now succeed. Failure moved to the last step:
`api.minecraftservices.com/authentication/login_with_xbox` returns
`403 Forbidden {"errorMessage":"Invalid app registration, see
https://aka.ms/AppRegInfo"}`.
- Ruled out via direct evidence: Will's Azure Portal screenshot shows the
  redirect URI is correctly registered as "1 public client" (0 web, 0 spa) —
  the "wrong platform type" theory is wrong, not a config mistake there.
- Tried to fetch Microsoft's own AppRegInfo doc 5x across 3 methods
  (WebFetch x3, curl x2) — all failed/timed out, looked like bot-detection
  on the Zendesk-hosted help page, not a real connectivity problem (other
  hosts, incl. GitHub/Maven Central/Fabric's maven, all reachable fine).
- Leading (unconfirmed) hypothesis: this Azure app registration was created
  very recently in this same session — community-reported propagation
  delay between Azure AD and Xbox/Minecraft's backend validation can
  produce exactly this error on a brand-new registration even when config
  is correct. No further Azure changes recommended; wait and retry.
- **Still unresolved as of this entry** — next real test should just be
  retrying sign-in after some time has passed, no code changes pending.

## 2026-07-06 — Launch pipeline: process lifecycle capture + GPU preference
Picked up two items while waiting on the Azure propagation theory above:
- `HomePage.StartWithLifecycleCapture` (was: `process.Start()` and forget)
  now redirects stdout/stderr to a timestamped log under
  `%LocalAppData%\OrbitLauncher\logs\`, and reports the real exit code back
  to the status line on `Exited`. Closes the last open Phase 1 roadmap item
  ("basic process lifecycle"). This is the raw data Phase 3's crash-cause
  detection will read later — no parsing yet, just real capture.
- New `Core/GpuPreference.cs` writes the
  `HKCU\Software\Microsoft\DirectX\UserGpuPreferences` registry hint
  (`GpuPreference=2`, same mechanism Windows Settings' own per-app graphics
  toggle uses) for the resolved `javaw.exe` path, applied only when
  Performance mode is selected. Uses `process.StartInfo.FileName` after
  CmlLib.Core builds the process — correct whether Java comes from a
  Mojang-bundled runtime or a system install, no separate Java-detection
  logic needed.

## 2026-07-06 — Orbit Client: new Fabric mod sub-project (custom client wrapper)
Will asked to start on the actual "custom client wrapper" from the original
brief (Lunar/Feather-style): simplified title screen, branded loading
screen, and a Right-Shift mod-menu overlay of QoL mods. This is a real
second toolchain, not an extension of the WPF launcher.
- **Decisions (confirmed with Will):** Fabric (best fit for 1.21.1's
  performance-mod ecosystem/Mixin tooling), pinned to Minecraft 1.21.1,
  lives in this same repo at `src/OrbitClient.Mod/` (own Gradle build,
  fully separate from the .NET solution), starter feature set = all four
  proposed (Zoom+Freelook, HUD info, Toggle sprint/sneak, Fullbright).
- Scaffolded from FabricMC's official `fabric-example-mod` repo's `1.21.1`
  branch (shallow-cloned, then gutted/renamed) rather than hand-writing
  Gradle/wrapper/Loom config — guarantees the Gradle wrapper, Loom version,
  and mappings actually match this MC version instead of guessing. Uses
  `loom.officialMojangMappings()` (Mojmap), not Yarn.
- Mixins implemented: `TitleScreenMixin` (rebuilds the title screen into
  Singleplayer/Multiplayer/Settings/Mod Settings/Quit), `LoadingOverlayMixin`
  (draws the Orbit wordmark over vanilla's resource-loading screen without
  touching the real progress logic), `GameRendererMixin` (zoom FOV divisor
  while the zoom key is held), `MouseHandlerMixin` + `EntityViewAngleMixin`
  (freelook: redirects `Entity.setYRot/setXRot` calls from
  `MouseHandler.turnPlayer` into a camera-only accumulator while held,
  entity's real rotation freezes, `getViewYRot/XRot` overridden to render
  the camera-only angle for the local player).
- Toggle sprint/sneak and fullbright deliberately avoided further Mixins:
  toggle sprint/sneak is a post-tick override in `OrbitClientMod` (re-apply
  our own boolean each tick after vanilla's own input processing already
  ran) and fullbright is a gamma-option override
  (`options.gamma().set(16.0)`) — both pure public API, no private-field
  guessing.
- `OrbitModMenuScreen` (Right Shift) + `OrbitToggleButton`: fully
  custom-drawn (not vanilla's button texture) to match the launcher's own
  Deskify monochrome look; `isPauseScreen()` returns false so it doesn't
  pause singleplayer, per the "overlay" brief.
- **Known unverified risk, flagged honestly rather than guessed at:**
  fullbright's gamma override may be clamped back to 1.0 by this version's
  `OptionInstance` validator (can't confirm without an in-game check);
  freelook pitch clamps relative to the angle freelook was engaged at, not
  the live camera angle, because vanilla's own clamp runs before the
  redirect sees the value.
- Verification in progress: running the real `./gradlew build` against the
  actual 1.21.1 Mojmap jar — this is the actual check for every Mixin
  target/class name guessed above (compile-time validated, not guessed at
  in this log). Result not yet known as of this entry.

## 2026-07-06 — Orbit Client mod: verified compiling, one real bug found and fixed
Ran `./gradlew build` for real against the 1.21.1 Mojmap jar (per the
"verify, don't guess" rule — every class/Mixin target in the previous entry
was written from knowledge, not looked up, so this was the actual check).
- One error, everything else compiled clean first try: `OptionsScreen` isn't
  under `net.minecraft.client.gui.screens` directly, it's one level deeper
  at `net.minecraft.client.gui.screens.options.OptionsScreen`. Found the
  real location by listing the Loom-cached mapped client jar directly
  (`unzip -l` + `javap` on the extracted `.class`) rather than guessing
  again — confirmed constructor is `OptionsScreen(Screen, Options)`, exactly
  as written, just the wrong package. Fixed the import, rebuilt clean.
- Confirms the redirect-based freelook mixin, the view-angle override, the
  zoom FOV mixin, the title/loading screen mixins, and the gamma()/
  OptionInstance API all match this version's real Mojmap classes as
  written — no further changes needed for it to compile.
- **Still not run in-game** — compiling clean proves the Mixin targets exist
  and signatures match, not that fullbright reaches true full brightness or
  that freelook feels right. That needs an actual launch + play-test, which
  is still gated on the unresolved Azure "Invalid app registration" issue
  for real account sign-in (or a dev-run via `./gradlew runClient` with an
  offline/dev account, which doesn't need the launcher's auth chain at all
  and could unblock visual testing sooner).

## 2026-07-06 — Renamed the in-game mod: Orbit Client -> Origin Client
Will asked to rename the client (not the launcher — "Orbit Launcher" keeps
its name; only the in-game mod is now "Origin Client").
- Renamed: project folder `src/OrbitClient.Mod/` -> `src/OriginClient.Mod/`,
  Java package `com.orbit.client` -> `com.origin.client`, every class
  (`OrbitClient`, `OrbitClientMod`, `OrbitFeatures`, `OrbitConfig`,
  `OrbitKeyBindings`, `OrbitHud`, `OrbitFreelookState`, `OrbitModMenuScreen`,
  `OrbitToggleButton` -> `Origin*`), mod id `orbitclient` -> `originclient`
  (fabric.mod.json, mixins config file name + its internal package field,
  asset folder `assets/orbitclient` -> `assets/originclient`, lang keys,
  Gradle `maven_group`/rootProject name), and the loading screen's on-screen
  wordmark text ("ORBIT CLIENT" -> "ORIGIN CLIENT").
- Renaming the folder hit a real Windows quirk: `mv` failed twice with
  "Device or resource busy" even after `./gradlew --stop`. Root cause:
  this session's own bash shell still had its cwd inside the old folder
  from the earlier `gradlew --stop` command — Windows won't let a directory
  be removed while any process (including the calling shell itself) has it
  as cwd. Fixed by `cd`-ing out to the project root first, then `rm -rf`
  worked. Worked around the original rename failure by `cp -a` to the new
  name first, then deleting the old one once safely outside it.
- Rebuilt after the rename (`./gradlew build`) — clean, confirming the
  rename didn't break anything (no leftover `com.orbit.client` references
  anywhere in source/resources; only remaining "Orbit" hit anywhere is the
  intentional one in `fabric.mod.json`'s description, which correctly still
  refers to "Orbit Launcher").
- Open question carried into `./CLAUDE.md`: whether Origin Client gets its
  own visual mark or reuses the launcher's tri-ring Orbit logo on the
  loading screen — not decided, current loading screen is text-only.

## 2026-07-06 — Full rebrand: Orbit -> Origin (launcher + mod + solution)
Will clarified the rename should cover everything, not just the mod:
"the launcher is origin launcher and the client is origin client."
- Renamed the WPF launcher project: `src/OrbitLauncher.App/` ->
  `src/OriginLauncher.App/`, namespace `OrbitLauncher.App` ->
  `OriginLauncher.App`, `OrbitLauncher.App.csproj` -> `OriginLauncher.App.csproj`
  (assembly/exe name follows automatically from the SDK-style csproj's file
  name — no separate AssemblyName override existed to update).
  `OrbitPaths` -> `OriginPaths` (including the actual app-data folder name
  string, `%LocalAppData%\OrbitLauncher` -> `%LocalAppData%\OriginLauncher`
  — checked first and confirmed no existing data at the old path, so no
  migration was needed), `OrbitMark`/`OrbitBackground` -> `Origin*`,
  `Assets/orbit.ico` -> `origin.ico`, "Orbit Launcher" window title/branding
  text -> "Origin Launcher", `Effect.OrbitGlow(Soft)` brush keys -> `Origin*`.
  Root solution file `OrbitLauncher.sln` -> `OriginLauncher.sln` (including
  its internal project reference, which a plain folder/file rename doesn't
  touch on its own). Rebuilt clean, 0 warnings/errors.
- Also renamed the project root folder itself:
  `C:\Users\Will\Documents\Orbit Client` -> `Origin Client`. This required
  care: the classifier initially blocked it as an irreversible action needing
  more explicit confirmation even after a "yes" in chat (had to ask again in
  plain terms and get explicit "proceed"); then the first real attempt failed
  with "Device or resource busy" because this Claude Code session itself
  keeps the folder open as its active project directory for the session's
  lifetime (confirmed by the harness's own "Shell cwd was reset to
  C:\...\Orbit Client" message after the failed attempt) — that's
  infrastructure-level, not a process I can find/kill from inside the
  session. Told Will the practical path: rename it himself from outside a
  live session (close this session, rename via Explorer/PowerShell, reopen
  Claude Code pointed at the new path) — did not attempt to force it.
- Lesson carried over from the earlier mod rename: always check for and
  stop anything with an open handle (running app process, Gradle daemon)
  *and* `cd` out of the target directory before attempting a folder
  rename/delete on Windows — "Device or resource busy" is almost always one
  of those two causes, not a real permissions problem.
- Root cause of "everything still says Orbit" complaint: the earlier
  2026-07-06 rename only covered the in-game mod (per that request's exact
  wording at the time), not the actually-visible-and-running WPF launcher
  app, which is what Will was actually looking at when he noticed the
  leftover branding.

## 2026-07-06 — In-game UI framework expansion (Client Settings + Mods screen + master UI toggle)
Expanded the Origin Client mod's custom-UI framework: a shared theme/rounded-
rect primitive, a real Mods browser, tabbed client settings, and a master
kill-switch back to vanilla.
- `client/gui/OriginTheme.java` (new): single source of Deskify color tokens
  for the mod side + a cheap software rounded-rect fill (bulk via 3
  `GuiGraphics.fill` calls, corners via a small per-pixel circle test — only
  ~100 fills at the 6-10px radii used here). `OriginToggleButton` now draws
  through this instead of its own hardcoded hex constants.
- `client/gui/OriginSlider.java` (new): flat track+pill slider matching the
  toggle button's look. First real user: **Zoom FOV** is no longer a fixed
  `/4.0` divisor in `GameRendererMixin` — it's a configurable absolute FOV
  (`OriginFeatures.zoomFov`, default 30, slider range 5-50) applied directly
  when the zoom key is held.
- `client/gui/OriginModMenuScreen.java` renamed/rebuilt as
  **`OriginClientSettingsScreen`** — now tabbed (Performance / HUD & Render /
  Quality of Life via `OriginTabButton`, new). Switching tabs opens a fresh
  screen instance already on that tab rather than juggling a shared widget
  list.
- **Master switch**: `OriginFeatures.originUiEnabled` (default true). The
  settings screen's bottom button reads "Disable Origin UI" / "Enable Origin
  UI" depending on current state — it's a real toggle, not one-way.
  `TitleScreenMixin` and `LoadingOverlayMixin` both check this flag and, if
  false, return immediately at their injection point, leaving vanilla's own
  rendering/widgets untouched — confirmed this works by inspection of
  Minecraft's own behavior: `Minecraft.setScreen()` re-runs `Screen#init()`
  every time a screen becomes current (even a pre-existing instance), so
  toggling off and returning to the same `TitleScreen` re-triggers the mixin,
  which now just no-ops and leaves vanilla's freshly-added widgets alone —
  reverts instantly, no restart needed.
  Deliberately does **not** gate the Right Shift quick-menu keybinding or the
  individual feature mods (zoom/freelook/HUD/etc.) — those stay live even
  with the UI disabled, so there's always a way back into the setting that
  re-enables it. Without this the toggle would be a one-way lockout.
- **`LoadingOverlayMixin`** rewritten: was TAIL-inject text-only branding,
  now cancels vanilla's render at HEAD (when UI enabled) and draws a fully
  custom dark screen + wordmark + animated indeterminate progress bar. Uses
  `System.currentTimeMillis()` for the animation rather than reading
  `LoadingOverlay`'s real reload-progress fields — those aren't stable API
  and reading them via `@Shadow` would be guessing private field names per
  MC version; cancelling render only skips this frame's drawing, the actual
  `ReloadInstance` driving resource loading runs independently and is
  unaffected.
- New `client/mods/` package — real mod-folder scanning, not a stub:
  `OriginModScanner` reads `FabricLoader.getInstance().getGameDir()/mods`
  directly (the same folder the launcher's `VersionManager` already
  provisions per instance — no separate "Origin mods path" concept needed).
  Parses `fabric.mod.json` via Gson (id/name/version/description/icon, icon
  handles both a plain string and the `{"16":..., "32":...}` size-map form)
  and hand-rolls a minimal `mods.toml` reader for Forge/NeoForge (just the
  4 keys actually needed — a full TOML parser would be a new dependency for
  no real gain). `OriginModIcons` lazily decodes+registers a mod's icon
  texture only the first time it's actually drawn, cached per session.
  `OriginFolderOpener` uses plain `java.awt.Desktop#open` rather than
  `Util.getPlatform()` — that Minecraft API has shifted signatures across
  versions; `Desktop` has been stable since Java 6.
- `client/gui/OriginModsScreen.java` (new): left scrollable list (hand-rolled
  scroll/click/scissor-clip, not vanilla `ObjectSelectionList`, to keep full
  control of the Deskify styling) + right detail panel with an "Open Mods
  Folder" button. Title screen gained a 5th button, "Mods", wired to this.
- Verified for real: `./gradlew build` from `src/OriginClient.Mod` — clean,
  0 errors, including the Mixin refmap/remap step (so every `@Mixin` target
  used above is confirmed to exist in the real 1.21.1 Mojmap jar, not just
  assumed). One real compile error caught and fixed along the way:
  `AbstractSliderButton.renderWidget` is `public` in this Mojmap version, not
  `protected` — `OriginSlider` had to match that visibility.
- **Launcher side (C#)**: checked `VersionManager.cs` before writing anything
  new — Fabric/Forge install (via CmlLib.Core, no external browser) and
  per-version instance isolation (`instances/{version}/mods`) already exist
  and already satisfy "seamless internal dependency architecture, no browser
  redirects, auto-provisioned mod folder" from this session's request. Only
  gap found: the mods folder was only actually created on disk if a perf
  profile or OptiFine happened to populate it — for Fabric with no matching
  `PerformanceModCatalog` entry, or Forge without OptiFine, the folder never
  got created up front. Fixed with one unconditional
  `Directory.CreateDirectory(modsFolder)` when `loader != Vanilla`, before
  the loader-specific switch. Not yet full-rebuilt end-to-end in this
  session (the running launcher instance held its own `.exe` locked during
  `dotnet build`, which only blocked the final copy step — the C# itself
  compiled with no CS errors before that).
- Not done, flagged as future scope rather than attempted here: mods-screen
  drag-drop install / per-mod enable-disable toggle (still Phase 2 per
  `./CLAUDE.md`'s roadmap — this session only built the read-only browser +
  metadata scanner, which those features would sit on top of), and
  `instances/<version>_<loader>` folder naming (user's own spec suggested
  loader in the folder name; current isolation is per-version only, changing
  it would touch a working system and wasn't asked for directly this
  session — worth a real decision before doing it, not a silent rename).

## 2026-07-06 — Bundled Origin Client jar into the launcher's own build output
Will's ask: everything from today's mod UI work should be available straight
from the launcher exe, so packaging it into a setup installer later needs no
separate manual step to attach the mod.
- `OriginLauncher.App.csproj`: new `Content` item copies
  `src/OriginClient.Mod/build/libs/originclient-0.1.0.jar` (Gradle's own
  build output — requires `./gradlew build` to have run in OriginClient.Mod
  first) into the launcher's own output dir as
  `Bundled/OriginClient/originclient.jar`, `CopyToOutputDirectory=
  PreserveNewest`. Filename is pinned to the exact `mod_version` in
  `OriginClient.Mod/gradle.properties` rather than a wildcard — a glob
  risks matching a stale jar from a prior version bump left in `build/libs`,
  which would break the build with an ambiguous match. Whatever tool
  eventually packages the launcher into a setup installer just picks this up
  along with the rest of the output directory — no separate step.
- `OriginPaths.BundledOriginClientJar` resolves it via `AppContext
  .BaseDirectory` at runtime, so it works the same from a dev `bin/Debug`
  build and from wherever an installer actually places the app.
- `VersionManager.InstallAndBuildProcessAsync`: for the Fabric case, when
  the target version is exactly `1.21.1` (the version Origin Client is
  pinned to — must be kept in sync with `gradle.properties` if that ever
  changes) and the bundled jar exists on disk, it's copied straight into
  the instance's `mods/` folder.
- **Caught a real bug while wiring this up, not just plumbing**: the
  launcher already had a *separate* standalone perf-mod installer
  (`PerformanceModCatalog` / `PerfModInstaller`) that downloads Sodium/
  Indium/Lithium/FerriteCore/Krypton straight from Modrinth for any Fabric
  instance — including 1.21.1. Origin Client's own jar *also* bundles
  those same 5 mods jar-in-jar (see `build.gradle`'s `include(...)` deps).
  Installing both into the same instance would have given Fabric Loader two
  copies of the same mod id (e.g. two "sodium"s — the catalog even pins a
  different Sodium point version, 0.8.12 vs. the mod's 0.6.13) and the game
  would flat-out refuse to launch. Fixed by only falling back to
  `PerfModInstaller` when the Origin Client jar *wasn't* installed (wrong
  version, or a dev checkout where the mod hasn't been built yet) — a real
  instance never ends up with both installed at once.
- Verified end-to-end: `dotnet build` on the launcher, confirmed
  `bin/Debug/net8.0-windows/Bundled/OriginClient/originclient.jar` actually
  exists on disk afterward, not just that the csproj change compiled.

## 2026-07-06 — Fabric API auto-installed for every Fabric instance (real launch blocker, not just tidying)
Will asked to have Fabric API built into all Fabric versions "so it works" —
turned out to be catching a real bug, not a nice-to-have: nothing in the
launcher installed Fabric API into any instance at all, and Origin Client's
own `fabric.mod.json` declares `"fabric-api": "*"` as a hard dependency. The
exe would have failed at Fabric Loader's own dependency check the moment it
tried to load Origin Client.
- New `Core/Loaders/FabricApiInstaller.cs`: unlike `PerformanceModCatalog`
  (a small, deliberately hand-pinned table — exact build pairing matters
  there), Fabric API needs to "just work" for whatever exact MC version is
  being installed across every Fabric version Origin supports, so this
  queries Modrinth's version API live (`loaders=fabric&game_versions=X`) and
  grabs the newest build rather than hand-maintaining a table back to 1.14.
  Skip-if-already-present via `fabric-api-*.jar` glob, same idea as
  `PerfModInstaller`. Wired into `VersionManager` unconditionally for every
  Fabric install (not gated to 1.21.1 like the Origin Client jar/perf-mod
  logic — every Fabric mod depends on this, not just ours).
- **Caught a second real bug by actually testing the request, not just
  reading the code back**: Modrinth's API returns `400 Bad Request` when
  `?loaders=["fabric"]&game_versions=["1.21.1"]` is sent with literal
  unencoded brackets/quotes — confirmed by hand with curl. Had to
  `Uri.EscapeDataString` each JSON-array query value
  (`%5B%22fabric%22%5D`) before the API accepted it. Also confirmed the
  resolved download URL actually serves a valid jar (2.4MB, real Java
  archive) end-to-end, not just that the API call parses.
- Lesson: this class of bug (URL/query construction that looks obviously
  right in code) doesn't surface from reading the code or from a clean
  compile — it only shows up by actually firing the request at the real
  API. Worth doing for any other code in this project that builds a URL by
  hand rather than through a typed HTTP client library.

## 2026-07-06 — Real crash on first launch: freelook Mixin targeted a method that doesn't exist that way
Will actually ran the exe end-to-end for the first time and hit a real crash:
Minecraft exited with code -1 immediately after the title screen loaded. Log
at `%LocalAppData%\OriginLauncher\logs\1.21.1_*.log` had the actual cause.

**Root cause, found with real evidence, not guessing:**
`java.lang.RuntimeException: Mixin transformation of net.minecraft.class_312
failed` → `InjectionError: ... Redirector originclient$redirectYaw(...) ...
failed injection check, (0/1) succeeded. Scanned 0 target(s). No refMap
loaded.` — `MouseHandlerMixin`'s two `@Redirect`s assumed
`MouseHandler.turnPlayer` calls `Entity.setYRot(float)`/`setXRot(float)`
with an absolute new angle. That assumption was never checked against the
real 1.21.1 bytecode when the freelook feature was originally written.

**Two dead ends before the real fix, both worth remembering:**
1. First suspected the build.gradle plugin id — this project uses
   `net.fabricmc.fabric-loom-remap` (`LoomRemapGradlePlugin`) instead of the
   official `fabric-loom` (`LoomNoRemapGradlePlugin`) FabricMC's own
   example-mod template uses. Switched to `fabric-loom` and did a clean
   rebuild. **This did not actually fix anything** — verified by decompiling
   the rebuilt class with `javap` and finding the crash persisted. Kept the
   plugin-id fix anyway since it matches the documented official convention
   and caused no regressions, but it was a red herring for this specific bug.
2. Suspected "No refMap loaded" meant Mixin's annotation-remapping never
   ran at all. Wrong — disassembled the actual compiled class
   (`javap -v -p`) and found the `@Redirect`/`@Mixin` annotation string
   values (`Lnet/minecraft/class_1297;method_36456(F)V` etc.) were already
   correctly remapped to real intermediary names at build time. "No refMap
   loaded" is a red herring here — Loom's remap step embeds already-translated
   values directly, no separate refmap resource needed. The real problem was
   the target simply didn't exist in that shape.
**How the real bug was actually found:** pulled the real intermediary-mapped
Minecraft jar out of Loom's own cache
(`~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-merged-intermediary-*.jar`),
extracted `MouseHandler` (`class_312`), and disassembled `turnPlayer`
(`method_1606`) with `javap -p -c`. Real signature is `turnPlayer(double)`,
not the parameterless/float-forwarding shape assumed — it does mouse-
sensitivity smoothing math and ends by calling
`LocalPlayer.method_5872(double, double)` exactly once. Looked that up in
Loom's own cached tiny mapping file
(`~/.gradle/caches/fabric-loom/1.21.1/loom.mappings.../mappings.tiny`,
`grep method_5872`) → real name is `Entity.turn(double yRotDelta, double
xRotDelta)`, declared on `Entity` (`class_1297`), inherited by `LocalPlayer`.
Confirms the two params are **deltas to add**, not absolute new angles.
**Fix**: `MouseHandlerMixin.java` — collapsed the two wrong `@Redirect`s
(targeting a nonexistent `setYRot`/`setXRot` call shape) into one correct
`@Redirect` targeting `Entity.turn(DD)V`. Delta semantics actually simplify
the freelook accumulator logic — no more `newAngle - entity.getYRot()` diffing,
the intercepted values already are the deltas. Verified post-fix by
re-disassembling the rebuilt class: annotation now reads
`Lnet/minecraft/class_1297;method_5872(DD)V`, matching the real invocation
exactly (just expressed via the declaring class `Entity` rather than the
call site's static type `LocalPlayer` — the correct, idiomatic way to write
it; Mixin resolves inherited methods through the class hierarchy).
`EntityViewAngleMixin` (the other freelook mixin, overriding
`getViewYRot`/`getViewXRot`) was checked too and is fine — both method names
verified to exist on `Entity` with the expected `(F)F` shape via the same
tiny mapping file.
**Still not verified**: I can't drive a full 3D Minecraft client window from
this sandboxed shell to confirm the crash is actually gone — bytecode-level
verification is as far as this session went. Needs Will to actually
relaunch and confirm freelook itself feels right (delta accumulation with no
pitch clamp — freelook can now rotate past vanilla's vertical look limits
since our `@Redirect` fully replaces the vanilla call and we chose not to
add our own clamp, matching the original simple-accumulator design intent).
**Lesson for future mixin work on this project**: don't write a `@Redirect`/
`@Inject` target from memory or assumption about what a vanilla method
"probably" does — pull the real jar from Loom's cache and disassemble the
actual target method with `javap -p -c` first. This is the second real bug
in a row (after the Fabric API URL-encoding one) that only surfaced by
testing against the real artifact, not by re-reading the code.

## 2026-07-06 — Lunar-Client-vibe UI pass: launcher-side toggle, animated title screen, keybinds, custom font
Will asked for four things at once: move "Disable Origin UI" to the launcher's
own Settings page as an on/off switch; make the in-game title screen look
like the launcher (black bg, animated floating rings, simple button) instead
of vanilla; give every keybind-driven feature mod (zoom, freelook) an actual
rebind control; and stop using Minecraft's font — use Bahnschrift everywhere
in Origin's own UI.

- **Launcher-side toggle**: new `Core/OriginClientConfigBridge.cs` reads/
  writes just the `originUiEnabled` key in the mod's own
  `instances/1.21.1/config/originclient.json` via a loose `JsonNode` parse
  (not a fixed C# model) — so it never clobbers other fields the Java side
  (`OriginConfig`/Gson) owns, like the player's zoom FOV or HUD toggle.
  Wired into `SettingsPage.xaml`/`.xaml.cs` reusing the existing
  `Toggle.Switch` style (same one the OptiFine toggle already uses — found
  it already existed rather than inventing a new switch style). Removed the
  in-game "Disable/Enable Origin UI" button from
  `OriginClientSettingsScreen` entirely — this control now lives in exactly
  one place.
- **Custom font (Bahnschrift)**: copied the real `bahnschrift.ttf` from
  `C:\Windows\Fonts\` into `src/client/resources/assets/originclient/font/`
  + a `ttf` font-provider JSON, registered as `originclient:bahnschrift` and
  applied via `OriginFont.text(String)` (a styled-Component wrapper) across
  every custom Origin screen/widget — title screen buttons, client settings,
  mods browser, HUD, loading screen. Deliberately does **not** override
  `assets/minecraft/font/default.json` globally — vanilla text elsewhere
  (chat, inventory, tooltips) is untouched, matching how Lunar Client's own
  mod-menu actually behaves (custom font on its own surfaces only).
  **Flagged to Will, not silently decided**: this bundles an actual
  Microsoft-licensed font file inside the mod jar. Fine for personal/current
  use; worth checking Bahnschrift's specific embedding/redistribution terms
  before this ships more broadly.
- **Keybind settings UI**: new `OriginKeybindRow` widget + a fourth
  "Keybinds" tab on `OriginClientSettingsScreen` (Zoom / Freelook / Open
  Client Settings). Click-to-rebind is handled by the *screen* catching
  `keyPressed`/`mouseClicked` while any row is in a "listening" state,
  rather than depending on Minecraft's per-widget focus-forwarding for a
  plain `Button` (uncertain whether that reliably routes raw key events to
  a specific widget) — simpler and more predictable. Verified the real
  `KeyMapping` API before using it (`setKey`, `resetMapping` — note:
  *not* the render-adjacent `setAll`, a different method that looked
  similar by name at first read; `getTranslatedKeyMessage`), same
  javap-first discipline as everything else tonight.
- **Title screen redesign** — the highest-risk piece, so verified most
  carefully before touching anything:
  - Confirmed via `javap` on the real compiled `TitleScreen.class` that
    it overrides `render()` (`method_25394`), `init()` (`method_25426`),
    and `renderPanorama(GuiGraphics, float)` (`method_57728`) — the tiny
    mapping file alone made it *look* like `init` wasn't overridden at all
    (identical-signature overrides aren't always re-listed per-subclass in
    that file), which would have meant the mod's existing, already-shipped
    `TitleScreenMixin` was broken and had simply never been exercised yet.
    False alarm, but only confirmed as one by checking the real class file
    directly instead of trusting the mapping's absence-of-evidence.
  - `renderPanorama` is purely decorative (unlike `LoadingOverlay.render`,
    nothing else depends on its side effects) — confirmed by disassembling
    it — so cancelling it outright at HEAD and drawing new
    `OriginRingsBackground` (a handful of large tilted stroke-only rings,
    ~160 point-samples each, continuously rotating at independent
    speeds — the in-game equivalent of the launcher's animated
    `OriginBackground.xaml`) is safe.
  - Also `@Redirect`-suppressed the vanilla Minecraft logo draw
    (`LogoRenderer.renderLogo`, found via real bytecode disassembly of
    `render()`'s actual call sequence, not guessed) for a clean,
    unbranded look matching the launcher.
  - New `OriginMenuButton` replaces the vanilla-styled `Button.builder(...)`
    calls in `TitleScreenMixin` with flat Deskify-styled buttons (same
    drawing approach as `OriginToggleButton`).
- Verified end-to-end at the bytecode level for everything above (`javap`
  on the rebuilt jar matched the real game's call sites exactly) and
  confirmed the launcher's bundled jar actually picked up the new build.
  Still not confirmed by an actual live playtest in this session — that's
  the next real test.

## 2026-07-06 — Fixed broken text (variable font), exact-matched background, anti-aliased corners
Will reported text was "completely broken" everywhere and asked the
background match the launcher's exactly and buttons not look pixelated.

- **Root cause of broken text, confirmed not guessed**: `bahnschrift.ttf`
  contains an `fvar` table — it's a *variable* font (single file spanning a
  weight axis), and Minecraft's TTF font provider (STB TrueType via LWJGL)
  is known to render these incorrectly/garbled. Confirmed by grepping the
  actual font file for the `fvar` table tag before assuming.
- **Fix**: replaced the TTF provider entirely with a pre-rendered bitmap
  font atlas. Generated via a PowerShell + `System.Drawing` (GDI+, which
  resolves variable fonts correctly) script that rasterizes all 95 printable
  ASCII characters from the real installed Bahnschrift into a 512×192 PNG
  grid, anti-aliased, white glyphs on transparent alpha (so Minecraft's text
  color tinting still works). Visually verified the atlas before wiring it
  in (composited onto black to check — white-on-transparent renders blank
  in a plain image viewer, which looked like a bug at first but wasn't).
  Caught one real bitmap-font gotcha before it shipped: Minecraft divides
  each row's pixel width by *that row's own string length* to find cell
  width, so a final row with fewer characters than the others (mine had 15
  vs. 16) would have misaligned every glyph in it — padded to a uniform 16
  characters per row (including the short last row) so every row divides
  into identical 32px cells.
- **Background**: `OriginRingsBackground` was re-derived from the actual
  values in `OriginBackground.xaml`/`.xaml.cs` (4 rings' exact
  Width/Height/Opacity/initial angle, and `StartRing`'s period+direction
  args) instead of the invented approximation from the first pass. Scaled
  proportionally to the real game window width against the launcher's own
  reference width (`MainWindow.xaml`'s 1180) so the same relative
  proportions hold regardless of the player's actual resolution.
- **Anti-aliased rounded corners**: `OriginTheme.fillRounded`'s corner fill
  was a hard binary circle test (in-or-out per pixel) — replaced with a
  coverage-based alpha falloff over the boundary half-pixel, same technique
  any raster circle renderer uses. This is a real, meaningful smoothing
  improvement, but there's a hard limit worth being upfront about: Minecraft
  draws its 2D UI in a low-resolution *virtual* coordinate space tied to GUI
  Scale (each "pixel" GuiGraphics draws can be a multi-pixel block on
  screen), which is fundamentally blockier than WPF's native
  hardware-accelerated rendering. Getting fully WPF-smooth visuals in-game
  would need a custom shader-based overlay bypassing Minecraft's own GUI
  coordinate system entirely — a much larger undertaking than this pass;
  flagged rather than silently promised.
- Verified: clean build, bitmap atlas visually inspected before shipping,
  jar contents confirmed (font assets present, no leftover ttf), launcher's
  bundled copy confirmed fresh. Not yet confirmed by an actual live
  playtest.

## 2026-07-06 — Font path bug (root cause of "looks the same"), universal background + font across every menu
Follow-up on the font/background/pixelation fixes: Will reported it still
looked broken and that only the title screen was custom while every other
menu was still default Minecraft.

- **Real root cause found via the actual game log, not guessed**: `Failed to
  load builder (originclient:bahnschrift...) FileNotFoundException:
  originclient:textures/font/bahnschrift_atlas.png`. Minecraft's bitmap font
  provider automatically prepends `textures/` to the "file" path — I'd put
  the PNG at `assets/originclient/font/`, it needed to be at
  `assets/originclient/textures/font/`. Because the font failed to load,
  Minecraft silently fell back to vanilla's own font everywhere, which is
  exactly why nothing looked different — not a rendering bug, a wrong
  resource path. Moved the file, no JSON changes needed (the "file" value
  was already the correct logical reference). Confirmed no crash in that
  same log run — the mixins were all applying fine and the game ran to a
  normal "Stopping!" shutdown, which was reassuring alongside the fix.
- **Universal font**: added `assets/minecraft/font/default.json` — a
  from-scratch override of vanilla's OWN default font (checked vanilla's
  real provider chain first: `space` → `default` (non-uniform) → `unifont`)
  that inserts the Bahnschrift bitmap atlas between `space` and vanilla's
  own ASCII glyphs. Font providers resolve per-character in listed order,
  so this means Origin's font now renders everywhere in the game
  (including vanilla's own Singleplayer/Multiplayer/Options screens, world
  names, etc.) while non-Latin/CJK text still correctly falls through to
  unifont exactly as before — nothing lost, much broader win than manually
  re-styling every individual screen's text draws.
- **Universal background**: new `ScreenBackgroundMixin` targets
  `Screen.renderPanorama` directly (the shared base method, not
  TitleScreen's own separate override) — confirmed via bytecode that
  `PauseScreen.renderBackground` just delegates to `Screen`'s base
  implementation (doesn't override `renderPanorama` itself), so one mixin
  reaches world-select, multiplayer, and options-from-title all at once.
  Deliberately skips (does nothing) whenever `Minecraft.level != null` —
  that's the "actually paused mid-game" case, where vanilla shows a blurred
  capture of the running game behind the pause menu. Replacing that with an
  opaque rings background would hide the game the player is paused in,
  which would be worse, not better, even though it's the same method call —
  flagged this UX call to Will rather than silently doing the more literal
  but wrong thing.
- **Caught before shipping**: created `ScreenBackgroundMixin.java` but
  initially forgot to add it to `originclient.client.mixins.json`'s
  `"client"` array — it would have compiled clean and sat there completely
  inert (Mixin only transforms classes explicitly listed in the config; an
  unlisted mixin class is silently never applied, not an error). Would have
  looked like "the fix didn't work" with zero error output to explain why.
  Caught by checking the config file before declaring this done, not by
  waiting for another confusing bug report.
- Not done this pass, flagged as a real scope boundary rather than
  attempted and risked: full custom button/widget reskinning of the pause
  menu, world-select list, server list, and options grid. Those are
  functionally complex vanilla screens (world management, server
  connections, dozens of settings) — reskinning their actual widgets means
  reimplementing real game logic, not just applying a background/font, and
  wasn't something to rush after tonight's several build-verify-fix cycles
  already on the higher-risk title-screen work. Universal background + font
  gets everything most of the way there; pause menu's own button styling
  (keeping the world-blur backdrop) is the natural next piece if wanted.

## 2026-07-06 — Real cause of the wide/broken text font, splash text + branding suppression
Will sent real screenshots + a long technical spec (from elsewhere) demanding
a "total reskin." The screenshots showed something concrete and useful:
every screen's text — including untouched vanilla ones — had unnaturally
wide, near-monospaced letter spacing.

- **Root cause, found by replicating Minecraft's real algorithm, not
  guessed**: disassembled `BitmapProvider`'s actual glyph-width scanner
  (`class_386$class_387.method_2038`) — it scans each glyph cell
  right-to-left for the first column with any non-zero-alpha pixel, exactly
  as I'd assumed. The real bug was upstream: GDI+'s anti-aliased
  `DrawString` onto a `Format32bppArgb` transparent bitmap is known to leave
  faint non-zero-alpha "residue" well outside the visible glyph — which
  fooled Minecraft's scanner into measuring nearly every glyph as ~30px
  wide regardless of the actual letter, i.e. monospaced-looking spacing.
  Confirmed by writing a PowerShell script that replicates MC's exact
  scanning algorithm against the old atlas and printing widths.
- **Fix**: regenerated the atlas rendering opaque black-on-white (no alpha
  involved at all, sidesteps the GDI+ issue entirely), then manually
  converting luminosity to alpha as a post-process. Re-ran the same
  MC-algorithm-replica scanner against the new atlas and confirmed
  proportional widths this time (i/l ~10-12px, M/W ~21-24px, not ~30px
  uniformly).
- **Splash text + version/branding text removed from the title screen**:
  found via disassembling `TitleScreen.render()` that the yellow splash
  ("pls rt" etc.) is a self-contained `SplashRenderer.render(...)` call, and
  the bottom-left "Minecraft 1.21.1 / Fabric" line is the *only*
  `GuiGraphics.drawString` call inside that method (its returned width is
  immediately discarded by the caller, confirmed via bytecode, so returning
  0 instead of drawing is safe). Both suppressed via `@Redirect` in
  `TitleScreenMixin`, same safety profile as the existing logo redirect.
- **Added real hover-scale animation** to `OriginMenuButton` — eased via
  actual elapsed wall-clock time (`System.nanoTime()` per-frame delta), not
  Minecraft's fixed 20-tick/s clock, so it stays smooth at any framerate
  instead of looking stepped. `OriginTheme.lerpColor` added for the
  accompanying color fade.
- **Pushed back on part of the pasted spec rather than implementing it
  blindly**: it recommended injecting at `render()`'s `HEAD` and calling
  `ci.cancel()` before drawing custom UI, "so vanilla rendering never
  executes." This would also cancel the widget-rendering pass that draws
  our *own* added buttons (`addRenderableWidget` relies on `Screen`'s
  default `render()` body actually running) — the exact same category of
  mistake that caused the loading-screen hang earlier tonight. Explained
  this to Will instead of silently implementing something that would have
  broken the buttons.
- Not done this pass: the separate `OriginTitleScreen extends Screen` +
  screen-swap-via-Mixin restructuring the pasted spec also asked for.
  Current approach (Mixin directly into `TitleScreen`, clear + rebuild
  widgets in `init()`) already achieves the same "zero vanilla widgets"
  result without the extra indirection, so this would be a rewrite for its
  own sake, not a fix for anything broken.

## 2026-07-07 — Full UI rewrite attempted and abandoned; back to stock vanilla

Will asked for a complete architectural rewrite of every in-game screen (main
menu, loading, singleplayer, multiplayer, settings, an in-game Lunar-Client-
style blurred mod menu) as a custom UI matching the launcher/website. Built a
real shared widget/screen framework and one full pass at the main menu across
several rounds of live feedback, then Will asked to tear all of it back out
and pause UI work entirely. End state: **100% stock vanilla Minecraft menus**
— only the pre-existing feature mods (Zoom, Freelook, HUD text, Toggle
Sprint/Sneak, Fullbright) remain. `originUiEnabled` and every custom
gui/mixin file from this pass are deleted, not just disabled.

**Real technical findings from this pass, worth keeping for next time:**
- Confirmed via `javap` against the real 1.21.1 mapped classes (not guessed):
  world list (`LevelStorageSource`/`LevelSummary`/`WorldOpenFlows`/
  `LevelStorageAccess`), server list (`ServerList`/`ServerData`/
  `ServerStatusPinger`/`ConnectScreen`), and options (`OptionInstance`,
  though `Options` has no bulk-enumeration API — each setting is its own
  named getter) all have clean, real, public APIs a custom screen can call
  into without reimplementing vanilla's own logic.
- `Screen.renderBlurredBackground(float)` is `protected`, not private, and
  has **no `isPauseScreen()`/`Minecraft.level` coupling** — a non-pausing
  custom overlay Screen can call it directly to blur the game behind it
  (Lunar-Client-style mod menu). This part of the plan was never actually
  built/tested live before the rewrite was abandoned.
- **Root cause of a real, confirmed "laggy as all hell" frame rate**:
  `OriginTheme.fillCorner`'s anti-aliased rounded-corner math issued one
  `GuiGraphics.fill()` call *per pixel* in the corner's radius×radius box.
  Fine at small button radii (6-10px), but `glow()`'s largest blur pass
  inflates that radius past 50-100px for things like a logo bloom — tens of
  thousands of individual fill() calls, every frame, just for one glow.
  Fixed by switching to a row-based algorithm (one wide fill() per fully-
  opaque row, only boundary pixels done individually) — O(radius) draw
  calls instead of O(radius²), same visual output. Confirmed via reasoning
  about real call counts, not just guessed; this class of bug (per-pixel
  fill() in a hot path) is worth checking first if "laggy" comes up again
  on any future custom-rendered screen.
- **Font**: both real attempts (variable-weight TTF, then a static instance
  produced via `fonttools varLib.instancer wght=400 wdth=100` from the real
  `bahnschrift.ttf`) still looked wrong live in-game ("completely blurred",
  "still looks bad"). Per Will's own pre-agreed fallback, dropped custom
  Bahnschrift entirely — back to Minecraft's stock default font. Two font
  attempts across two sessions now, both failed live despite passing static
  analysis each time; a third attempt shouldn't be tried without a way to
  actually visually verify it first (see next point).
- **Live verification gap, the real blocker throughout this pass**:
  computer-use could not resolve the dev Minecraft window for screenshots
  under any name tried (`Minecraft`, `Minecraft* 1.21.1` — the exact real
  title bar text — `Java`, `javaw`), even once confirmed running. Every
  round of feedback this pass depended on Will manually looking at the
  window and describing/screenshotting it. If a future session revisits
  in-game UI work, solving this gap first (or accepting manual
  screenshots as the only channel) would save a lot of round-trips.
- Icon design lesson: hand-drawn procedural icons (point-sampled dotted
  lines/arcs via repeated small `fill()` calls, mimicking
  `OriginRingsBackground`'s technique) read as messy scattered dots at
  real button sizes, not clean glyphs. Will's own original icon pack at
  `Documents/Icon Packs/downloads/icons-dark/png/` (163 icons, 6 sizes
  each, his own design) is the right source for any future icon needs —
  real raster assets via `GuiGraphics.blit`, not procedural drawing.
- `Krypton` (one of the bundled jar-in-jar performance mods) crashes
  `./gradlew runClient` with `NoClassDefFoundError` on its nested
  `velocity-native` jar — Loom's `include()` doesn't seem to propagate a
  bundled mod's *own* jar-in-jar through to the dev classpath. Had to
  temporarily comment out Krypton's `include(...)` in `build.gradle` to get
  a live dev client running at all this session; restored before finishing.
  Unrelated to any UI work — a real, reproducible dev-environment-only
  issue worth root-causing before the next time live testing is needed.
- Also confirmed (in case a variable-font retry ever comes up): the real
  1.21.1 TTF font provider path is LWJGL **FreeType**
  (`TrueTypeGlyphProviderDefinition` → `FT_New_Memory_Face`), not STB as
  a prior session's memory entry claimed — FreeType never selects a named
  instance out of a variable font, it just opens whatever FreeType treats
  as default, which is a different (and more plausible) failure mode than
  "can't parse the format."

## 2026-07-07 — Third UI attempt: design-system spec + incremental, self-verified build-out (M0-M3)

Will asked for a third pass at the website-matching in-game UI, but explicitly
asked to "work as slow as needed... one step at a time" instead of another
big blind rewrite. Wrote `src/OriginClient.Mod/DESIGN_SYSTEM.md` first
(exact tokens/behavior extracted from `website/css/styles.css` and
`js/main.js`, plus the lessons below folded in as guardrails), then a
plan-mode session designed a milestoned build-out
(`/root/.claude/plans/harmonic-knitting-willow.md`) specifically to front-load
everything self-verifiable without a live client, since that gap (not sloppy
analysis) is what sank both prior attempts.

- **Real, confirmed environment constraint this session**: this sandbox's
  egress proxy allows `registry.npmjs.org`/`pypi.org` directly but returns
  403 for `maven.fabricmc.net`, `libraries.minecraft.net`,
  `piston-meta.mojang.com`, `fonts.google.com`, `github.com` — confirmed via
  the proxy's own status endpoint and live `curl` tests, not assumed.
  **`repo1.maven.org` (Maven Central) is reachable**, though — pulled a real
  Gson jar from there for partial compile verification (see M3 below).
  Net effect: this session could not run `./gradlew build` or reach
  Mojang/Fabric's Maven at all; every Java-touching milestone needs Will to
  build+run locally, which the plan accounts for as a hard checkpoint, not
  an afterthought.
- **M0 — font acquisition**: pulled `@fontsource/inter` from npm (static
  per-weight WOFF2, not Google Fonts' variable file) and converted each of
  400/500/600/700/800 to plain TTF via fontTools, asserting no `fvar` table
  survived — the exact pitfall that broke Bahnschrift twice before.
  Reproducible via `tools/font-atlas/fetch_fonts.py`.
- **M1 — `OriginTheme`**: pure-Java design tokens (colors/spacing/radii/
  motion) matching the website's CSS variables exactly, plus a real
  Newton-Raphson cubic-bezier evaluator for the site's ease-out/spring
  curves. Numerically verified in this sandbox (no MC classpath needed):
  `easeOut` is monotonic 0→1, `spring` overshoots to ~1.10 before settling
  at 1 — matches `cubic-bezier(0.34,1.56,0.64,1)`'s real bounce shape.
- **M2 — font atlas generator**: `tools/font-atlas/generate_atlas.py` bakes
  each weight into a supersampled (512px em → 64px em, 8x oversample),
  anti-aliased bitmap atlas using the one technique already proven correct
  in the second prior attempt (opaque black-on-white render, then
  luminosity→alpha as a post-process) — but this time metrics (advance/
  bearing/bbox) come from Pillow's own `font.getbbox()`/`getlength()` at the
  *same* render size as the bitmap, so image and metrics can never disagree
  about scale (unlike Minecraft's own bitmap font provider, which re-derives
  width by scanning pixels — the source of the near-monospace bug last
  time). **Actually looked at the output**: composited sample HUD strings
  ("COORDS 142, 74, -308") at native (64px), mid (22px), and realistic small
  HUD (14px) sizes and viewed the PNGs directly — genuinely anti-aliased,
  proportional (non-monospaced) glyph widths, no residue or bleeding, at all
  three sizes. This is the first time in three attempts the font asset
  itself was actually inspected before reaching Minecraft.
- **M3 — `OriginFont` (no-shader renderer)**: loads an atlas PNG as a
  `DynamicTexture`, forces `setFilter(true, false)` (GL_LINEAR, no mipmap —
  Minecraft's own UI textures typically sample nearest-neighbor, which is
  the live hypothesis for why earlier attempts still looked blocky even
  after their alpha-residue bug was fixed), and draws glyph quads via
  `GuiGraphics.blit` with no custom shader — deliberately testing that
  hypothesis against `DESIGN_SYSTEM.md`'s own claim that the real ceiling is
  structural (GuiGraphics's GUI-Scale-tied coordinate space) before
  committing to a full SDF+shader renderer (M4). Wired into a temporary
  `OriginFontDebugOverlay` (new HUD corner, not real UI yet) so a bad result
  costs nothing to revert.
  - **Could not compile this against real Minecraft classes** (network
    blocked, confirmed above) — the exact `GuiGraphics.blit`/
    `ResourceLocation.fromNamespaceAndPath`/`DynamicTexture`/
    `AbstractTexture.setFilter` signatures used are from memory of stable,
    long-standing 1.21.1-era Mojmap API, not verified against a real jar.
    Mitigated as much as possible short of that: wrote minimal stub classes
    matching the assumed signatures and compiled the real font classes
    against them plus a real Gson jar (Maven Central, unlike Fabric's own
    maven, is reachable) — confirms this code's own logic/syntax is
    internally consistent, and separately ran the actual JSON-parsing path
    against a real generated `inter-700.json` (95 glyphs round-tripped
    correctly, including the space glyph's zero-size/nonzero-advance case).
    Does **not** confirm the MC API signatures themselves are right — that's
    exactly what Will's `./gradlew build` checkpoint is for.
- **Not done yet, waiting on Will**: pull the branch, `./gradlew build`
  (report any compile errors verbatim so a signature mismatch can be fixed
  precisely), run the client, F2-screenshot the debug corner (top-left,
  below existing HUD text), share the image back. Result decides M4
  (skip if smooth, build the SDF+shader escalation if not) per the plan.

## 2026-07-08 — First real M3 checkpoint: `GuiGraphics.blit` compiled clean, crash was unrelated, first screenshot showed still-blocky text

Walked Will through the whole checkpoint end-to-end (new machine, project
folder was actually at `Documents\Origin Client`, not a guessable path —
had to search for it; separately his monitor briefly went gray from an
unrelated display-driver hiccup, resolved on its own / possibly via
Ctrl+Win+Shift+B, unrelated to this work).

- **`./gradlew build` succeeded on the first real try** — every
  `GuiGraphics.blit`/`ResourceLocation.fromNamespaceAndPath`/
  `DynamicTexture`/`AbstractTexture.setFilter` signature guessed from memory
  in M3 (see prior entry) was actually correct against the real 1.21.1
  Mojmap classes. Worth remembering: the stub-compile + fake-Gson-jar
  verification done from the sandbox (no MC classpath at all) was a
  reasonable proxy after all, at least for this set of calls.
- **`./gradlew runClient` crashed before reaching any menu** — but the real
  cause was completely unrelated to this session's font work:
  `NoClassDefFoundError: com/velocitypowered/natives/util/Natives` from
  Krypton's `KryptonSharedInitializer`. This is the *exact* bug a prior
  session already root-caused (2026-07-07 entry): Krypton bundles its own
  nested `velocity-native` jar-in-jar, and Loom's `include()` doesn't
  propagate that nested jar through to the `runClient` dev classpath. Fixed
  the same way as before — commented out Krypton's `include(...)` in
  `build.gradle` with an explanatory comment and a note to restore it before
  any real release build. This is a recurring dev-environment-only trap
  worth solving properly (or at least remembering faster) next time
  `runClient` is needed.
- **First real screenshot of the M3 debug corner**: text rendered, correctly
  positioned, correctly proportioned/kerned — but Will's direct read was
  "too bold and too blocky." Two separate causes:
  - *Too bold*: the debug overlay itself drew an oversized (22px), heavy
    (weight 700) test heading that was never meant to represent real HUD
    styling — a debug-test artifact, not a design decision. Fixed by
    rewriting the overlay to render at the sizes the design actually calls
    for (11px/weight 400 for HUD-style rows, a modest 16px/weight 600
    heading instead of 22px/700).
  - *Too blocky*: the real finding. Working theory: the atlas was baked at
    64px-em and then shrunk as much as ~4.6x at draw time (down to 14px) via
    GL linear filtering *without mipmaps* — large minification ratios
    without a mip chain are a well-known way to get aliased/blocky results
    regardless of the base filter mode, independent of whether Minecraft's
    GUI-coordinate-space ceiling theory (`DESIGN_SYSTEM.md` §6a) is also in
    play. Lowered `generate_atlas.py`'s baked `EM_SIZE` from 64 to 32 (16x
    oversample instead of 8x, same underlying rasterization quality) so the
    common HUD-row case (11px) is now roughly a 2.9x reduction instead of
    4.6x. Re-verified the new atlas the same way as M2 (rendered the same
    sample strings at realistic sizes and looked at the PNGs directly) —
    still smooth. Did **not** touch `AbstractTexture.setFilter`'s mipmap
    flag (still `false`) since enabling mipmapped sampling without confirming
    Minecraft actually generates/uploads a full mip chain for a plain
    `DynamicTexture` risks a worse, more confusing failure (an incomplete
    texture can render solid black) — safer to isolate one variable at a
    time. If the resized atlas is *still* blocky live, that's much stronger
    evidence for the structural GuiGraphics-coordinate-space theory and the
    real next step is M4 (SDF atlas + threshold shader), not further
    fiddling with atlas resolution.
- **Not done yet, waiting on Will (round 2)**: pull, rebuild
  (`.\gradlew.bat build` — should be fast, only resource files + one small
  Java edit changed), `.\gradlew.bat runClient` again, same screenshot ask.

## 2026-07-08 — Third custom-font attempt abandoned; standardizing on vanilla Minecraft text

Round 2 screenshot came back *worse*, not better: the 11px/14px HUD-style lines
(`COORDS`/`PING`, same weight+size as before) rendered clean, but the alphabet row,
numbers/symbols row, and a 16px heading all showed visibly garbled/overlapping glyph
shapes — worse than round 1's "bold and blocky." Working theory, not confirmed
(no way to verify further without another live round-trip): something about dense,
space-free strings at the new smaller baked `EM_SIZE` (32, down from 64) exposed a
positioning/advance issue that wasn't visible in strings with natural gaps (spaces,
punctuation) between characters — plausible candidates include `Math.round`ing the
per-glyph pen position in atlas-em-units before the `PoseStack` scale is applied, or
`GuiGraphics.blit`'s actual UV-sampling behavior not matching what §0/M3's memory
entry assumed. **Not going to chase this further blind.**

Will's call, and the right one: three separate techniques (real TTF via MC's font
provider, a bitmap-provider atlas, a hand-rolled blit-based renderer bypassing MC's
font system entirely) have now failed live across three sessions, every time only
after passing whatever checks were possible without a running client. That's not a
"one more fix" pattern. **Decision: use Minecraft's own vanilla font for all in-game
text, indefinitely** — it already looks clean at HUD sizes (visible in every one of
these screenshots, right next to the failed custom text). Effort goes into the parts
of the design system that don't carry this risk instead: the translucent panel
styling, the mouse-reactive cursor-glow background, button hover/press motion,
colors/spacing — none of which depend on custom glyph rendering.

- Deleted (not disabled) `OriginFont`/`OriginFontAtlas`/`OriginFontDebugOverlay` and
  their `HudRenderCallback` registration in `OriginClientMod` — same convention as
  the 2026-07-07 full-rewrite abandonment (delete dead code, don't leave it inert).
  `OriginHud.java` is untouched, still plain vanilla-font text, unaffected by any of
  this session's font work.
- Kept `tools/font-atlas/` (fetch script, atlas generator, baked Inter TTFs/atlases)
  in the repo, unused — the asset pipeline itself works and was self-verified clean
  every time (viewed directly as PNGs); the failure is specifically in the live
  Minecraft rendering/verification loop, not the font asset. No point deleting a
  working, reusable tool over a problem it didn't cause.
- Updated `DESIGN_SYSTEM.md` with an explicit "settled" banner and marked the
  Typography section + §6a/6b superseded, so a future session doesn't re-attempt
  this a fourth time without first reading why it was shelved. The real, durable
  blocker across all three attempts: no way to observe the live client *during*
  development, only after a full rebuild+relaunch+navigate+screenshot round-trip
  each time. Solving that (or accepting small-increment, Will-reviewed development
  as the permanent mode) is a precondition for ever revisiting custom text
  rendering, not just a one-off inconvenience this session hit.
- Also fixed in passing: `gradlew` was missing its execute bit (harmless on
  Windows via `gradlew.bat`, would have broken `./gradlew` on Linux/macOS).
- Next: continue the plan from M5 (HUD panel) onward, but M5 now means rebuilding
  `OriginHud` into a styled panel using vanilla `Font`/`GuiGraphics.drawString`
  instead of a custom renderer — same panel spec (DESIGN_SYSTEM.md §2), different
  text-drawing call. M6a (cursor glow) and M6b (buttons + mod-menu screen) are
  unaffected by this pivot.

## 2026-07-08 — Custom loading screen: smooth orbital rings (texture-based), real progress via verified javap

Will's next ask: custom loading screen — website charcoal background, "Origin"
centered (any window size/fullscreen), a clean progress bar, and an ambient
background. Gave him a live HTML mockup (Artifact) of 4 background options
(bare+grain, orbital rings, ambient glow, dot matrix); he picked **orbital
rings + light grain**, "just as smooth and clean as the website."

- **The smoothness problem, solved the right way this time.** Thin curved ring
  strokes drawn *procedurally* in MC's GUI space come out as jagged scattered
  dots (the icon lesson from 2026-07-07). Instead: pre-render each ring as a
  supersampled anti-aliased PNG (`tools/loading-screen/generate_textures.py`),
  then only blit+rotate the texture in-game. Magnifying a smooth texture stays
  smooth (unlike the font's *minification* problem), so this sidesteps the
  whole ceiling that killed the font. Ring geometry/opacity/speed mirror the
  launcher's `OriginBackground.xaml` (4 tilted ellipses ~0.37 h:w, back rings
  fainter + gaussian-blurred for depth) so launcher/website/in-game are one
  system. **Self-verified in-sandbox before any Minecraft**: composited all 4
  rings over #050505 at real opacities/rotations and viewed at 3x zoom — clean
  AA curves, no jaggies, grain subtle. This is the key discipline the font
  work lacked: prove the pixels in a viewer first.
- `OriginLoadingRenderer` (new, `client/loading/`): draws bg + rings (each
  rotating at its own period/direction via `Axis.ZP.rotationDegrees` +
  pose scale) + tiled grain + "ORIGIN" in **vanilla MC font** (per the settled
  font decision) + a thin glowing progress bar, all centered off the live
  GUI-scaled window size each frame. Textures load via **classloader**
  (`getResourceAsStream`), not the resource manager, so it's safe during the
  earliest overlay while resources are still loading; degrades to plain
  charcoal if textures fail rather than crashing. Reuses the exact
  texture/blit/DynamicTexture APIs that already built clean in M3 (confirmed),
  plus stub-compiled clean against the new pose/RenderSystem/Font shapes.
- **LoadingOverlay hooked against real bytecode, not memory** — honoring the
  project's #1 mixin rule. Had Will run `javap -p` on
  `net.minecraft.client.gui.screens.LoadingOverlay` from his now-populated Loom
  cache (jar: `minecraftMaven/.../minecraft-clientonly-1.21.1-loom.mappings...jar`;
  note javap wasn't on PATH, used the full jdk-21.0.10 path). Confirmed:
  `render(GuiGraphics, int, int, float)` and `private float currentProgress`
  (already-smoothed 0..1 load progress). `LoadingOverlayMixin` injects at
  **`render` TAIL, non-cancelling**, reading `currentProgress` (via `@Shadow`)
  for a real progress bar. TAIL+no-cancel is the specific, deliberate choice
  that makes it hang-proof: vanilla's own fade timing + `setOverlay(null)`
  transition still run fully; we only paint an opaque scene on top afterward.
  (HEAD+cancel is exactly what hung the loading screen in a prior attempt.)
- **Known v1 limitation, flagged not hidden**: opaque TAIL over-draw means
  vanilla's fade-in/out isn't respected — the overlay appears instantly and
  cuts to the (still-vanilla) title screen rather than cross-fading. Minor,
  and fixable later by replicating the fade alpha (would need `javap -c` of
  render for the exact formula); deferred until the look itself is confirmed.
- **Waiting on Will**: pull, `.\gradlew.bat build` (report errors verbatim),
  `.\gradlew.bat runClient`. The loading screen shows at startup — watch the
  first couple seconds for the rings + filling bar; also F3+T in-world forces a
  resource reload to re-trigger it. Screenshot back.

## 2026-07-08 — Loading screen worked; wordmark→Inter texture; rings moved to main menu

Loading screen came out great live (rings smooth exactly like the website).
Will's feedback drove three follow-ups, all shipped:
- **Squares for the first ~2s** were Minecraft's own font not being loaded yet
  during the first resource reload (drawString renders tofu). Fixed by baking
  the wordmark as a **texture** (`generate_wordmark.py`, website's Inter font —
  Will chose Inter over MC-pixel when asked). Shows instantly; it's one fixed
  word as an image, not a glyph atlas for dynamic text, so none of the earlier
  custom-font risk. Verified smooth in-sandbox (composited over the rings and
  viewed) before shipping.
- **Perfect centering + bar directly under**: renderer now centers the
  wordmark's *ink box* (letters, excluding glow padding) on screen center using
  the baked pixel dims, and places the bar just below the ink bottom.
- **Rings off the loading screen, onto the main menu** (Will's later request).
  Refactored `OriginLoadingRenderer` -> `OriginScreenRenderer`
  (`client/render/`), shared by both screens: `renderLoading()` = charcoal +
  grain + centered wordmark + bar (no rings); `renderTitleBackground()` +
  `renderTitleWordmark()` = charcoal + rotating rings + grain + wordmark where
  the vanilla logo sits.

**TitleScreenMixin** (main menu re-skin), all targets confirmed via `javap -p`
on the mapped 1.21.1 `TitleScreen` + `LogoRenderer` (not guessed):
- Draw Origin background at `render()` HEAD (guaranteed-called, non-cancelling,
  so it paints under logo/buttons).
- Cancel **both** `renderPanorama(GuiGraphics,float)` and
  `renderBackground(GuiGraphics,int,int,float)` at HEAD — belt-and-suspenders
  so whichever backdrop path render() uses can't paint over ours. Both are
  background-only on TitleScreen; widgets draw in the separate widget pass,
  untouched. (Didn't have `javap -c` of render() to know which path it uses, so
  cancelling both covers it in one build instead of risking a round-trip.)
- `@Redirect` the `LogoRenderer.renderLogo(GuiGraphics,int,float)` INVOKE ->
  draw the Origin wordmark instead of the Minecraft logo. Targeted the 3-arg
  overload (render's likely call); the 4-arg overload also exists, so if the
  build fails on this redirect, switch the descriptor to `(...IFI)V`.
- Vanilla buttons + splash/version text left as-is ("keep default for now").
- **Known v1 limitations, flagged**: wordmark ignores the title fade-in
  (appears instantly); if the panorama still shows, render() uses a bg path I
  didn't cancel (unlikely given both are cancelled). Loading screen's hard
  cut (no fade) also still stands.
- **Waiting on Will**: one build for both changes — pull, `.\gradlew.bat build`
  (report errors verbatim, esp. the renderLogo redirect), `.\gradlew.bat
  runClient`. Loading screen = no rings now; main menu = rings + Origin logo.

## 2026-07-08 — Feedback round 2 shipped; title text-removal via confirmed descriptors

Title mixin built + ran clean (BUILD SUCCESSFUL) — the renderLogo→wordmark
redirect (3-arg overload) was right. Will's next feedback batch, mostly shipped:
- Wordmark re-baked all-caps "ORIGIN" + 0.22em letter-spacing (matching the very
  first HTML mockup), used on both screens. `bake_text.py` shared helper does
  char-by-char rendering with letter-spacing.
- Loading bar gained the live "LOADING xx%" caption (mockup option 01). Baked as
  a small glyph strip (`caption.png/json`, fixed charset, Inter 500) composed
  in-game — shows instantly, no tofu, not the failed dynamic atlas.
- Rings sped up to 16/24/33/44s periods (were 40-120s, too slow to read as
  motion — that's why Will said "not spinning").
- Main-menu header enlarged + centered between screen top and the Singleplayer
  button (`h/8+24`), width-clamped.
- **Title text removal** via `javap -c` grep (confirmed real descriptors, not
  guessed): splash = `SplashRenderer.render(GuiGraphics,I,Font,I)V` →
  @Redirect no-op; version line = the *only* `GuiGraphics.drawString(Font,
  String,III)I` in render() → @Redirect return 0. No separate copyright
  drawString exists in render() (only one drawString total), so if a
  bottom-right copyright line remains it's a widget added in init(), to remove
  separately. Small risk the version @Redirect conflicts with a Fabric branding
  mixin on the same invoke — if the build errors there, switch to MixinExtras
  @WrapWithCondition.
- **Waiting on Will**: pull, build, runClient, screenshot the main menu + the
  loading screen (F3+T). First visual check of: caps ORIGIN, the % caption,
  spinning rings, big centered header, and no splash/version text.

## 2026-07-08 — Custom menu buttons (BTN-0..2): reskin-in-place, not widget swap

After the loading/menu polish landed, tackled the last vanilla piece — the menu
buttons — as a planned sub-project (plan approved). Design (with Will): flat
translucent-charcoal fill + hairline border, white baked-Inter labels, hover =
border brighten + soft glow bloom + ~2px lift, all eased on wall-clock time.
- **BTN-0 assets** (`tools/buttons/generate_buttons.py`, reuses `bake_text`):
  rounded-rect fill + hairline border alpha masks (9-sliced + tinted in-game),
  a soft glow, and Inter labels baked as uniform baseline-aligned cells (an
  earlier ink-box-height sizing made "Realms" render bigger than
  "Singleplayer" because descenders inflate the ink box — fixed by a shared
  cellHeight). Self-verified by compositing a full menu mock (9-sliced buttons,
  hover+normal, over the rings) and viewing — smooth corners, clean hairline,
  readable glow. Baked "Quit Game"/"Minecraft Realms" variants too since the
  vanilla button strings may not be "Quit"/"Realms".
- **Approach pivot** (important): originally planned to swap each vanilla
  `Button` for a custom `OriginButton` widget, but adding widgets needs the
  protected/generic `Screen.addRenderableWidget`, and inherited-member access
  already bit us once (the `removeWidget` @Shadow crash). Switched to
  **restyle-in-place**: `AbstractButtonMixin` @Injects `renderWidget` HEAD,
  and *only when `Minecraft.getInstance().screen instanceof TitleScreen`*
  cancels vanilla drawing and calls `OriginButtonRenderer.render(...)`. No
  widgets added/removed; buttons keep positions/actions/clicks. Invisible
  buttons (the hidden language/accessibility/copyright ones) never reach
  renderWidget, so they're excluded for free. `OriginButtonRenderer` keeps
  per-button hover state in a `WeakHashMap` keyed by the button.
- **Deferred to polish (BTN-3)**: press-squash (needs the click hook —
  `playDownSound`/`onClick` — whose declaring class I haven't javap-confirmed;
  hover-only ships first). Also any layout/spacing/size tuning from live view.
- All targets confirmed via javap (scaling `blit` overload, Button ctor/OnPress,
  AbstractWidget accessors); `OriginButtonRenderer` stub-compiled clean. The
  only unverified bit is that `renderWidget` is declared on `AbstractButton`
  (confident — it's the button-drawing method) — a wrong target fails the build
  clearly, not silently.
- **Waiting on Will**: pull, `.\gradlew.bat build`, `runClient`, screenshot the
  menu + hover a button. First live look at the styled buttons.

## 2026-07-08 — Button polish round: cursor-follow glow, label quality parity, Options"..." fix

Will's live feedback on the first styled-buttons build, all addressed:
- **"Glow looks square"** — the baked hover-glow's blur didn't decay to zero
  inside the texture bounds, so the rectangle edge showed at draw scale. Moot
  now: per Will, the glow comes **off the buttons** entirely and becomes the
  website's **mouse-follow spotlight** on the main menu instead. Baked a true
  radial gradient (`radial_glow.png`, alpha 1→0 at 70% radius, zero well inside
  the bounds — can never show an edge; verified over charcoal in-sandbox).
  `OriginScreenRenderer.renderTitleCursorGlow`: core snaps to cursor, halo
  trails via the site's 0.12/frame lerp (dt-corrected), both bloom + brighten
  (~250ms ease) while hovering a clickable — sizes/opacities derived from the
  CSS (130→200px core, 560→720px halo, on a ~1600px viewport → fractions of
  GUI width). Drawn in the render-HEAD inject: over rings, under widgets, same
  z-order as the site (glow z1, content above). Hover detection = any visible
  `AbstractWidget.isHovered()` among `children()` (public fields/methods,
  javap-confirmed earlier).
- **"Options still normal Minecraft + remove the 3 dots"** — one bug: the
  vanilla label is `Options...`, which missed the baked `Options` texture and
  fell back to vanilla pixel font (dots included). Fix: `cleanLabel()` strips
  `…`/trailing dots before both the lookup and the fallback draw.
- **"Labels not ORIGIN-logo quality"** — real cause: the wordmark texture only
  minifies ~1.4x at draw, but labels were baked at 125px cell and drawn at
  ~25 real px (~5x minification, no mipmaps → aliasing; the exact M3 font
  lesson resurfacing). Fix: generator now LANCZOS-downscales label cells to
  32px at bake time so draw-time scaling is ~1.3x. Verified at true display
  size in-sandbox — crisp.
- Hover made snappier (90ms ease-out; lift stays 2px, matching the site's
  translateY(-2px); the site's buttons don't scale on hover — the *glow* is
  what grows, which is what "it gets slightly bigger" maps to).
- **Waiting on Will**: pull/build/runClient — check the mouse-follow light
  (trailing halo, bloom over buttons), Options label now Inter without dots,
  and label crispness vs the wordmark.

## 2026-07-08 — Glow shrunk 60%; labels re-baked per GUI scale for pixel-perfect sharpness

Will confirmed responsiveness is right; two fixes from his next look:
- **Mouse glow too big** — shrunk both layers to 40% of the website-proportional
  sizes (halo 0.35w→0.14w, core 0.081w→0.032w; blooms scaled likewise). The
  1:1 CSS translation was correct math but wrong feel in-game.
- **Labels still not wordmark-sharp** — root cause this round: a single 32px
  bake is only pixel-perfect at GUI scale 2. At scale 3/4 it *up*-scales
  (soft), at scale 1 it minifies (aliased). Fix: bake each label at a ladder
  of cell heights (one per GUI scale 1..6 = round(14.4·gs) real px, subtle
  wordmark-style glow baked in), and at draw time pick the rung matching
  `Window.getGuiScale()` and draw it at exactly 1:1 texels-to-screen-pixels
  (pose-scale 1/gs to escape integer GUI units). Verified rungs at true 1:1
  in-sandbox — crisp at both 29px (scale 2) and 43px (scale 3).
- **One unverified API this round**: `Window.getGuiScale()` (double) — very
  stable API but not javap-confirmed; a mismatch fails the build loudly, and
  the fallback fix is deriving scale from framebuffer/gui width.
- Buttons stay clickable exactly as before — nothing about the in-place
  restyle changed, only how the label texture is chosen/drawn.

## 2026-07-08 — Halo speed, 1:1 grain, loading-bar track visibility

Buttons confirmed good by Will. Three follow-ups shipped:
- **Halo "much faster, slight lag"**: lerp factor 0.12/frame (the website's
  value) → 0.38/frame, dt-corrected. Site-exact felt floaty in-game.
- **Grain "too low res"**: the grain is per-pixel noise but was drawn in GUI
  units, so each texel rendered as a guiScale-sized block (2x2/3x3...). Now
  tiled in REAL pixels via pose-scale 1/guiScale — every grain is exactly one
  screen pixel, like the website. (~100-500 small blits per frame, fine —
  nothing like the per-pixel fill() trap.)
- **Loading bar "still not the correct size"**: layout numbers already matched
  the mockup (46%/1.3%) — the real issue is the unfilled TRACK was 8% white on
  charcoal, i.e. invisible in-game, so only the fill showed and the bar read
  as a stubby wrong-size bar. Track brightened to ~16% white (0x29FFFFFF) and
  width set to the mockup-exact 46%. If Will still flags size after this,
  get an actual loading-screen screenshot before touching numbers again.

## 2026-07-08 — OPT: full menu-tree restyle begins (staged); OPT-1 = buttons everywhere

Will's next directive: apply the design system to the ENTIRE Options tree and
every Java Edition menu — sliders, toggles, checkboxes, tabs, disabled states,
backgrounds — preserving all functionality, Sodium compatibility included.
This is exactly the scope that killed the 2026-07-07 full rewrite, but the
difference now is the proven **restyle-in-place at the widget base class**
pattern (no screen reimplementation, no widget-list surgery). Staged:
- OPT-1 buttons everywhere (shipped, this entry) → OPT-2 menu backgrounds →
  OPT-3 sliders → OPT-4 checkboxes/toggles/tabs → OPT-5 Sodium (its own
  widget classes; separate decision).
- **OPT-1**: AbstractButtonMixin's TitleScreen gate removed — every
  AbstractButton on every screen now draws Origin style. Coverage is
  naturally scoped by the hierarchy: subclasses with their OWN renderWidget
  (ImageButton, SpriteIconButton, Checkbox, AbstractSliderButton) bypass the
  mixin and stay vanilla until their own pass — so this can't mangle icon
  buttons or sliders. CycleButton ("Graphics: Fancy" etc.) does NOT override
  renderWidget → all Options toggles get the style + vanilla-font dynamic
  labels (consistent with the settled font decision). Disabled buttons
  (active=false, e.g. Telemetry) render dimmed Origin style (FILL/BORDER
  _DISABLED + MUTED label) and skip hover; `active` is a public
  AbstractWidget field (javap-confirmed earlier).
- Next round-trip needs javap on: Screen (background methods for OPT-2),
  AbstractSliderButton (value field + renderWidget for OPT-3), Checkbox,
  CycleButton (confirm no renderWidget override), tab classes (OPT-4).

## 2026-07-08 — OPT-2/3/4a shipped in one pass (backgrounds, sliders, checkboxes)

Will's javap batch confirmed every target, so all three stages went out in one
build (each independently revertable via its mixin registration):
- **OPT-2 backgrounds** (`ScreenBackgroundMixin`): `Screen.renderBackground`
  HEAD-cancel → Origin charcoal+rings+grain, gated `Minecraft.level == null`
  so in-game screens keep vanilla's blurred-world backdrop. Also cancels the
  static `Screen.renderMenuBackgroundTexture` (same gate) — that's the helper
  option/selection LISTS use to tile their darker strip, so lists now sit
  transparently on the Origin background. TitleScreen overrides
  renderBackground, so its own path is unaffected (no double draw).
- **OPT-3 sliders** (`AbstractSliderButtonMixin`): renderWidget HEAD-cancel →
  `OriginButtonRenderer.renderSlider`: button shell + faint fill-to-value
  (loading-bar read) + 3px accent handle w/ hover glow + centered label.
  `value` read via @Shadow on a field DECLARED on the target class (the safe
  shadow case — javap-confirmed `protected double value`; precedent:
  LoadingOverlay.currentProgress shadow works). Drag logic untouched; value is
  read live per frame so it's exactly as responsive as vanilla.
- **OPT-4a checkboxes** (`CheckboxMixin`): renderWidget HEAD-cancel → rounded
  shell + accent inner square when `selected()` (public, confirmed) + label
  right, disabled dim like buttons.
- CycleButton confirmed to have NO renderWidget override → OPT-1 already
  covers every Options toggle. TabButton wasn't at
  `components.tabs.TabButton` (class not found) — locate later (likely
  `components.TabButton`); vanilla Options has no tabs, create-world does.
- Refactor: hover easing unified into `hoverEase(Object,boolean)` with a
  WeakHashMap<Object,State> shared by buttons/sliders/checkboxes.
- **Waiting on Will**: pull/build/runClient → Options should now be fully
  Origin (background + rings behind the list, styled toggles/sliders/
  checkboxes, dimmed Telemetry). Also check world-select/create-world
  (background + transparent lists) and that in-game pause still shows the
  blurred world.

## 2026-07-08 — The white ring on the FOV slider: focus highlight, killed via sprite override

Will's screenshots showed a thick white rounded ring around the FOV slider on
the Options screen that survived two rounds of our border changes — including
pinning the slider shell to the resting border color. Diagnosis from evidence
(no jar access in the remote sandbox — network policy blocks piston/gradle, so
no javap this round):
- Our renderSlider IS running for that widget (its groove/handle changed in
  lockstep with our commits), so the AbstractSliderButton renderWidget cancel
  works. Yet the ring persisted unchanged → drawn by some OTHER code path.
- The ring is visually vanilla's `widget/slider_highlighted` focused-slider
  sprite (rounded corners, thick white border), and the FOV slider is the
  screen's initially-focused widget — explaining why only it ringed and why
  our color constants never mattered. Likely mechanism: the concrete class
  (`OptionInstance$OptionInstanceSliderButton`/`AbstractOptionSliderButton`)
  overrides renderWidget, calls super (where our HEAD inject draws + cancels
  only the super body), then blits its own sprite on top.
- **Fix that works regardless of the exact call site**: override the vanilla
  sprites with fully transparent PNGs from our mod resources
  (`assets/minecraft/textures/gui/sprites/widget/`: slider,
  slider_highlighted, slider_handle, slider_handle_highlighted,
  button_highlighted) — mod assets layer above the vanilla pack, so ANY
  uncancelled path that draws them renders nothing. If they're already dead
  sprites, the overrides are no-ops. Deliberately did NOT blank
  `widget/button`/`button_disabled` (an unknown vanilla-drawn button should
  stay visible, not become an invisible click target).
  Generator: `tools/buttons/generate_vanilla_overrides.py`, alpha verified 0.
- If a ring still shows after this, next step is javap on
  `net.minecraft.client.OptionInstance$OptionInstanceSliderButton` and
  `...gui.components.AbstractOptionSliderButton` (Will's Loom cache) to find
  the real draw site.
- Also learned: remote gradle is blocked at the network layer (wrapper 403 on
  services.gradle.org; maven.fabricmc.net/piston unreachable), system gradle
  8.14.3 exists at /opt/gradle but can't fetch Loom either — so the
  stub-compile + Will-builds loop remains the only verification path.

## 2026-07-08 — White ring SOLVED: fill() teardown disables blending; our border drew opaque

The sprite-override guess was wrong — Will's Music & Sounds screenshot (EVERY
slider ringed, no button ringed, "nothing to do with selecting") + his javap
dump pinned the real mechanism:
- javap facts: `OptionInstance$OptionInstanceSliderButton.renderWidget` just
  calls `super.renderWidget` (invokespecial at offset 6) →
  `AbstractOptionSliderButton` has NO renderWidget → resolves to
  `AbstractSliderButton.renderWidget` (the two vanilla `blitSprite` calls at
  46/83) — which our mixin cancels. Vanilla slider sprites never draw at all;
  the transparent overrides were dead code (now reverted, so any future
  uncovered slider subclass renders vanilla rather than invisible).
- **Root cause**: `guiGraphics.fill()` flushes through a RenderType whose
  teardown DISABLES GL blending. In renderSlider we fill() the handle between
  the shell blit and the border blit → the border texture drew with blending
  off → its 11% alpha ignored → fully-opaque thick white rounded ring. Only
  sliders fill() between texture passes, hence ring-on-every-slider,
  never-on-buttons, immune to every border-color change, and immune to the
  sprite blanking. (Checkboxes were safe by accident: their fills come after
  both blits.)
- **Fix**: `RenderSystem.enableBlend()+defaultBlendFunc()` immediately before
  the border nine-slice in renderSlider, plus defensively at the top of
  render/renderSlider/renderCheckbox and before the baked-label blit in
  drawLabel (any fill()-then-blit sequence is a landmine).
- **Rule learned (add to the M3/shader-tint family)**: around GuiGraphics,
  every textured blit must assume BOTH shader color AND blend state are dirty
  — reset both before drawing. fill() is the usual saboteur.
- Only unverified API this round: `RenderSystem.defaultBlendFunc()` (no javap;
  ultra-stable Blaze3D method vanilla widget code itself calls — a mismatch
  fails the build loudly).

## 2026-07-08 — Cursor spotlight on every menu

Will: "the same lighting effect around the mouse in every menu." The
mouse-follow glow (core + trailing halo) was title-screen-only
(TitleScreenMixin render HEAD). Now drawn from ScreenBackgroundMixin so it
covers the whole menu tree, exactly once per frame, always under widgets:
- Out-of-world: inside the existing renderBackground HEAD-cancel path, right
  after the Origin backdrop.
- In-world (pause, in-game options): a new renderBackground TAIL inject draws
  it over vanilla's blurred-world backdrop. TAIL never runs when HEAD
  cancels, so the paths are exclusive by construction.
- TitleScreen overrides renderBackground (and draws its own glow in its own
  mixin), so no double glow there. Hover-bloom uses the same visible+hovered
  children() test as the title screen, now shared as a mixin-local helper.
- renderTitleCursorGlow was verified to have no title-only assumptions
  (gui-scaled width + wall-clock statics; halo state carries smoothly across
  screen changes, which reads as intended polish). Known acceptable gap: any
  screen that fully overrides renderBackground without calling super gets no
  glow -- none observed yet; fix per-screen if one shows up live.

## 2026-07-08 — Inter everywhere via vanilla's TTF font provider ("add the custom text")

The move that was always available but hidden behind the M3 trauma: instead of
custom glyph rendering (banned, 3 live failures), override
`assets/minecraft/font/default.json` from mod resources with a `ttf` provider
pointing at bundled Inter Medium (`assets/originclient/font/inter.ttf`,
OFL license alongside). Minecraft's OWN font engine rasterizes it — zero
custom draw code, so every dynamic string (slider labels, tooltips, chat,
HUD) gets the website font for free, at every GUI scale.
- Provider order: `include/space` ref first (vanilla space handling), Inter
  ttf second (size 10.0 → caps ≈7.3px vs vanilla's 7; oversample 4.0 for
  crispness at high GUI scale), then vanilla `include/default` +
  `include/unifont` refs as fallbacks — Inter-500 is a 230-glyph Latin
  subset (verified with fontTools; no ✔/▶), so non-Latin/symbols fall
  through to vanilla glyphs instead of missing boxes.
- Same open question as the sprite-override attempt: whether Fabric mod
  resources actually override vanilla-namespace assets was never proven
  (the sprite test was moot — those sprites were never drawn). If Will's
  build shows unchanged pixel font, fallback plan: register the assets as a
  Fabric built-in resource pack (ResourceManagerHelper,
  DEFAULT_ENABLED) instead of relying on implicit mod-resource override.
- Baked-label buttons keep their textures (wordmark-quality glow); now
  visually consistent since both are Inter.
- DESIGN_SYSTEM.md banner amended: the ban stays for hand-rolled glyph
  rendering; the TTF provider path is explicitly allowed.

## 2026-07-08 — TTF override CONFIRMED live; baked button labels retired

Will's screenshots confirm the font/default.json override WORKS (Fabric mod
resources DO override vanilla-namespace assets — settles the open question
from the sprite round): the Options tree renders real Inter. But that exposed
a mismatch he called out immediately: main-menu buttons still drew the old
per-GUI-scale BAKED label textures (glow baked in, own sizing) next to live
TTF text — two renderers, two looks. Fix: deleted the baked-label pipeline
(drawLabel ladder, LABELS/LabelInfo, labels.json load, label_*.png assets) —
every label now goes through the one game font (= Inter), shadow-off,
ellipsis-stripped. The ladder was a workaround for the pixel font; with the
TTF provider it was pure duplication. Wordmark + loading caption textures
stay (brand marks, not UI text).

## 2026-07-08 — Font settled FOR REAL: default Minecraft text everywhere (Will's call)

Will's decision after seeing Inter live: "all text back to default minecraft."
Removed the font/default.json override + bundled inter.ttf. Because the
baked-label pipeline was already retired, every label (buttons, sliders,
titles, HUD) now goes through the untouched default font — consistency is
structural, not curated. DESIGN_SYSTEM banner updated: the TTF-provider
mechanism is PROVEN and documented for any future revisit; the hand-rolled
glyph-rendering ban stands. The menu look (Origin background, cursor glow,
styled widgets) is unchanged.
Unparsed remainder of his message: "then after make the same menu with
backround mouse affect buttons and default minecraft text for single player
multiplayer realms and change text for options and main menu" — the
background/glow/buttons already apply to every menu, so asked him what
"change text for options and main menu" means before acting on it.

## 2026-07-08 — Autonomous batch: loading screens, FPS pass, simplify + review

Will handed off a multi-part task to run without him ("continue without me,
don't ask, use best judgement"), then left. Done:
- **Loading/progress screens** get the menu background + a loading bar:
  LevelLoadingScreen / ReceivingLevelScreen / ProgressScreen take over render()
  (HEAD-cancel) → Origin scene (bg + default-font title + smooth indeterminate
  sweeping bar), replacing the chunk map / dirt. ConnectScreen keeps its Cancel
  button + status text (bg already from ScreenBackgroundMixin) and gets the bar
  added at render TAIL. New mixins isolated in originclient.loading.mixins.json
  with required:false + defaultRequire:0 so a moved target degrades to
  vanilla-for-that-screen instead of crashing the mod. Bar is indeterminate on
  purpose (real progress would need an unverifiable @Shadow; no jar/javap in the
  remote sandbox this round — gradle/piston/maven all network-blocked).
- **FPS**: grain tile 128→256px (~135→~40 blits/frame at 1080p, 1:1 look kept;
  512 rejected for jar size); volatile fast-path on both ensureLoaded()s so the
  per-frame / per-widget already-loaded case skips the monitor.
- **Simplify**: trimmed generate_buttons.py to shell-only (label ladder gone);
  deleted the dead M3 generate_atlas.py. fetch_fonts.py + Inter TTFs kept
  (wordmark/caption bakers still source them).
- **Code review**: wrote CODE_REVIEW.md (architecture, the GL shader-tint +
  blend-teardown bug class documented as the standing rule, perf notes, and a
  numbered list of untested assumptions for Will to verify on first launch).
  No new bugs found in the feature mixins (freelook/zoom/HUD unchanged).
- Everything committed + pushed to claude/ingame-ui-design-system-21cp08;
  Will will build/run when back. Not yet visually confirmed (no remote build).

## 2026-07-08 — Loading/logo design restored to the original mockup

Will (billion-dollar-polish pass): the loading screens/logo/grain/glow had
drifted from the original design (`tools/loading-screen/wordmark_preview.png`).
Diagnosed the exact drift commits and restored the intended look. **Key win:
Pillow 12 + the Inter TTFs are present in the sandbox, so the whole scene could
be composited exactly as the Java renders it and eyeballed against the mock
BEFORE any build** — the live-verification gap that blocked earlier asset work
didn't apply to static PNGs.

- **Wordmark**: all-caps "ORIGIN" + 0.22em (commit 2fd52e1) → back to mixed-case
  **"Origin"**, Inter-700, natural (-0.015em) spacing, broad soft glow bloom
  (blur 70 @ 0.28). generate_wordmark.py.
- **Rings**: the "blur all of them, dreamy" pass (9614d07) → restored **crisp**
  originals (front two blur 0, back two 1.4/2.4; thinner strokes, lower
  opacity). generate_textures.py RINGS.
- **Grain**: dropped the 0.4px blur that enlarged the grain → fine per-pixel
  noise like the website's SVG fractalNoise (still subtle at 2.8% in-game).
- **renderLoading**: added the missing rings; wordmark bumped 0.13h→**0.165h**
  and optically centered (0.50h); bar narrowed from 46%-screen to **~word
  width** and sits just under the logo (the mock's underline). Removed the
  "LOADING xx%" caption + its whole glyph-strip pipeline (drawCaption, fields,
  loader, caption.png/json, generate_caption.py) — the mock has no percentage
  and it was the last non-logo baked text.
- World-load screens (LevelLoading/Receiving/Progress/Connect) unchanged — they
  keep contextual default-font titles and just inherit the upgraded ring/grain.
- All asset params verified in-sandbox against the mock (side-by-side matched on
  lettering, size, glow, rings, underline). Java layout numbers are the ones
  that matched in that harness. Still no in-game build (gradle network-blocked);
  Java wiring is deterministic, assets are pixel-confirmed.

## 2026-07-08 — Logo to all-caps ORIGIN + loading bar bigger/lower (Will)

Will revised the just-restored logo: wanted it back to **all-caps "ORIGIN"**
(not mixed-case), with letter-spacing "half a normal space, maybe a little
less" — implemented as 0.45x the font's own space advance (measured 71px @
CAP 300 → 32px tracking, 0.107em) in generate_wordmark.py, so it stays a true
half-space if the font/size changes. Kept Inter 700 + the glow bloom.

Loading-screen layout retuned (all verified in-sandbox against the ring bg via
the Pillow harness, since gradle is still network-blocked):
- Cap height picked as the **middle** of a 0.12/0.135/0.15 comparison → 0.135h
  (all-caps has no descender, so ink height == cap height).
- Wordmark centre nudged to 0.48h; **bar moved farther down (1.15x cap-height
  below centre) and made bigger** (full word width, 0.012h thick vs the old
  0.92-width / 0.006h). OriginScreenRenderer.renderLoading.

Also regenerated the every-screen mockups (scratchpad screens.py) with a pixel
font (PixelifySans) standing in for MC's default text instead of Inter, and
fixed the sound screen to show ON/OFF **toggle buttons** (what vanilla actually
uses there) rather than misplaced checkboxes.

## 2026-07-08 — Multi-version/multi-loader: fail-soft hardening + VERSIONS.md

Will (on fable): "every version of Minecraft, Fabric/OptiFine/Forge or none —
menus must work flawlessly everywhere." Sodium restyle explicitly cancelled
mid-question ("don't mess with sodium actually").

Architecture truths documented in src/OriginClient.Mod/VERSIONS.md: a Fabric
jar can't load under Forge and nothing loads with no loader, so the LAUNCHER
guarantees the loader (always installs Fabric + the version-matched Origin
build — Lunar model; "vanilla" in the launcher UI = Fabric+Origin invisibly).
OptiFine is never paired (Sodium conflict; bundled stack + future Iris covers
it). Forge = separate port decision later. Multi-version = one build per MC
version (mixins bind exact names; e.g. GuiGraphics.blit reshaped in 1.21.2),
Stonecutter recommended when build access exists.

Implemented now — the fail-soft contract (runtime half of "works everywhere"):
- Both mixin configs required:false + defaultRequire:0 (main config was
  required:true/defaultRequire:1 → any moved target used to abort the game).
- OriginScreenRenderer + OriginButtonRenderer: every public draw entry wraps
  in catch(Throwable) → one-time error log → session-wide `broken` switch.
  Boolean-returning entries (renderTitleBackground, renderLoadingScene,
  renderTitleWordmark, render/renderSlider/renderCheckbox) tell callers
  whether Origin actually drew.
- All HEAD-cancel mixins now cancel ONLY on success; TitleScreen's
  panorama/background suppressions + ScreenBackground's list-strip cancel are
  gated on isActive(); the logo @Redirect falls back to the real
  instance.renderLogo(...) when the wordmark can't draw.
- Net: worst case on any version/loader mismatch = vanilla visuals, no crash,
  no black screen. Verified by inspection (call-site grep + brace/wrapper
  structure check); still no compile possible remotely.

NOT done (needs build access, honestly out of scope in this sandbox): actual
per-version builds/ports, Sodium jar verification (Modrinth blocked by proxy
policy — attempted, 403), any javap of non-1.21.1 versions. VERSIONS.md lists
the known API breakpoints to verify per version.
