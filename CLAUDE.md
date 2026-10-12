# Origin

> Project-specific config. Global rules, identity, model + skill routing live in
> `~/.claude/` and load automatically — this file is ONLY what's unique to this
> project. Keep it lean (it loads every session). Long logs go in `./MEMORY.md`.

## Working agreement (Will)

1. **Before making changes**: ask whatever clarifying questions are needed.
   Don't guess at intent on anything with more than one reasonable reading.
2. **After making changes**: give a short plain-English summary — what changed
   and exactly what Will should click/test. No jargon; Will doesn't touch the
   code, so write the summary for a player, not a programmer.
3. Keep the code optimized for **Claude readability** — Will never edits it.
   Comments explain constraints and reasons, one obvious way to do each thing.
4. **Launch/terminal commands** (Will builds + runs on his own **Windows /
   PowerShell** machine — the cloud sandbox can NEVER build: Fabric/Mojang
   maven is 403-blocked there). Whenever handing Will commands to build or run
   a client, give the WHOLE copy-paste sequence starting from a fresh
   PowerShell — his shell opens at `C:\Users\Will`, NOT the repo, so ALWAYS
   lead with the `cd` into the project root. Never assume he's already in the
   right folder. Full shape:
   ```powershell
   cd "C:\Users\Will\Documents\Minecraft Mods\Origin Client"    # last-known repo path
   git fetch origin <branch>
   git checkout <branch>
   cd src\mods\versions\1.21.1                    # the version module (holds gradlew.bat)
   .\gradlew.bat runClient                        # Right Shift in-game opens the menu
   ```
   Windows paths + backslashes + `.bat`. If the repo path is wrong, have him
   find it: `Get-ChildItem $HOME -Recurse -Depth 5 -Directory -Force -Filter ".git" -ErrorAction SilentlyContinue | ForEach-Object { $_.Parent.FullName }`.

## Working order (Will, 2026-07-21)
Perfect ONE version at a time, then move on — never spread half-finished work
across versions. The order:
1. **1.21.1** — get it perfect first (current focus).
2. then **1.20**.
3. then the **26.x lineup**.
4. then keep going **down the list** until every supported version is complete.

"Perfect" = the full Origin experience (mandate 1–5) working and verified on that
version before advancing to the next. Per-version best-fit still applies (mandate 5) —
each version uses the best stable method for itself, same look across all.

## Type
application

## What it is
- **Origin Launcher** — premium Windows desktop Minecraft launcher (C# / .NET 8,
  WPF). Account management, version handling, performance tuning, launch.
  Lives in `src/OriginLauncher.App/`.
- **Origin Setup** — the custom installer/uninstaller (`OriginLauncher-Setup.exe`),
  .NET Framework 4.7.2 WPF in the launcher's look. Lives in `src/OriginSetup/`
  (engine tests: `src/OriginSetup.Tests/`). See docs/RELEASING.md → The installer.
- **Origin Client** — the in-game Fabric mod it installs: restyled title screen,
  loading screens, mod menu, HUD, and a curated set of QoL mods. Java, Fabric,
  Mojmap, Gradle + Loom. Lives under `src/mods/` — see layout below.

## The mandate (non-negotiable)
1. **No loader choice, ever** (the Lunar/Feather model): the launcher always
   installs the right stack for the picked version quietly. **Fabric for every
   supported version — the lineup is Fabric-only again as of 2026-08-06**, when
   the legacy Forge pair (1.8.9, 1.12.2) was pulled. No Forge, no NeoForge, no
   loader selector anywhere in the UI or the code. (The legacy install path
   still exists in the launcher but is unreachable — see VERSIONS.md.)
2. **Every supported version gets the FULL Origin experience.** Identical look
   across versions — title screen, every loading/progress screen, mod menu, and
   HUD must match the Origin design on every supported version. Vanilla menus
   are NOT an acceptable shipped state; fail-soft-to-vanilla exists only as a
   crash-safety net, never the intended result.
