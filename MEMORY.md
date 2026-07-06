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
