# Origin Launcher

> Project-specific config. Global rules, identity, model + skill routing live in
> `~/.claude/` and load automatically — this file is ONLY what's unique to this
> project. Keep it lean (it loads every session). Long logs go in `./MEMORY.md`.

## Type
application

## One-liner
A premium Windows desktop Minecraft launcher (Origin Launcher) that runs a custom
client wrapper (Origin Client) with account management, version/mod-loader
handling, performance tuning, and crash diagnostics — Lunar/Feather-tier polish.

## Why
Deliver a faster, cleaner, more stable alternative to existing modded launchers:
instant-feeling UI, simple version+mod workflow, real crash diagnostics instead
of a raw log dump, and secure multi-account switching without constant re-auth.

## Target
- Platform: Windows desktop only (v1)
- Audience: Will + Minecraft players who want a modded client with less friction
  than Forge/Fabric's raw tooling and a cleaner UI than existing launchers

## Scope (v1 — Phase 1, see Roadmap)
- Must-have:
  - App shell + Deskify-style theme (dark, monochrome, Bahnschrift, 8px grid)
  - Home page: Play button, last played version, selected account, quick settings
  - Microsoft account login (device code OAuth) + encrypted local token storage
  - Account switcher slide-out panel, multi-account support
  - Version selector (vanilla, via Mojang manifest) + install path management
  - Basic settings: RAM slider, resolution, MC version, install path
  - Launch pipeline: build JVM args → launch vanilla Minecraft, isolated instance
- NOT in v1:
  - Mod loaders (Fabric/Forge/OptiFine), mods page, conflict detection → Phase 2
  - Repair Game, advanced JVM editor, performance/graphics mode toggle → Phase 2
  - Crash debug screen, log-cause detection, disable-mods-and-retry → Phase 3
  - Language sync, light theme, future MC version branches (26.x) → Phase 3

## Stack / Tools
- C# / .NET 8, WPF (native Windows, matches Deskify token system directly in XAML)
- CmlLib.Core (MIT) for Mojang version manifest, library/asset download, Forge/
  Fabric install, and launch-argument construction — avoids reinventing a
  well-solved problem; wraps in `Core/Launch/` rather than calling it from UI code
- Microsoft.Identity.Client (MSAL) for MSA OAuth → Xbox Live → XSTS → Minecraft
  token chain
- Windows DPAPI (`System.Security.Cryptography.ProtectedData`) to encrypt cached
  tokens at rest — ties them to the Windows user profile, satisfies "device-bound,
  no plaintext" requirement with no extra key management
- JSON (System.Text.Json) for local config/instance/profile files

## Origin Client (in-game mod) — separate stack
- Java 21, Fabric (loader + Fabric API), Gradle + Fabric Loom, official Mojang
  mappings (Mojmap, not Yarn). Lives in `src/OriginClient.Mod/` — its own Gradle
  build, no dependency on the .NET launcher solution.
- Named "Origin Client" (renamed from "Orbit Client" on 2026-07-06); the
  launcher was renamed to match ("Orbit Launcher" -> "Origin Launcher") the
  same day. Whole product is "Origin" now, no remaining "Orbit" branding.
  Java package `com.origin.client`, mod id `originclient`.
- Pinned to Minecraft 1.21.1 for v1. Multi-version/multi-loader strategy is
  settled in `src/OriginClient.Mod/VERSIONS.md`: one build per MC version,
  launcher always installs Fabric + the matching build (Lunar model; OptiFine
  never paired — Sodium conflict), fail-soft runtime degrades any mismatch to
  vanilla instead of crashing.
- Scaffolded from FabricMC's official `fabric-example-mod` (1.21.1 branch) to
  guarantee a matching Gradle wrapper/Loom version/mappings instead of
  hand-writing config

## Constraints
- Performance targets: launcher cold start <3s, 60fps+ UI transitions, no jank
  switching tabs/versions
- Hard requirements: accounts encrypted at rest and device-bound; instances
  isolated per Minecraft version under `/OriginLauncher/instances/`; no plaintext
  tokens on disk; newest launch action cancels any in-flight one