3. **Every supported version has shader integration.** Iris + Sodium on every
   supported version. A version doesn't ship until its shaders work.
4. **Never broken.** Whatever version the player picks, the game boots and the
   Origin surfaces work — or degrade silently to vanilla, never crash. Boot
   crashes surface the CrashReportWindow naming the culprit mod when evidence
   allows (Core/Launch/CrashAnalyzer.cs), with an Origin-only retry.
5. **Per-version best-fit, one consistent look.** Every version is built for its
   OWN layout and API era — there is NO one-size-fits-all implementation. If a
   mechanism that works on one version does not work on another, do NOT force it:
   swap in the best — fastest, most reliable, stable — replacement that works on
   THAT version. The method under the hood may differ per version; the player-
   facing look and feel must stay consistent across every version (mandate 2 —
   consistent, not identical internals). Prefer each version's own stable stack
   over dragging a newer/older mechanism onto it and breaking things. Example
   (2026-07-21): the mod list inside Video Settings uses Sodium's Config API on
   the versions already on stable Sodium 0.8; 1.21.1 ships stable Sodium 0.6.13
   (no Config API, and its 0.8 is a shader-breaking alpha), so it gets the same
   feature via a Sodium 0.6.13 GUI mixin instead — same look, different method.

## Repo layout (the boundaries that matter)
```
src/OriginLauncher.App/   the WPF launcher
src/OriginLauncher.Tests/ launcher pure-logic tests (links Core/Promos; CI build-check runs them)
src/mods/
  shared/                 version-independent mod core — ONE copy, synced out
  versions/<mc>/          SHIPPED per-API-family builds (CI builds these)
  staged/<mc>/            WIP builds — never built by CI, can't reach players
  VERSIONS.md             THE registry: live/staged status, sharing rules, and
                          the 4 coupling points to flip a staged version live
  live-modules.json       THE machine list of shipped modules every CI workflow reads
tools/shared-sync/sync.py propagates shared/ into every module; --check is the
                          CI drift gate (build-check runs it on every push)
tools/release/            plan.py (build vs reuse per version) + notes.py (release notes)
                          + check_consistency.py (launcher vs installer copies agree)
tools/github/labels.py    GitHub label set + PR auto-label rules
tools/website/build_assets.py  website textures/fonts/icons/three.js from their real sources
website/                  originclient.org: island-in-the-sky page (DOWNLOAD.live switch in js/site.js)
  store.html              cosmetics store (Tebex checkout, STORE.live switch in js/store.js) — docs/STORE.md
  launcher/promos.json    the launcher HOME BANNER's live feed (promos, Discord invite, ad-network
                          switch) — edit + push to main; launchers pick it up within 30 min
  ad/banner.html          the banner's ad-network page (placeholder until Nitro is approved)
services/cosmetics/       store delivery service (Cloudflare Worker + D1): Tebex purchases ->
                          who owns/wears what; the mod reads it. docs/STORE.md
tools/cosmetics/          export-capes.mjs: store cape art -> in-game cape textures
docs/RELEASING.md         how shipping works (tag flow, pipeline, labels)
```
- **Shared fix** → edit `src/mods/shared/`, run `python tools/shared-sync/sync.py`.
- **Version fix** → edit that module only; if the file exists in shared/, list
  it in the module's `overrides.txt` (marks the deliberate fork).
- Staged work NEVER lives next to shipped work — `staged/` is the boundary.

