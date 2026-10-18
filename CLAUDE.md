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

5. **Website changes auto-deploy (Will, 2026-10-13).** For anything on the
   website (`website/`), don't wait for Will's approval: have an independent
   judge agent score it (fun, polish, mobile, accessibility, honest claims) and
   keep improving until it rates **9/10 or higher**, then publish it yourself —
   push ONLY the website commits to `main` (deploy-website.yml publishes on
   push), never unreleased launcher/mod work. Never claim "every version" on
   the site (Origin doesn't cover every Minecraft version).

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
   supported version except 1.8.9, which runs Forge 11.15.1.2318 + OptiFine**
   (back LIVE 2026-10-13, Will's call: Fabric never supported 1.8.9 and
   OptiFine is its only shader layer). No NeoForge, no loader selector anywhere
   in the UI or the code — the player never sees which loader a version uses.
   1.12.2 stays pulled.
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
tools/lean/               Claude Lean: report.py (where tokens went) + hook tests;
                          the hooks live in .claude/hooks/lean/ (README there)
tools/website/build_assets.py  website textures/fonts/icons/three.js from their real sources
website/                  originclient.org: island-in-the-sky page (DOWNLOAD.live switch in js/site.js)
  store.html              cosmetics store (Stripe Checkout, STORE.live switch in js/store.js) — docs/STORE.md
  launcher/promos.json    the launcher HOME BANNER's live feed (promos, Discord invite, ad-network
                          switch) — edit + push to main; launchers pick it up within 30 min
  ad/banner.html          the banner's ad-network page (placeholder until Nitro is approved)
services/cosmetics/       store service (Cloudflare Worker + D1): Stripe checkout + webhook ->
                          who owns/wears what; the mod reads it. docs/STORE.md
tools/cosmetics/          export-capes.mjs: store cape art -> in-game cape textures
docs/RELEASING.md         how shipping works (tag flow, pipeline, labels)
```
- **Shared fix** → edit `src/mods/shared/`, run `python tools/shared-sync/sync.py`.
- **Version fix** → edit that module only; if the file exists in shared/, list
  it in the module's `overrides.txt` (marks the deliberate fork).
- Staged work NEVER lives next to shipped work — `staged/` is the boundary.

## Supported versions
**Fabric, plus 1.8.9 on Forge + OptiFine.** LIVE: 1.8.9, 1.16.5, 1.17.1, 1.18.2, 1.19.2,
1.19.3, 1.19.4, 1.20, 1.20.1, 1.20.2, 1.20.4, 1.21, 1.21.1, 1.21.2/3/4, 1.21.6/7, 1.21.8,
1.21.10, 1.21.11, 26.1/26.1.1/26.1.2 (one jar), 26.2, 26.3. PULLED (modules kept, out of
picker + CI): 1.21.5, 1.12.2. No "Coming Soon" entries — the picker shows only live
versions, and check_consistency.py fails CI if `VersionCatalog` and `OriginBuilds`
disagree. Pre-1.20 is a second render backend (per-module `Gfx` PoseStack wrapper,
`.no-shared-sync`); the 1.21.x line is several separate ports; 26.x is unobfuscated
Java 25. Full detail: `.claude/rules/mods.md` (auto-loads under `src/mods/`) and
`src/mods/VERSIONS.md` (the single source of truth).

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
- Launcher: C# / .NET 8, WPF, CmlLib.Core, MSAL, DPAPI. Settings writes go through
  `SettingsStore.Update(mutate)` ONLY. More: `.claude/rules/launcher.md`.
- Mod: Java + Fabric + Loom + Mojang mappings (26.x unobfuscated, Java 25; 1.8.9: Forge
  11.15.1.2318 + Mixin 0.7.11, MCP names). Shaders via Iris (OptiFine on 1.8.9).
  More: `.claude/rules/mods.md`.

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

## Current state (2026-10-18)
- Latest release `launcher-v1.0.53` (2026-10-18, main @ 1c094fe0: cosmetics v2 + launch v2 + smooth
  loading, #33). Tag `origin/main` directly
  (`git tag launcher-v1.0.N origin/main`). Cloud sessions can't push tags (the git proxy drops
  every tag push), so Will pushes the tag from PowerShell.
- Merged, awaiting Will's in-game pass: 26.1–26.3 and 1.8.9 back on Forge + OptiFine (#32);
  launch experience v2 + smooth loading + snappy menus on 1.21.1 (#33).
- Auth: Minecraft `login_with_xbox` 403 (app approval pending) — `OfflineTestMode` is the test path.
- Full history (everything this section used to hold): MEMORY.md → "CLAUDE.md current-state +
  roadmap archive" and the dated entries. Grep it; never read it whole.

## Roadmap (open items)
- [~] 1.8.9 and 26.1–26.3 — live, awaiting Will's in-game pass.
- [ ] 1.21.9 (pulled), 1.18.1 (no stable Sodium/Iris), crash system v2, light theme.
- [~] Screen-open lag (Will, 2026-10-18): smooth loading scenes + snappy menus built on 1.21.1, awaiting Will's in-game pass; then port. MEMORY.md → 2026-10-18 screen-open lag.

## Token discipline (Claude Lean — `.claude/hooks/lean/README.md`)
Usage is ~all re-reading of the conversation, so what enters it stays forever.
These Origin rules override the global `~/.claude` ones where they differ.
- Search before reading (Grep/Glob), then Read only the lines you need (offset/limit).
- Don't re-read a file that's unchanged and still in context (a hook denies it once;
  repeating the identical call goes through if it was summarized away).
- Build/test output is auto-trimmed to errors + verdict; the full log path is printed.
- MEMORY.md is grep-only — never read it whole, not even at session start.
- Screenshots: look at the folder's `_sheet.jpg` (every shot in one image; dev-shots
  makes it, `python tools/lean/contact_sheet.py <dir>` for any folder) before any
  single full-size shot.
- **How work runs in Origin:** the main session does small, single-area work itself.
  Dispatch agents only for parallel fan-outs (ports across versions), isolated heavy
  research, or review/verify passes — each agent re-pays its own context. Use
  Origin's agents in `.claude/agents/` (Will's claude-config set, adapted to Origin
  with a token contract): coding/porting/debugging on opus, building/reviewing on
  sonnet, mechanical on haiku (pinned `claude-haiku-5-5`: the `haiku` alias lags). Ask for short reports; CI checks at most every 3 min.

# Compact instructions
When compacting, keep: the user's current request and any decisions/"never" rules they
gave this session; the branch and what is committed vs pushed; files changed and why;
the exact failing command/error still open; next concrete step. Drop: file contents
(re-read on demand), resolved errors, tool output already acted on, exploration dead ends.