## Brand
- Name: Origin — mark is 3 tilted rings (ellipses) sharing one center, rotated
  0°/60°/120° (atom/orbital pattern), stroke-only, with a soft monochrome glow.
  Two consumers of the same geometry: `UI/Controls/OriginMark.xaml` (static,
  used as the small in-app mark + rendered into `Assets/origin.ico`) and
  `UI/Controls/OriginBackground.xaml` (larger, continuously rotating, ambient).
- Palette: still Deskify-derived monochrome — dark default, one tonal accent
  (`#E0E0E0`), no new hue even in the glow (confirmed with Will — glow stays
  white/gray, not a color accent).
- UI language pivoted from the original nav-rail/card layout to a minimal,
  center-focused "one primary action" launcher (big centered Play button,
  version dropdown above it, chromeless window, floating corner controls).
  See `UI/Pages/HomePage.xaml` and `MainWindow.xaml` for the current shape —
  this superseded the Phase 1 nav-rail described further down; roadmap below
  reflects the current state.

## Success (Phase 1) = 
Will can install Origin Launcher, log in with a Microsoft account, pick a vanilla
MC version, hit Play, and land in-game — with account switching, RAM/resolution
settings, and instance isolation all working end-to-end.

---

## Roadmap

### Now (Phase 1 — core loop)
- [ ] Azure AD app registration for MSA OAuth (client ID + redirect URI) — Will,
      blocks the account-system work specifically, not UI/shell work
- [x] WPF app shell + Deskify token/theme setup (ResourceDictionary)
- [x] Home page UI (Play button, last played, account preview, quick settings)
- [x] Azure AD app registration done (client ID `de37d9e5-...`, personal
      accounts only, public client, redirect `http://localhost`)
- [x] Account system: full MSA (PKCE auth-code + loopback listener) → Xbox
      Live → XSTS → MC token chain in `Core/Auth/MicrosoftAuthenticator.cs`,
      returns a `CmlLib.Core.Auth.MSession`. Refresh-token support
      (`SignInSilentlyAsync`) for re-auth without a browser each launch.
      DPAPI-encrypted persistence in `Core/Accounts/AccountStore.cs`.
      "Add Microsoft Account" button and account-row selection are wired to
      this for real (`UI/Controls/AccountSwitcherPanel.xaml(.cs)`).
- [x] Launch pipeline: Play button does silent token refresh → builds
      `MLaunchOption` via `LaunchProfileBuilder` → `VersionManager` installs
      + builds the process via CmlLib.Core → `Process.Start()`. Wired in
      `UI/Pages/HomePage.xaml.cs`.
- [ ] **Auth chain unresolved at the last step** — MSA → Xbox Live → XSTS all
      confirmed working against a real account. Minecraft's own
      `login_with_xbox` returns `403 Invalid app registration`; Azure config
      itself has been verified correct (screenshot-confirmed redirect URI).
      Leading unconfirmed theory: new-app-registration propagation delay.
      Next real test is just retrying sign-in after time has passed.
- [x] Version manager: Mojang manifest fetch (CmlLib.Core `MinecraftLauncher`),
      instance folder scaffolding (`/instances/`, `/accounts/`, `/logs/`)
- [x] Launch pipeline: JVM args + RAM + resolution → launch vanilla instance,
      basic process lifecycle (start/exit code capture + per-launch log under
      `/logs/`) in `HomePage.StartWithLifecycleCapture`
- [x] Settings page (basic): RAM slider, install path, resolution, MC version
      (persisted to `settings.json` under the Origin root)

### Now — visual/UX revision (done, on top of Phase 1 core loop)
- [x] 3-ring origin mark (`UI/Controls/OriginMark.xaml`) + animated background
      field (`UI/Controls/OriginBackground.xaml`, 4 rings, independent
      speed/direction, pauses on minimize)
- [x] Chromeless MainWindow: drag-from-empty-background, top-right corner
      cluster (account/minimize/close), floating left-edge icon column
      (Home/Mods-stub/Settings)