## Supported versions
**Fabric only.** 1.20, 1.20.1, 1.20.2, 1.20.4, 1.21, 1.21.1, 1.21.2/3/4,
1.21.6/7, 1.21.8, 1.21.10, 1.21.11 are LIVE, **plus the pre-1.20 set 1.16.5,
1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 (LIVE 2026-07-15, launcher-v1.0.24)**.
26.2 staged. **PULLED 2026-08-06 (Will's call):** 1.21.5, and the legacy Forge
pair 1.8.9 + 1.12.2 — all three are removed from `VersionCatalog`, so they no
longer appear in the picker at all, and CI no longer builds or bundles them.
Their modules stay on disk. **Pre-1.20 is a SECOND rendering backend**, not a
gap-port: `GuiGraphics` only exists since 1.20, so those six modules draw via
`PoseStack` through a per-module `Gfx` wrapper that mirrors the GuiGraphics
method shapes (1.16.5 additionally is Java 8 + fixed-function GL). They are
`.no-shared-sync` for that reason. 1.18.1 is deliberately NOT offered (its
only Sodium is an alpha, its only Iris a pre-release). The 1.21.x line is NOT
one build — Minecraft rewrote its render/GUI/input system in stages, so each
sub-family is its own port. **No "Coming Soon" entries (Will, 2026-10-12):**
the picker shows ONLY the versions above — every unbuilt version (all of 26.x,
1.20.6/1.20.5/1.20.3, 1.19.1, 1.19, 1.18.1, 1.17, 1.16–1.16.4) was removed
because Will says they will never ship; CI's check_consistency.py fails if the
picker (`VersionCatalog`) and `OriginBuilds` ever disagree. Per-
version status, API boundaries, install models, and the promotion checklist
live in `src/mods/VERSIONS.md` — keep that file the single source of truth, not this
one.

**Verification bar:** compiling clean only proves mixin *targets exist*.
`@Inject` descriptor and `@Shadow` mismatches only surface at mixin **apply**
time — always smoke-test a new/ported build with `./gradlew runClient`
(offline dev account) and confirm zero `Mixin apply ... failed` lines before
calling a version done.

## Releasing (main → tag)
`main` always reflects at least the latest release. To ship, from a green main:
`git tag launcher-v1.0.N && git push origin launcher-v1.0.N`. CI (~5-6 min)
builds the changed mod versions in parallel (reusing unchanged jars from the
last release), bundles them into the launcher, asserts every live jar is
present, and publishes setup.exe + the self-update zip with auto-written
release notes (from commit titles — write them for players). Updates are
mandatory for players. Full rules: `docs/RELEASING.md`.

## Stack
- Launcher: C# / .NET 8, WPF. CmlLib.Core (Mojang manifest, downloads, Fabric
  install, launch args). MSAL for MSA→Xbox→XSTS→Minecraft auth. Windows DPAPI
  for token-at-rest. System.Text.Json for config. Settings writes go through
  `SettingsStore.Update(mutate)` ONLY — never write a whole cached snapshot.
- Mod: Java + Fabric (loader + API) + Loom + official Mojang mappings. 1.21.x
  on Java 21, 1.20.x/1.18–1.19.x on Java 17 bytecode, 1.17.1 on 16, 1.16.5 on
  8, 26.2 on Java 25. Shaders via Iris; perf via Sodium/Lithium/FerriteCore.
  1.21.1 bundles its perf stack jar-in-jar; other versions get the stack
  standalone from `PerformanceModCatalog` (`VersionManager.OriginBuilds`
  records which model).
- Pre-1.20 mod (1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4): still
  Fabric + Loom, but a SECOND render backend — no `GuiGraphics` before 1.20, so
  all drawing goes through each module's `client/gui/Gfx.java` (a PoseStack +
  `GuiComponent`-statics shim mirroring the GuiGraphics method shapes). Hence
  `.no-shared-sync` on all six. Era cliffs: `fabric-api` as a depends key does
  NOT resolve below 1.18 (Fabric API's mod id is `fabric` there — use that);
  Loom's `splitEnvironmentSourceSets()` fails below 1.18 (no bundled server
  jar → merged source set); 1.16.5 has no `RenderSystem.setShader*` at all
  (fixed-function: `TextureManager.bind` + `color4f`).
- Legacy mod (1.8.9 / 1.12.2): Java 8, Forge, MCP mappings via
  gg.essential.loom (Gradle 8.7, foojay-provisioned JDK 8), NO mixins — all
  Forge events. Launcher side: `LegacyForgeInstaller` (bundled version JSON +
  universal jar), `OptiFineInstaller` (download-at-install, SHA-1-pinned,
  official-site-then-mirrors, never redistributed), `LegacyStackInstaller`
  (era perf mods + FML splash theme). Shaders via OptiFine.

## Brand
Origin mark = 3 tilted stroke-only rings sharing one center (0°/60°/120°, atom/
orbital), soft monochrome glow. Deskify-derived monochrome (dark default, one
tonal accent `#E0E0E0`, no hue even in the glow). Minimal center-focused
launcher: big centered Play button, version dropdown above, chromeless window,
floating corner controls. In-game menus match this exactly.

## Constraints
- Launcher cold start <3s, 60fps+ UI, no jank switching tabs/versions.
- Accounts encrypted at rest + device-bound; no plaintext tokens on disk.
- App installs per-user to `%LocalAppData%\Programs\OriginLauncher`; ALL user
  data lives under `%LocalAppData%\OriginLauncher` (`Core/OriginPaths.cs` is
  the single path authority — never write user data anywhere else).
- Instances isolated per version AND per account: `instances/<version>/` holds the shared heavy files
  AND the version's ONE `mods/` folder (shared by every account, loaded via Fabric's `fabric.modsFolder`;
  Core/GameFolders/SharedMods.cs), `instances/<version>/accounts/<uuid|offline>/` is each account's own
  game folder — settings, worlds, servers, configs (Core/GameFolders; Will 2026-10-12: one game window
  per account, several at once; mods shared per version). Never rewrite a jar in the shared mods/ in
  place — a running game of another account may hold it (add / delete / rename-swap only).
- Newest launch action cancels any in-flight one FOR THE SAME ACCOUNT (other accounts' games keep running).
- One control, one job: no UI handler may mutate a setting other than its own.

## Current state (2026-10-12)
- **Latest release: `launcher-v1.0.48`** (2026-10-12; mandatory auto-update; tag flow). Tag `origin/main`
  directly (`git tag launcher-v1.0.N origin/main`) — Will's local main once carried an unpushed commit and
  his first v1.0.46 tag landed on it (run cancelled before publishing).
- **v1.0.49 round (2026-10-12):** friends sign-in v2 — Mojang 403s every Cloudflare Worker request, so
  clients send their Mojang player certificate + signed profile + a possession signature and the service
  verifies Mojang's signatures offline (keys from api.minecraftservices.com/publickeys, fetched by GitHub
  Actions into D1 meta; docs/SOCIAL.md "Identity v2"); cosmetics equip uses the same Bearer token. Multiple
  game windows (one per account, "Running" focuses it). Home banner: click a tip → next card, text always fits.
- **Origin social (2026-10-12, branch claude/fervent-feynman-u4xm34, CI-green, untagged — next release):**
  docs/SOCIAL.md is THE contract. Service: services/cosmetics (social.js/party.js, migration 0002_social.sql;
  deploys + migrates when main changes). Launcher: status pill left + "<n> playing Origin" pill right (5 min),
  Friends drawer (chat, party, Join → launches the friend's version straight into their server),
  Core/Social + Core/Auth/MinecraftSessionBroker; CI screen render `launcher-shots.yml` → claude/dev-shots/launcher.
  Game, ALL 16 versions: social/ + gui/FriendsScreen (FRIENDS tab, Right Shift group Settings·Waypoints·MODS·
  Cosmetics·Friends), toasts, party chat `/originpc` (client-only), Party HUD, markers/pings/minimap dots ONLY in
  LAN worlds (Will: positions banned on all multiplayer servers — social/FairPlay). Free Cloudflare plan by Will's
  choice: adaptive polling (4/20/30/60 s), friendsEtag, wantState; ~30 all-day players fit. Untested live until
  merged (service not deployed before that).
- **Will's 2026-10-12 fix round, ALL 16 versions (branch claude/fervent-feynman-u4xm34, CI-green, untagged
  — next release):** every Origin window (Cosmetics, FPS Coach, Quickplay, shader browser, Waypoints, Item
  Size, Item Counter picker, full-screen map) rebuilt on gui/OriginWindow (the mod menu's panel/header/widgets
  as a base class); see-through menu text readable (OriginDeck.seeThrough: legible() colours + soft halo);
  Cosmetics: character shown from the title screen (cosmetics/MenuPlayerPreview, per era), drag spins the
  other way; title + pause Cosmetics buttons identical to their neighbours (1.19.3 pause had none before);
  icons fixed (Shinypots, Glint Colorizer, TNT Countdown, title Accessibility/Language); Color Saturation
  black world / white vignette on 1.17.1-1.20.4 fixed (pre-1.20.5 BlendMode cache — see PORTING-ERA-NOTES).
  Dev shots: `set: world` (OriginDevWorld creates a world: world_off/sat/bright, pause, cosmetics_world) and
  `label` (parallel runs keep separate folders <module>[-world]-<label>). Awaiting Will's in-game pass.