- [x] HomePage rebuilt: centered `Button.PlayHero`, version dropdown above it
- [x] Mods placeholder page (stub — real mod loader work still Phase 2 below)
- [x] Settings: Performance/Graphics mode toggle, auto-detected default RAM
- [x] `Core/Launch/JvmArgPresets.cs` (Aikar's flags for Performance mode) +
      `Core/Launch/LaunchProfileBuilder.cs` (produces `MLaunchOption` for
      CmlLib.Core) — prep for the real launch pipeline, still gated on the
      Azure AD auth blocker below for a real account session
- [x] `Assets/origin.ico` generated from the mark, wired as `ApplicationIcon`
      + `Window.Icon`

### Next (Phase 2 — mods + loaders)
- [ ] Fabric/Forge/OptiFine install per version (via CmlLib.Core)
- [ ] Mods page: drag-drop `.jar` install, enable/disable per profile
- [ ] Mod conflict detection (name/version mismatch + suggested fix)
- [ ] Repair Game (re-validate/redownload assets & libraries)
- [ ] Advanced JVM args editor, hardware acceleration toggle
- [x] GPU preference registry hint for hybrid-GPU laptops
      (`Core/GpuPreference.cs`, writes `UserGpuPreferences` for the resolved
      java executable, applied when Performance mode is selected)
- [ ] Language settings (dropdown, synced with launcher UI language)

### Now — Origin Client (in-game mod, new sub-project)
- [x] Fabric mod project scaffolded at `src/OriginClient.Mod/` (MC 1.21.1,
      Mojmap, own Gradle/Loom build)
- [x] Title screen replaced (Mixin): Singleplayer/Multiplayer/Settings/Mod
      Settings/Quit, matching the launcher's minimal look
      (`TitleScreenMixin`)
- [x] Branded loading screen: Origin wordmark drawn over vanilla's resource
      loading overlay without touching its real progress logic
      (`LoadingOverlayMixin`)
- [x] Right Shift mod-menu overlay (`OriginModMenuScreen` + custom-drawn
      `OriginToggleButton`, non-pausing, Deskify-styled toggle rows)
- [x] Feature mods v1: Zoom (FOV mixin), Freelook (camera-only rotation via
      redirect + view-angle override), HUD info (FPS/coords/ping), Toggle
      sprint/sneak (post-tick override, no mixin), Fullbright (gamma
      override, no mixin)
- [x] **Compile-verify against the real 1.21.1 jar** — `./gradlew build`
      passes clean. One real bug found/fixed (`OptionsScreen` is under
      `...screens.options`, not `...screens` directly); every other
      Mojmap class/Mixin target matched on the first try.
- [ ] Not yet visually confirmed in-game (compiling clean proves the Mixin
      targets exist, not that the features look/feel right) — needs a real
      launch. `./gradlew runClient` (dev/offline account) can test this
      independently of the launcher's auth chain if that stays blocked.
      Flagged risks to check specifically: fullbright brightness ceiling
      (gamma validator may clamp back to 1.0), freelook pitch clamp
      behavior at extremes
- [ ] Real logo texture on the loading screen (current v1 is text-only
      wordmark) — open question whether Origin Client gets its own mark or
      reuses the launcher's tri-ring Origin mark; not decided yet
- [ ] Wire the launcher's Play button to actually load this mod (Fabric
      loader install + this mod jar in the instance's mods folder) — not
      done yet, mod currently only runs via its own dev-launch task

### Later (Phase 3 — crash system + polish)
- [ ] Origin Debug Screen: crash-cause detection from logs, mod-conflict tie-in,
      JVM error summary
- [ ] "Disable Mods & Retry" / "Open Crash Log" actions
- [ ] Full motion pass (button press/hover/lift per Deskify spec), load-then-snap
      transitions everywhere
- [ ] Light theme (Deskify inverse tokens)
- [ ] Placeholder support for future MC branches (26.1–26.2)

### Open questions
- Azure app registration not yet created — needed before any real OAuth call;
  shell/UI/version-manager work can proceed without it in the meantime