- **New mod menu + Right Shift screen on ALL 16 versions (2026-10-12, branch
  claude/fervent-feynman-u4xm34, merged for v1.0.46):** Will's approved mock (design/menu-redesign
  v5) built 1:1 on gui/OriginDeck (design-px kit, smooth Inter, click targets recorded while
  drawing), Will's v3 icon pack (design/icon-proposals/origin-icon-system-v3; generate_icons.py
  writes every live module). Dev shots of all 16 match 1.21.1 (`[shots:all]` or `[shots:a,b]` in a
  commit message). Per-era methods in src/mods/docs/PORTING-ERA-NOTES.md; 1.16.5 text = OriginTtfFont.
  HUD-editor drag mode itself unchanged. Awaiting Will's in-game pass.
- **Cosmetics + every 1.21.1-only mod on ALL 16 versions (2026-10-12, merged for v1.0.46):**
  capes/halos/crown + Cosmetics screen and its buttons, Portal Calculator, FPS Coach, the feedback
  round below, Mod Menu Layout; Color Saturation now on 1.16.5 too. Dev shots match 1.21.1 on all 16;
  in-world parts await Will's in-game pass. Era findings in PORTING-ERA-NOTES.md. The Cosmetics
  screen is still the old style everywhere (restyle offered).
- **Lunar-parity mods on all 16 modules (2026-10-12, CI-green, not yet tagged — v1.0.43):**
  Lunar's mod set (Minimap, Direction HUD, Ping, Reach, Combo, Crosshair, Cooldowns, Item
  Physics, Boss Bar, Titles, Menu Blur, Nick Hider, Auto GG, Quickplay, Discord status,
  Hypixel/Level Head, Team View, WorldEdit CUI, screenshot pop-up, ...) + Hypixel Bed Wars,
  built on 1.21.1 then ported per era (registerLunarBatch* in each Mods.java). Era stand-ins:
  own Menu Blur post chain below 1.20.5; Hurt Cam tilt / Shinypots speed / toast time sliders
  are no-ops below 1.19.4; no Wind Charge counter below 1.21. Mumble Link skipped (Will).
  Awaiting Will's in-game pass.
- **Origin originals (2026-10-12, CI-green, untagged):** Portal Calculator + FPS Coach (now on
  all 16); **Settings Sync** in the launcher (Core/Sync + Core/SettingsSync.cs,
  off by default, Settings → Origin Client card) carries options.txt, originclient*.json, Sodium/Iris
  settings and servers.dat across every version's instance — three-way per-key merge, era value
  translations, resource packs + `version` never synced. Will chose these and said "that's it".
- **Every live version on the 1.21.1 design (released 2026-10-11, launcher-v1.0.40):** all 16
  modules ported to the 1.21.1 title screen, menus, widget skin, mod menu and mod set,
  CI-compiled, awaiting Will's in-game pass. Differences by necessity: no JEI card on
  1.21.2-1.21.8 / 1.17.1 / 1.16.5 (Will: no substitute). Detail in MEMORY.md;
  era notes in src/mods/docs/PORTING-ERA-NOTES.md.
- **Custom installer (2026-10-11):** Inno Setup replaced by Origin Setup —
  themed Welcome/Customize/Installing/Done + uninstaller, smoke-tested
  (silent install/uninstall) and screen-rendered on every CI run. Not yet
  run by hand on a real PC — first shipped in launcher-v1.0.40. **Since
  launcher-v1.0.41: a ~1 MB setup that DOWNLOADS the newest release's
  launcher zip while installing** (needs internet; resume, verify, Try-again
  screen) instead of carrying an 85 MB payload — docs/RELEASING.md → The installer.
- **In-game fix round (launcher-v1.0.41, 2026-10-11), all 16 modules:** block outline
  drawn at outline time with its own depth state (no see-through edges after zoom), zoom
  eases once per frame and never zooms the hand, HUD no longer redrawn over the mod menu,
  HUD backgrounds default transparent (Keystrokes kept), Time Changer remapped so it works
  in production + live slider, icon strokes padded for AA, waypoint beam/chunk posts as
  real geometry. CI-green, awaiting Will's in-game pass.
- **Source-verified fix round (launcher-v1.0.42, 2026-10-11):** every bug re-traced against
  decompiled vanilla + the pinned Fabric API per version. Waypoint beam/labels/chunk borders
  were rotated twice on 1.16.5-1.19.3 (WorldRenderEvents.LAST fires inside vanilla's
  cloud/weather model-view push) -> identity model-view window in the LAST hook; 1.16.5 zoom
  left chunks culled by the zoomed frustum -> needsUpdate() as FOV widens; loading screen
  draws ORIGIN from vanilla ascii.png (fonts aren't baked yet); scoreboard bg fixed on
  1.20.4-1.21.1 (fills inside drawManaged). Awaiting Will's in-game pass.
- **Release pipeline rebuilt 2026-10-10:** mod versions build in parallel on
  Linux (build-check ~2.5 min, release ~5-6 min, was 12-40); real release
  notes; GitHub labels + PR auto-labels; update download shows a full-screen
  progress bar; Settings shows the launcher version. Detail in MEMORY.md.
- **Since v1.0.24 (through v1.0.29):** JEI bundled + toggleable on all 11 Fabric
  versions that have a Fabric JEI; ThickLine hitboxes + real-item ModIcons
  propagated to every live version; Weather-changer rewrite + own-nametag fix
  everywhere; batched-gui tint fix (TV-static backgrounds) on 1.21.4/1.21.5;
  Add-Microsoft-Account WebView2-dispose fix; CI Gradle cross-release caching;
  **1.21.1 menu buttons reskinned to the Origin look (v1.0.29).**
- **No recipe viewer where Fabric JEI doesn't exist (Will, 2026-10-12: "nah it's fine"):**
  1.16.5, 1.17.1, 1.21.2-1.21.8 keep no JEI card; don't add REI or another substitute.
- **Video-Settings mod rail REMOVED (Will, 2026-10-12):** he didn't like the mod list on the
  left of Video Settings; 1.21.1's SodiumOptionsRailMixin + gui/SodiumModRail are deleted and
  Video Settings is plain Sodium again. Don't port it to other versions.
- **Will's 2026-10-12 feedback round (now on all 16, untagged):**
  Item Counter picks any item in the Item Size grid (shared gui/OriginItemGridScreen); Combo
  gets Players Only + Reset After; Item Physics tumbles about the item centre and eases flat;
  Minimap is singleplayer-only; Screenshot pop-up replaced by buttons on the chat line
  (mods/ScreenshotChat); Nick Hider trimmed to your own name/skin + lobby ID. Launcher home
  banner: preview art (Assets/Promos, from tools/launcher-previews), a "tips" feed slot
  (Core/Promos/PromoTips), progress-bar rotation. **Icons v2:** Will's 2.2 px set
  (design/icon-proposals/origin-icon-system-v2, Feather/MIT) now feeds the 1.21.1 atlas
  (tools/mod-menu/generate_icons.py) and launcher Theme/Icons.xaml. Never ship the pack's
  lunar-originals PNGs (Lunar's art); Origin redrew those 7 in REDRAWN.
- **1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4 went LIVE 2026-07-15**
  (`launcher-v1.0.24`) — Will's order: 1.16.5 and everything above it, skipping
  the unfinished 1.21.x gaps, Lunar-parity coverage. The pre-`GuiGraphics`
  PoseStack backend (one `Gfx` wrapper per module); all six boot-verified by
  Will. 1.19.3 got its OWN jar (hybrid era: 1.19.4 APIs but renderButton
  widgets/no LogoRenderer/dirt bg — the 1.19.4 jar degrades silently there).
  Boot testing found what a 100%-green mixin audit could not: Fabric API's mod
  id is `fabric` (not `fabric-api`) below 1.18 → loader refused to start; and
  1.16.5's `TitleScreen.render()` opens with a full-screen WHITE fill that
  vanilla hides behind the panorama → suppressing the panorama exposed it and
  buried the Origin backdrop. Both fixed; full detail in MEMORY.md.
- **1.8.9 + 1.12.2 PULLED 2026-08-06** (Will's call), together with **1.21.5**.
  All three are out of `VersionCatalog`, so their entries/cards are gone from
  the picker entirely, and neither CI workflow builds them nor does the
  launcher bundle their jars. The legacy pair went live 2026-07-14 on Forge +
  OptiFine; with them gone the lineup is Fabric-only again and the legacy
  install path is unreachable (kept, not deleted — see VERSIONS.md).
- Launcher shipping via tag flow (latest launcher-v1.0.42; auto-update).
  Auth chain: MSA→Xbox→XSTS confirmed; Minecraft `login_with_xbox` returns 403
  (leading theory: new-app-registration propagation) — `OfflineTestMode` in
  Settings→Developer is the test path until resolved.
- Live Origin builds: 1.21.1 (bundled stack), 1.20/1.20.1, 1.20.4, 1.21 —
  runClient-verified except 1.21 (at-home confidence check pending).
- Fabric-only cleanup landed 2026-07-12: loader selector, Forge/OptiFine,
  Voxy, and PerformanceMode all removed end to end.
- Crash system v1: `CrashAnalyzer` + `CrashReportWindow` (boot crashes name
  the culprit mod; Origin-only retry). In-game debug screen still open.
- 1.21.5, 1.21.8, 1.21.10, 1.21.11 went LIVE 2026-07-13, each a separate
  sub-family port of the staged 1.21.x rewrite (render-pipeline era rename
  table in MEMORY.md), all four boot-verified clean through the real launcher.
  The "one build for the whole 1.21.2–1.21.11 family" assumption was wrong;
  1.21.2/3/4/6/7/9 still need their own ports (VERSIONS.md has the boundaries).

## Roadmap
- [x] 1.20 / 1.20.1, 1.20.4, 1.21.1 — full Origin experience, verified.
- [x] 1.21 — wired live; runClient at home is the remaining confidence check.
- [x] 1.21.8, 1.21.10, 1.21.11 — live, each its own sub-family port,
  boot-verified (`src/mods/versions/{1.21.8,1.21.11}`).
- [x] 1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 — the pre-1.20 PoseStack
  backend, live + boot-verified 2026-07-15 (`launcher-v1.0.24`).
- [x] 1.21.5, 1.8.9, 1.12.2 — PULLED from the lineup 2026-08-06 (Will's call);
  modules kept on disk, out of the picker and out of CI.
- [ ] 1.21.9 — pulled (input-event boundary + fabric-API gap); analysis in memory.
- [ ] 1.18.1 — blocked on a stable Sodium/Iris pair (alpha/pre-release only today).
- [~] 26.2 — staged, render layer mid-port (`src/mods/staged/26.2/PORT-262.md`).
- [x] Crash system v1 — boot-crash blame + Origin-only retry.
- [ ] Crash system v2 — in-game Origin debug screen, log-cause detection.
- [ ] Light theme (Deskify inverse tokens).
