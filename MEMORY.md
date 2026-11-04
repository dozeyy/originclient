# Origin — Memory log

Append-only log of verified results and root causes, newest at the bottom. One short
entry per finished unit (date, what, evidence). Standing rules do NOT go here — they go
in `DECISIONS.md` (always loaded).

Everything up to the archive commit (2026-10-25) is archived verbatim in
`docs/memory-archive/MEMORY-through-2026-10-25.md` (431 KB). Never read it whole: grep it
for your topic, or open one section with `Read offset=<line> limit=<n>` using the index below.

## Archive index (line: section)
- 9: 2026-10-17 — Origin+ membership (payments + website now, game next)
- 22: 2026-10-17 — Stripe connected; store test lock (branch claude/stripe-store → main)
- 40: 2026-10-17 — CLAUDE.md current-state + roadmap archive (moved here by Claude Lean)
- 210: 2026-10-12 — v1.0.47 released; menu polish; Origin social (friends/chat/parties) everywhere
- 225: 2026-10-12 — v1.0.46 released; Will's fix round on all 16 versions (13 agents)
- 242: 2026-10-12 — Cosmetics + every 1.21.1-only mod ported to all 16 versions
- 261: 2026-10-12 — Mod menu + Right Shift redesign, built and ported to every version
- 286: 2026-10-12 — Store cosmetics: delivery service + in-game (1.21.1), not yet switched on
- 319: 2026-10-12 — Website cosmetics store (showroom; payments off until in-game cosmetics exist)
- 359: 2026-10-11 — Every live version brought onto the 1.21.1 design (Will: "make each version match 1.21.1")
- 401: 2026-10-11 — Website rebuilt: island in the sky + scroll-through download journey
- 444: 2026-10-11 — Custom Origin installer replaces Inno Setup
- 507: 2026-10-10 — Release pipeline rebuilt: parallel builds, real release notes, labels
- 568: 2026-09-25 — Website now mirrors the live Workbench palette; download paused
- 574: 2026-09-25 — Website HUD-editor showcase removed
- 578: 2026-09-25 — Website rebuilt around the verified product state
- 585: 2026-07-22 — 1.21.1 menus: real SDF/MSDF text + rounded-box SDF shaders (scalable rendering)
- 623: 2026-07-22 — 1.21.1 mod-menu redesign (OneConfig-style): rounded, sidebar, Inter, iOS toggles, profiles
- 679: 2026-07-15 — pre-1.20 PoseStack backend built (1.16.5→1.19.4), staged for boot
- 787: 2026-07-13 — 1.21.10 + 1.21.11 LIVE + the "1.21.x is NOT one family" finding
- 890: 2026-07-12/13 — The big reorganization (repo, releases, launcher, crash screen)
- 947: 2026-07-11 — Launcher redesign: master-detail version picker (shipped) + crash window
- 999: 2026-07-10 — Shader Performance Mode fixed across 1.21.1 + 1.20 (ported from 1.20.4; 1.20 was silently dead)
- 1029: 2026-07-10 — 1.20.4 in-game bugs: Iris dirt bands + shader perf mode shadows (root-caused, fixed)
- 1064: 2026-07-10 — 1.20.4 Origin build: ported, verified, wired (src/OriginClient.Mod1204)
- 1102: 2026-07-10 — Direction locked: full Origin UI + shaders on EVERY version
- 1110: 2026-07-10 — 1.20 / 1.20.1 Origin port done + wired + verified
- 1141: 2026-07-06 — Project init
- 1161: 2026-07-06 — Phase 1 shell built (app + theme + nav + settings)
- 1196: 2026-07-06 — Visual/UX revision: chromeless center-focus redesign
- 1240: 2026-07-06 — UI bug-fix pass (post-redesign)
- 1276: 2026-07-06 — MSA auth chain implemented (Azure AD registration done)
- 1313: 2026-07-06 — End-to-end sign-in + launch wired up
- 1346: 2026-07-06 — First real sign-in attempt: 2 real bugs found and fixed
- 1379: 2026-07-06 — Second sign-in attempt: "Invalid app registration" (403), unresolved at Minecraft login step
- 1399: 2026-07-06 — Launch pipeline: process lifecycle capture + GPU preference
- 1416: 2026-07-06 — Orbit Client: new Fabric mod sub-project (custom client wrapper)
- 1461: 2026-07-06 — Orbit Client mod: verified compiling, one real bug found and fixed
- 1484: 2026-07-06 — Renamed the in-game mod: Orbit Client -> Origin Client
- 1513: 2026-07-06 — Full rebrand: Orbit -> Origin (launcher + mod + solution)
- 1554: 2026-07-06 — In-game UI framework expansion (Client Settings + Mods screen + master UI toggle)
- 1643: 2026-07-06 — Bundled Origin Client jar into the launcher's own build output
- 1683: 2026-07-06 — Fabric API auto-installed for every Fabric instance (real launch blocker, not just tidying)
- 1714: 2026-07-06 — Real crash on first launch: freelook Mixin targeted a method that doesn't exist that way
- 1786: 2026-07-06 — Lunar-Client-vibe UI pass: launcher-side toggle, animated title screen, keybinds, custom font
- 1860: 2026-07-06 — Fixed broken text (variable font), exact-matched background, anti-aliased corners
- 1907: 2026-07-06 — Font path bug (root cause of "looks the same"), universal background + font across every menu
- 1966: 2026-07-06 — Real cause of the wide/broken text font, splash text + branding suppression
- 2018: 2026-07-07 — Full UI rewrite attempted and abandoned; back to stock vanilla
- 2094: 2026-07-07 — Third UI attempt: design-system spec + incremental, self-verified build-out (M0-M3)
- 2174: 2026-07-08 — First real M3 checkpoint: `GuiGraphics.blit` compiled clean, crash was unrelated, first screensho
- 2234: 2026-07-08 — Third custom-font attempt abandoned; standardizing on vanilla Minecraft text
- 2285: 2026-07-08 — Custom loading screen: smooth orbital rings (texture-based), real progress via verified javap
- 2338: 2026-07-08 — Loading screen worked; wordmark→Inter texture; rings moved to main menu
- 2382: 2026-07-08 — Feedback round 2 shipped; title text-removal via confirmed descriptors
- 2409: 2026-07-08 — Custom menu buttons (BTN-0..2): reskin-in-place, not widget swap
- 2446: 2026-07-08 — Button polish round: cursor-follow glow, label quality parity, Options"..." fix
- 2480: 2026-07-08 — Glow shrunk 60%; labels re-baked per GUI scale for pixel-perfect sharpness
- 2500: 2026-07-08 — Halo speed, 1:1 grain, loading-bar track visibility
- 2517: 2026-07-08 — OPT: full menu-tree restyle begins (staged); OPT-1 = buttons everywhere
- 2543: 2026-07-08 — OPT-2/3/4a shipped in one pass (backgrounds, sliders, checkboxes)
- 2576: 2026-07-08 — The white ring on the FOV slider: focus highlight, killed via sprite override
- 2612: 2026-07-08 — White ring SOLVED: fill() teardown disables blending; our border drew opaque
- 2643: 2026-07-08 — Cursor spotlight on every menu
- 2663: 2026-07-08 — Inter everywhere via vanilla's TTF font provider ("add the custom text")
- 2689: 2026-07-08 — TTF override CONFIRMED live; baked button labels retired
- 2703: 2026-07-08 — Font settled FOR REAL: default Minecraft text everywhere (Will's call)
- 2719: 2026-07-08 — Autonomous batch: loading screens, FPS pass, simplify + review
- 2746: 2026-07-08 — Loading/logo design restored to the original mockup
- 2777: 2026-07-08 — Logo to all-caps ORIGIN + loading bar bigger/lower (Will)
- 2798: 2026-07-08 — Multi-version/multi-loader: fail-soft hardening + VERSIONS.md
- 2834: 2026-07-08 — Pre-Fabric versions: how Origin reaches them (VERSIONS.md §)
- 2849: 2026-07-08 — Legacy Fabric wired into the launcher (floor: 1.7.10)
- 2875: 2026-07-08 — Supported versions replanned: 1.8.9, 1.12.2, 1.16.5, 1.17+
- 2893: 2026-07-08 — One-click launch flow hardened (Will: "a click and it works")
- 2926: 2026-07-08 — Push-to-update: live self-updating launcher (mandatory)
- 2960: 2026-07-08 — Release channel: ship from the `release` branch, not main
- 2974: 2026-07-08 — Offline test mode + build-check CI (MS auth pending ~1 week)
- 2994: 2026-07-08 — Mod system v1 GREEN first try; shipped
- 3004: 2026-07-08 — "launcher ran an OLD in-game client" — root cause + fix
- 3025: 2026-07-08 — Premium redesign: menu pages, Apple switches, freeform editor
- 3048: 2026-07-08 — First-run crash (all versions) root-caused + fixed; stale 1.21.1 UI
- 3084: 2026-07-08 — In-game mod menu overhaul (ORIGIN revision spec)
- 3120: 2026-07-08 — Live-test feedback pass (mod 0.3.1, commit d1b7c86)
- 3144: 2026-07-09 — Two more silent-mixin root causes (freelook, time changer)
- 3171: 2026-07-09 — Shader store: crash fix, WebP covers, search, remove, +shaders
- 3218: 2026-07-09 — Big mod-fix pass (Batches 1–3): root causes
- 3261: 2026-07-09 — In-game UI feedback pass (switches, fullbright, nametag, chat, particles)
- 3282: 2026-07-09 — Launcher install/update flow made flawless (single-file jar bug + installer)
- 3307: 2026-07-09 — External/user mod management (per-version) + Origin-UI-on-top
- 3365: 2026-07-09 — Launcher crashed when the game closed (root-caused + fixed)
- 3394: 2026-07-09 — ORIGIN wordmark → Michroma + loading-screen depth pass
- 3429: 2026-07-09 — Bundled 3 more zero-visual-change FPS mods (EntityCulling / ImmediatelyFast / ModernFix)
- 3462: 2026-07-10 — HUD positions reset to defaults on relaunch (save/load length mismatch)
- 3483: 2026-07-10 — Origin ported to Forge for the classics (1.8.9 + 1.12.2)
- 3522: 2026-07-10 — Forge classics brought to full Fabric-client parity
- 3567: 2026-07-10 (later) — Reverted the Forge detour; Fabric-only again + fixed hidden 1.21.1
- 3597: 2026-07-10 (later 2) — Fabric-only 1.20+; multi-version mod build attempted, reverted
- 3625: 2026-07-10 (release) — Shipped Fabric-only 1.20+ to main + release
- 3638: 2026-07-10 (Mod120) — Iris nag + in-world shader-screen dirt; 1.20 versions locked
- 3678: 2026-07-11 — 1.21.1 UI polish pass (A/B/C spec)
- 3956: 2026-07-12 — Launch reliability pass: Play crash root-caused, boot watch, external-mods switch, Voxy removed
- 4015: 2026-07-12 — Fill out the rest of 1.21.x (1.21 live; 1.21.2–1.21.11 family)
- 4042: 2026-07-12 — Merge 26.2 to main; scaffold the 1.21.11 Origin build
- 4078: 2026-07-12 — 1.21 was never launchable: catalog Iris/Sodium era mismatch (found in pre-release verification)
- 4102: 2026-07-14 — 1.8.9 + 1.12.2 LIVE: the legacy pair, Forge + OptiFine (Will's direct order, Fabric-only mandate 
- 4155: 2026-07-14 — Legacy fix round from Will's first play-test (all verified in-game with shaders)
- 4194: 2026-07-14 — Legacy round-2 fixes (Will's 2nd play-test); FPS-regression lesson
- 4222: 2026-07-17 — ThickLine + ModIcons propagated to every live Fabric module
- 4244: 2026-07-17 — TV-static backgrounds on 1.21.4/1.21.5: root-caused + fixed
- 4257: 2026-07-19 — "Add Microsoft Account" fails on repeat use: WebView2 not disposed (root-caused + confirmed fixed
- 4292: 2026-08-01 — 1.21.11 "Ticking Particle" crash: Hide All Particles + any firework = NPE (root-caused, fixed, bo
- 4325: 2026-08-01 — Cross-version audit (26.2 / 1.21.1 / 1.21.11) + every dead control wired up
- 4388: 2026-08-01 (night) — 1.21.x brought onto the 1.21.1 baseline (Will: "use 1.21.1 as a baseplate")
- 4481: 2026-08-01 (late) — firework crash guard propagated to EVERY version + profiles promoted
- 4521: 2026-08-02 (early) — Color Saturation + Tab Editor ported into the 1.21.x line
- 4627: 2026-08-12 — 1.21.11 blank inventory squares + Toggle Sprint that keeps its memory
- 4693: 2026-08-14 — Tab Editor: live health readout + combat highlight (all 8 1.21.x modules)
- 4730: 2026-08-14 — v1.0.35 shipped a total launch outage; root cause + hotfix v1.0.36
- 4757: 2026-08-22 — Slider knob press animation crashed on every drag (v1.0.36)
- 4796: 2026-08-23 — Vanilla menus reverted to vanilla buttons; foreign-mod widgets
- 4850: 2026-08-23 — v1.0.37 shipped a stale-settings bug: JVM args needed a restart
- 4898: 2026-09-03 — "Aurora" in-game redesign of 1.21.1 (branch `redesign/origin-2026-09`)
- 5050: 2026-09-09 — Origin Workbench cross-version UI system
- 5075: 2026-09-09 — Minecraft-native typography and block-material refinement
- 5093: 2026-09-09 — deterministic launcher identity + block-control enforcement
- 5117: 2026-09-09 — four-column mod library + unified pause-menu entry
- 5137: 2026-10-11 — video-settings-mod-rail: 1.21.1 build (Sodium 0.6.13 GUI mixin)
- 5182: 2026-10-18 — 1.21.1: Resource Pack Hub, frame-time stats, Origin Render (branch claude/origin-client-developme
- 5197: 2026-10-18 — Launch journey (launcher → game handshake + transitions, 1.21.1)
- 5211: 2026-10-18 — Launch experience v2 (Will's picks, built — awaiting Will's in-game pass)
- 5231: 2026-10-18 — Screen-open lag (Will's report; built on 1.21.1, awaiting his pass)
- 5252: 2026-10-18 — Settings Sync on for everyone + instant saving (every version)
- 5274: 2026-10-18 — originclient.org is served by Vercel, not GitHub Pages
- 5282: 2026-10-18 — Cosmetics round (Will's list; built on 1.21.1, awaiting his pass) (branch claude/relaxed-turing-p
- 5312: 2026-10-18 — Launcher mod profiles (Will's ask; branch claude/launcher-mod-profiles)
- 5328: 2026-10-18 — Loading → main-menu freeze fixed (every version; branch claude/fix-menu-handoff-freeze)
- 5348: 2026-10-18 - Website hero spacing fix
- 5357: 2026-10-18 — 1.21.1: shared after-shaders pass for world overlays (branch claude/overlay-after-shaders)
- 5365: 2026-10-18 - Launcher restyle, Friends/Party/Chat redesign, /originpc held sync
- 5377: 2026-10-19 — Cosmetics sit over the outer skin layer + head/face 3-axis adjust (every live version; branch cla
- 5392: 2026-10-18 - held sync: stale friendsEtag no longer ends a hold
- 5398: 2026-10-19 - Launcher icons clipped, ClipCheck guard, 26.3 plus, held-sync churn
- 5408: 2026-10-19 - Import from another Minecraft launcher (shipped on claude/import-instance)
- 5422: 2026-10-19 - Loading → main-menu hand-off v2 (smooth with and without mods)
- 5456: 2026-10-19 - Loading bars never go backward (startup + world)
- 5471: 2026-10-19 - Failed-reload guard + boot-smoke on real Java 17
- 5481: 2026-10-20 - Shader speed-ups shipped on every version (launcher-v1.0.58 to v1.0.60)
- 5513: 2026-10-20 - Frozen brush crash in FamilyTheme.Apply (v1.0.60)
- 5516: 2026-10-20 - Press-scale crash (RenderTransform.ScaleX)
- 5520: 2026-10-20 - High-quality version art
- 5523: 2026-10-20 - Emblems, player heads, import memory, picker
- 5529: 2026-10-20 - Cohesion pass + crash-window fallback
- 5534: 2026-10-20 - Released launcher-v1.0.61
- 5537: 2026-10-25 - Overhaul Phase 0: two fixes merged, cleanup, baseline
- 5544: 2026-10-25 - Website: AI helper removed
- 5547: 2026-10-25 - Releases launcher-v1.0.62 to v1.0.65 (catch-up)
- 5554: 2026-10-25 - Sodium/Iris per-profile pin spike (docs/overhaul/spike-sodium-iris.md)
- 5560: 2026-10-25 - Workbench design system (Phase 2) merged, judge 95
- 5567: 2026-10-25 - Phase 0 baseline (CI, main 924a5809d)

## Entries (after the archive)

## 2026-10-25 — Website assistant Anthropic key disabled
- Will disabled the origin-assistant-api Worker's Anthropic API key in the Claude Console (2026-10-25). Closes the archive note at line 5552. The Cloudflare Worker itself may still exist (not checked).
- Will deleted the Cloudflare Worker too (2026-10-25). Website assistant fully gone.

## 2026-10-25 — Rings retired on every version (only the real logo)
- Will: "the only place you can use the oval shapes thing is our logo". Title orbit rings, the ring loading animation and the ring backdrop (Mod Menu, HUD editor, Origin windows) are deleted on all 21 live modules; loading/connecting = origin_logo.png at 44% + a 200 design px capsule bar (LoadingLook), RingField cut to SceneClock. Title dock hover cards hidden (narration kept). Merges da702dc1e (1.21.1) and 8f4014045..4a2b9efcd (others); shared ring art deleted in 01f009a13; check_logo.py now fails on ring art outside the pulled 1.21.5/1.12.2.
- Proof: boot-smoke zero `Mixin apply ... failed` on every module; dev-shots 95-117 PNGs per module, sheets show no rings; main build-check 40230306341 green. Gated deploy left.
- Open: other versions' title screens still have the old look (pixel wordmark, coloured dock icons) — 1.21.1's Workbench title isn't ported yet.

## 2026-10-25 — 1.21.1 title look on every version
- Will: "bring 1.21.1's new title screen look ... to the other versions". Real origin_logo.png + origin_wordmark.png, Workbench panel buttons with Inter labels, plain parchment dock glyphs (tooltips hidden), plateless head chip, Inter footer now on all 20 live modules. Merges a679b5cb6 (1.20) and the six claude/title-port-* branches; footer dot escaped in a45d654e6.
- Proof: every group's boot-smoke had zero `Mixin apply ... failed`; dev-shots 98-117 PNGs per module; a 20-module title grid matches 1.21.1. 26.2/26.3 keep Mojang's Friends button (person glyph), 26.3 also Realms; 1.8.9 dock has 4 icons. Per-era facts in .claude/skills/origin-port/recipes/title-menus.md. Gated deploy left.
- Open: Friends notification badge and a real downloaded skin on the head chip not seen in CI.


## 2026-10-25 — Overhaul close-out (phases 3-7)
- Merged: Discord presence always on (b89f3cd5d), covered-version boots + 1.21.2 Sodium 0.6.0 (10404ac05, bd38abe04), U3.9 launcher fixes (c0f24144d), Crash V3 (99a59154b, crash-plant 7/7, unknown rate 3.1%), per-profile component pins + configurations + Fix Compatibility (component store under OriginPaths; Modrinth one GET per missing id), boot-smoke `pairs` (305682cbf), 1.8.9 Java/GC A/B: shipped preset wins (cf26338ad), guided import in four steps (178b43118), P7 audit corrected (52d7cda8d). Main tests 2396 launcher / 63 setup.
- Mod Menu parity: every module differs from baseline 40178168462 only in the ring-backdrop removal. Pair proof 40252040490: 1.21.1 0.6.5+1.8.1 and 1.21.11 0.8.0/0.8.4 pairs reach the title, 0 mixin fails. 1.19.2 JEI 11.8.1.1035 matches all 10 hooks; 11.68 moves UserInput (4 hooks break), held.
- Judge, once each: P3 80, P4 74, P5 89 / UI 84, P6 81, P7 89. Will (2026-10-25): "make it go wayyyy faster ... not as many tests not as many reviews" and "no more opening minecraft" -> no re-judges, no CI runs that start the game; findings fixed in the launcher or listed as follow-ups.
- Correction: the "title-port" dev-shots ids 40226765644.. are the rings-out runs; the title-port shot runs are 40236720080, 40236886941, 40237137504, 40237558497, 40237626010, 40238188228, 40238195619, 40238943451.
- Held, not on main: claude/p6-judge-fix (da2f14b69, bench-only BenchSwitches for picks 3-5 + time to title; never run in a game). Run it with `gh workflow run bench-189.yml --ref claude/p6-judge-fix -f runs=4 -f scenes=idle,hud,particles -f extra=off-hud,off-minimap,off-particles -f label=judge` once Will allows game runs.
- Follow-ups: in-game loading consistency (Inter vs MC font before 1.20, Workbench Cancel only on 1.21.1, opaque vs see-through backdrop, 0% bar stub pre-1.20, no 1.8.9 loading shot); P4-15 frame time; P6 multiplayer/baseline/cosmetics (+18.6%) checks; covered boots into a world + pinned Fabric API; Core/Compat repairs "reset JVM args" and "install missing dependency"; crash-plant differential test.

## 2026-10-25 — Launcher QoL after v1.0.66 (Will's report)
- Picker release panel: an open ComboBox captures the mouse, so outside clicks were swallowed; VersionSelectOverlay PreviewMouseLeftButtonDown now closes on click-off. Hover late after open: WPF doesn't re-hit-test a still mouse after the rise transform -> Mouse.Synchronize() when it ends.
- Tabs stay built and are shown/hidden, newest click wins (UI/PageSwitch), refresh after the frame: click cost 3.5-10.7 ms -> 1.3-1.7 ms. Hover "flash" (person icon, card faces) = text snapping while its plate faded; brushes now fade over Motion.Hover. Store/logo hover arrows and Icon.LinkHint removed. Merge f16de386e, tests 2407.
- Unverified with a real mouse (render-shots can't click): click-off, hover timing, flash. Not released yet.

## 2026-10-26 — Newer everything + player's mods win + step-aside + Sodium gates
- Deps (merge 172f72077): every live module on newest bootable deps; 1.21.1 ships Sodium 0.8.13 (SMxNOGZ6) + Iris 1.8.14-beta.1 (bAo1Qhte), no Indium (Will's Voxy/Sable need Sodium >= 0.8.12); 1.21 Sodium 0.6.13 + Iris 1.8.8; 26.3 Sodium 0.9.3-beta.1. Fabric Loader 0.20.1 everywhere (FabricLoaderFloor reinstalls older cached loaders). Boot-smoke 40284428598 27/27, 0 mixin fails. Dev facts: Iris 1.8.14-beta.1 needs DevRemapFix in dev; new JEIs nest mezz_config (modLocalRuntime) and need fabricloader >= 0.19.4; JEI 19.51/27.54 renamed hooks (drawForScreenForeground/Background, onGuiMouseDragged). Held: Sodium 0.8.15-beta on 1.21.11, 26.1 JEI, JEI > 19.39 on 1.21, JEI 19.57+ on 1.21.1 (Java 25 Gradle), Krypton on 1.21.1.
- Player's mods win (merge ebb0681ed): PlayerOverrides per launch (profiles, import, drop-a-jar, shared mods folder via hard-linked mods-shared copy). A jar is Origin's only if its folder's `.origin-installed` record says so (AdoptLegacy adopts old unpinned Fabric API/Voice Chat once); pin-only matches are renamed `.jar.disabled`, never deleted. Pair rules: tested pair -> switch pin; untested -> `-Dfabric.debug.disableModIds=iris`; a player Iris addon keeps a pair. Review found 1 Critical (old unpinned Fabric API lost) + 2 High, all fixed. Tests 2476.
- Step-aside (merges ed242c02c, 3d36a38e0, 3304765ff, cae774777): shared StepAside table freecam->freelook, zoomify->zoom, appleskin->saturation, worldeditcui, top-level jei; card "Replaced by X", forcedOn re-enables. Every live module, dev-shots 6 PNGs each. Dropped: entityculling (stacks, sliders not a card), tacz crosshair (unverified), barrel roll/keybindfix/bettercombat/tooltip mods (different job). 1.8.9 uses Forge ids freelook/pmr/omnilook/freecam/epiczoomer, real-mod run not done.
- Sodium/Iris/JEI gates (merge 1dcc2ad86): CompatMixinPlugin + per-module originclient.compat-gates.txt ranges; out-of-range pair boots to title with 0 Origin hooks and a "compat gate: X is off" line (1.21.1, 1.21 proven, boot-smoke 40290623254). Ranges must include the launcher's vetted pairs; never `||` in range data.

## 2026-10-26 — Sable launch crash fixed (launcher-v1.0.69)
- Cause (proven from Will's crash log): originclient's CameraMixin `@Redirect` (require=1) on `Camera.setup` -> `setRotation(FF)V` took the call, so `sable-fabric.mixins.json:camera_rotation.CameraMixin` (also a redirect) found 0/1 targets and crashed launch. Two redirects on one call never coexist.
- Fix: `@WrapOperation` in 1.21.1, 1.21.10, 1.21.11 (MixinExtras present there), listed in each `overrides.txt` (shared/ keeps the `@Redirect` for modules without MixinExtras; Sable is 1.21.1-only). javap on the built 1.21.1 jar: wrap on `method_19321` -> `class_4184.method_19325(FF)V`. PR #38 green (first run failed shared-sync drift until overrides.txt listed the fork), merge f36b4f2ff, tag launcher-v1.0.69.
- Unverified: Will's own launch with Sable + freelook in game.

## 2026-10-26 - Version picker "Choose a version" + instant hover
- Picker: each family's dropdown opened with a release preselected (the saved one, else the newest), and a ComboBox ignores a click on what it already shows, so that release could not be picked until another was. Now nothing is preselected (ReleaseIndex -1, Tag "Choose a version"); Down from it takes the newest. VersionSelectModelTests updated + a test for clicking the saved version.
- Hover "takes a second": `--render-shots <dir> --hover-cost` showed the UI thread idle (Input-priority wait ~0.1 ms, drift on or off) and the mouse handled in 2-5 ms; the lag was the 120 ms linear fade from a low-contrast rest colour, starting a frame or two late. Every hover-in now uses Motion.HoverIn (0 ms, lands next frame); hover-out keeps the 120 ms fade.
- Never move Will's cursor: the first timing run used SetCursorPos on the render-shots window; Will: "stop touching my mouse". Removed; rule in DECISIONS.md.

## 2026-10-26 - Dropdown hover dead spots (launcher-v1.0.71)
- Will after v1.0.70: hovering versions in the release dropdown "still is not registering". The 0 ms hover-in was not the cause.
- Cause (proven): the ComboBoxItem template's Border had no Background, so WPF hit-tested only the painted text; and a ComboBox highlights a row only on MouseEnter (decompiled ComboBox.NotifyComboBoxItemEnter: needs IsDropDownOpen + capture + mouse moved). `--dropdown-cost`: 1 of 9 probe points per row took the mouse; fps 250 and hit-test 0.004 ms ruled out speed. The RAM slider had the same flaw (only the 4 px groove).
- Fix: Inputs.xaml rows and slider grids are `Background="Transparent"`. Rule: every hover surface must be painted, Transparent at minimum.
- Guard: `--render-shots <dir> --hover-audit` probes 9 points on every hoverable element across all screens and popups; launcher-shots CI fails unless hover-audit.txt starts "0 ". Local run: 0 dead spots; 2477 tests pass.
- Unverified: Will's real mouse.

## 2026-10-26 - Dark buttons, title panoramas, built-in Sodium translations
- Dark widgets: Origin draws its own charcoal set (`tools/dark-widgets/make_widgets.py`; Default Dark Mode is CC BY-NC-SA, never copied), shipped as jar resources under `assets/minecraft/` so nothing shows in the pack list and a player's pack still wins. 1.20.2+: `gui/sprites/widget/*` override (1.20.2/1.20.4 have no scroller_background). Pre-1.20.2: `widgets.png` also holds the hotbar, so it is never shipped; a texture-id swap inside the button draw instead (OriginDarkWidgets + Dark*Mixin per module; 1.8.9 GuiButtonMixin @ModifyArg). Title cards retuned via OriginTheme TITLE_*. Dev-shots 0 mixin fails on all 20 modules (runs 40450454421, 40450986703, 40451935039, 40453032472). Left vanilla: pre-1.20 scroll bars, 1.8.9's yellow hover text.
- Sodium translations (CC0): v8.21.0 lang in shared/ (en_us, test.json, empty az_az skipped); v1.11.0 hand-copied to 1.17.1-1.19.4; none exists for 1.16.5.
- Panoramas: CI renders each update's scene with Photon (`panoramas.yml`, `[panorama:<ids>]` commit tag, `tools/panorama/`). Traps: Photon needs SH_SKYLIGHT=false on llvmpipe; `Level.getHeight` lies for unloaded chunks; `/locate biome` cell may sit under another biome's surface; `/fill` refuses not-yet-loaded chunks ("That position is not loaded") silently unless logged, which made every lit cave render black, so the harness places `light` layers itself. Will 2026-10-26: dark biomes are fine (keeps mangrove 1.19 and pale garden 1.21.4), never night, caves may be underground, never dull. 26.2 = lit sulfur cave (run 40463430377); the 1.17 lush cave rendered as broken silhouettes and a desert village was no better than the open desert, both kept their originals.
- Traps: `sync.py` writes into the untracked `staged/26.2/src` leftover (delete those files); PowerShell turns an unquoted `-f modules=1.20,...` into 1.2.

## 2026-10-27 - 1.8.9 picks 3-5 A/B and inventory blur (branch claude/p6-189-perf)
- Picks 3 (HUD batching), 4 (minimap), 5 (particle snapshot) all KEPT: switched off one at a time, paired per round (runs 40469380647 at 1280x720, 40477173770 at 427x240). Pick 4 faster in all 10 rounds (p50 -1.0..-1.8 %); picks 3/5 faster in every CPU-bound 427x240 round (p50 about -1 %), but inside the noise at 1280x720 because llvmpipe is fill-bound there. CPU-side picks need the small window to show.
- Inventory blur (G): capture-once on open saved only ~1.4 of 5.0 ms (run 40469384713). The redraw of the captured image plus the tint still costs on llvmpipe. Blur off saved 3.9 ms (+11.9 % avg FPS, every round). Competitive already sets menublur.inventory=false, so capture-once was dropped. Details: docs/overhaul/p6-measurements.md, 2026-10-27 section. CI relative only.
- Bench additions: scene `pvp-ui` (inventory 1 s open / 1 s closed) and variant `off-invblur`. bench-189 is workflow_dispatch only.

## 2026-10-27 - One look on every version: dark title buttons, loading look, in-world smoke (claude/integrate-1073)
- Ported from 1.21.1 (577216cf8, cf4c658da, a5a77715f) to all 20 live modules by era group (claude/one-look-port-g1g2/g3/g4/g5/g6/g7g8/g9); each group green in CI with in-world PASS and 0 mixin-apply failures.
- Verifier (judge 85) caught what the ports missed: CLEAN_BG stayed #0B0C0F on 1.21.6+ and 26.x and pre-1.20 (spec #0E1110); every module but 1.21.1/1.21.6+ still used LoadBar.scene (sweep = fill at "0%"), replaced by 1.21.1's sceneRail; 1.20's Language/Accessibility stayed light stone (ImageButton art has stone baked in) -> Origin glyphs over the dark 9-slice. Lesson: parity briefs must list every constant/helper the look depends on, not just the files.
- Title footer showed "00B7" on 7 modules: a45d654e6 lost the backslash of `·` (shell escape). Fixed f1ce11738.
- Era facts: RealmsLongRunningMcoTaskScreen declares render before 1.20.2, repositionElements after; 1.21.6+ loading screens with no own renderBackground get it overridden by the mixin; 1.21.10+/26.x Screenshot.grab is async (finishGrab, then list the folder); pre-1.20 boot font measured space as 0 wide ("Loadingresources"). Detail in the origin-port recipes.
- 26.3 shows Realms in the dock (the teenyicons rack) as before; 26.2+ keep Mojang's Friends button.

## 2026-10-27 - v1.0.74: local panoramas, 1.8.9 download/title/blur/HUD-editor fixes
- Panoramas now render on Will's PC (tools/panorama local path, Complementary + far terrain). Harness traps: a spot pinned by `at` with no locate left the command recorder null (26.2 came out unlit); cave mode must reject bedrock floors (nether roof); canopy spots must require the biome under the camera. Rule (Will): each update's one highlight where it really generates, no placed blocks or out-of-biome mobs.
- 1.8.9 install froze on the last file: CmlLib 4.0.6 reads bodies with no token/timeout and HttpClient.Timeout stops at headers. Fix: Core/Net/DownloadHttp stall guard (stall = TaskCanceledException wrapping TimeoutException, the HttpClient-timeout shape, so existing fail-soft filters catch it) + RetryingGameInstaller. CmlLib's Dataflow pipeline silently DROPS a file that fails cancel-shaped, so the installer converts the final failure to TimeoutException. 1.8.9's "967 files" included 192 jre-legacy files, now skipped when Temurin is ready.
- 1.8.9 title blur/floor view came from vanilla renderSkybox (256 px + 7 blur passes) and drawPanorama's sin pitch; MenuPanorama now draws the cube itself (pitch 10, 0.1 deg/tick, 85 deg FOV).
- 1.8.9 inventory blur defaults off in Mods + every preset; Mods.upgradeOptions() drops only a saved `true` once (a dropped key syncs as a deletion to every version, so never drop a value that already equals the new default).
- 1.8.9 Origin screens read input only in runTick (20 Hz): drags updated on 37% of frames. Now handleInput() runs at RenderTickEvent START (after runTick, before render; javap), mid-frame-unsafe actions deferred to ClientTick START; editor draws 260 -> 39 per frame.
- Open: 1.8.9 sometimes wedges after Save and Quit right after "Holder lookups applied" (seen twice in CI, also on main-based code); being root-caused on claude/fix-189-quit-freeze.
- 2026-10-28 1.8.9 Save and Quit freeze = vanilla race: the server stops on the closed connection before running the log-out task IntegratedServer.initiateShutdown waits on with no timeout (dump: client parked in initiateShutdown -> Futures.getUnchecked, no server thread). Fixed by IntegratedServerShutdownMixin (stop waiting once the server has stopped). Stress it with dev-shots input inworld_loops=12; wedges get thread dumps from tools/ci/thread_dump.sh. Shipped in v1.0.75.

## 2026-10-31 - Pre-publish check on v1.0.75
- Tests pass locally (launcher 2514, installer 63). Will confirms real Microsoft sign-in works on v1.0.75; the 403 note in CLAUDE.md was stale and is corrected.
- Left before going public: website `DOWNLOAD.live` is false; Discord `inviteCode` is still REPLACE_ME; the v1.0.75 installer has 0 GitHub downloads; real-PC checks (FINAL-REPORT "Not verified") not done.
- Mods vs Modrinth on 26.3, 1.21.11, 1.21.1: only 26.3 Lithium is behind (0.26.2, newest 0.26.3). 26.3 ships Sodium 0.9.3-beta.1 while stable 0.9.2 exists; its catalog comment still describes 0.9.2.
- `sync.py --check` fails on Will's PC for local reasons only: the untracked `staged/26.2` leftover (141 files), and 3 OriginForeignWidgets copies differ in the 26.x modules, likely CRLF working copies (one confirmed w/crlf, comparison not finished); CI's drift gate is green on the release commit.
- Went public the same day (Will's call, before anyone ran the installer): website `DOWNLOAD.live` true, Discord invite z3SbasGCKR in promos.json. launcher-v1.0.76: 26.3 on stable Sodium 0.9.2 + Lithium 0.26.3 (boot-smoke 41234871378 production-style PASS, 0 mixin failures; dev-shots 41234868855 119/119 PNGs). pin_history.py reads git history, so commit a new pin BEFORE regenerating or ManagedPinsTests fails.

## 2026-11-02 - Weekly permission scan
- Added 20 read-only allow rules to `.claude/settings.json` from the 50 newest transcripts. Left out Select-Object, Where-Object, Sort-Object, Format-*, ForEach-Object, awk, git grep and ffprobe: each can run code or write files through an argument.

## 2026-11-02 — 1.8.9 Raw Mouse Input
- Will: 1.8.9 mouse feels accelerated, 1.21.1 doesn't. Cause: LWJGL 2.9.4 WindowsMouse.handleMouseMoved builds deltas from cursor positions (no DirectInput classes in the jar; the old Mods.java comment was wrong), so Windows Enhance Pointer Precision applies (Will's registry: MouseSpeed=1, on); 1.21.1 uses GLFW raw motion.
- Fix: RawMouseInput (JInput raw plugin from MC's own libs, polled per frame on the render thread) + MouseHelperRawMixin, General > Raw Mouse Input row, default on. Falls back to LWJGL deltas for the session on load failure, no device, or 30 frames of cursor motion with no raw counts.
- Proof: build + refmap (func_74374_c, func_74372_a); plugin loads and lists 5 mice on Will's PC outside the game. Real camera feel not proven: CI is Linux, so raw input is Windows-only there. Gated deploy left.

## 2026-11-02 - Installer failed when run from a Mac shared folder
- Player on Parallels ran 1.0.76 Setup from `\Mac\Home\Downloads` (log paste shows one backslash, markdown ate it): download verified, then `File.Copy(self, uninstall.exe)` threw FileNotFoundException every time, after the launcher was moved into place, so no shortcuts or registration. Why the share refuses the copy is not proven; no Parallels here.
- Fix: uninstall.exe is staged with the launcher, before the point of no return. Plain stream read, else the release's own Setup from the feed (size/SHA-256 from the API, `MZ` check always), else "move Setup to this PC" with nothing changed.
- Proof: OriginSetup.Tests 72/72 on net8.0; stream read works over `\localhost\C$` on .NET Framework; render-tour 63 PNGs. `dotnet test --framework net472` runs 0 tests (compile check only). A real Parallels run is still unproven.

## 2026-11-02 - Launcher moved to .NET 10 LTS
- Why: .NET 8 support ends 2026-11-10 (Microsoft support-policy page, read 2026-11-02); .NET 10 LTS runs to 2028-11-14. App, Tests, UiTests and OriginSetup.Tests now target net10.0; OriginSetup stays net472.
- Root `global.json` (10.0.100, latestFeature) is the one SDK pin; every CI dotnet job uses setup-dotnet `global-json-file`. dotnet finds global.json from the CURRENT folder, not the project path (launch-time.yml now publishes from inside each checkout).
- Breaking-change scan (.NET 9 + 10): only system-DLL P/Invokes (single-file native search change is harmless), no empty Row/ColumnDefinitions, all 30 DynamicResource uses type-correct, no BinaryFormatter.
- Proof, commit ed1c14ba5: build-check green (Setup tests 63/63, launcher tests 2507 + 7 skipped, UiTests 3/3, 20 jars bundled, installer smoke); launcher-shots 300 + 165 shots, 452 clip checks clean; release rehearsal 41565482913 green, same asset names; cold-start.yml (new) on one runner: v1.0.77 first run 3615 ms / median 739 ms, .NET 10 first run 1258 ms / median 847 ms, same file set, exe 200.0 -> 178.7 MB.
- Not done locally: Will's PC has only SDK 8.0.422, so local `dotnet build` of the launcher needs the .NET 10 SDK installed first (Will started the winget install 2026-11-02).
- CET: .NET 9+ marks the exe CET-compatible by default; `CETCompat=false` keeps .NET 8's behaviour so an old overlay/antivirus hook can't crash the launcher (Will chose "match today", 2026-11-02).
- Self-update proof, commit fead26303: cold-start.yml installs launcher-v1.0.77, stages this ref as its update and replays UpdateService's apply-update.cmd (wait for pid, robocopy, restart): exe became 1.0.9999, every file hash matches, updated launcher drew its first frame at 728 ms. Timings same run: v1.0.77 first 874 / median 719 ms, .NET 10 first 845 / median 807 ms.
- Unproven: Windows 10 consumer editions (22H2 Home/Pro) are off .NET 10's official OS list; very likely run, not tested.

## 2026-11-02 - Other players' cosmetics never showed on busy servers
- Cause: D1 refuses a statement with >100 bound values. `/v1/players/equipped` with the mod's full 100-player batch bound 101 (membersAmong, inGameAmong: list + `now`) -> HTTP 500; the mod retries the same 100 together every 60 s, forever. Live proof: 98/99 players 200, 100 players 500. Own cosmetics use the one-player GET, so they kept working.
- Fix (service only, no release): `rowsIn()` in services/cosmetics/src/common.js slices IN-lists at 90. test/d1.mjs now enforces D1's 100-value limit (node:sqlite allows far more, which is why tests never caught it); new 100-player test fails on the old code.
- Render path proven fine on CI: a second player with real looked-up cosmetics renders on 1.21.1 (branch claude/remote-cosmetics dev-shots probe, not merged).

## 2026-11-02 - Esc/pause-menu blur pixelated on 1.8.9, 1.16.5, 1.17.1
- Cause: Menu Blur shrank the frame to 1/8 through a LINEAR halving pyramid and bilinear-stretched it back. A bilinear stretch of a 1/8 image is piecewise linear between samples 8 screen px apart, so every edge shows an 8 px grid. The 2026-10-20 "climb back in 2x steps" fix composes to the same thing, which is why it didn't hold. 1.16.5/1.17.1 stretched 1/8 straight back (worse).
- Fix (PR #41): the full-resolution chain 1.18.2-1.20.4 already ship (vanilla `blur` program, H+V at R, R/2, R/4). 1.8.9 runs it as a standalone ShaderGroup (`assets/minecraft/shaders/post/origin_menu_blur.json`, last pass writes a private `result` target kept for the frozen-world reuse), only when `WorldHooks.postChainsUsable()` (otherwise vanilla's gradient, which has nothing to pixelate). 1.16.5/1.17.1 run a self-managed PostChain (`originclient:shaders/post/menu_blur.json`).
- Every other live module already blurred at full res (PostChain or vanilla processBlurEffect); a grep found no other pyramid.
- Proof: boot-smoke green on the 3 modules; dev-shots world set 18/17/17 PNGs, shaderpack world set 29/29 (1.16.5/1.17.1); zero Mixin apply failed, zero "Menu Blur failed"; pause shots smooth. OptiFine-shaders-on 1.8.9 path (vanilla gradient) not shot in CI.

## 2026-11-02 - 1.7 Animations on every live version (launcher-v1.0.81)
- New mod `oldanimations` ("1.7 Animations"): off by default, 9 switches on once enabled (Combat: Sword Blocking, Block Hitting, Swing While Using, No Hit Dip; Held Items: 1.7 Item Positions, 1.7 Eating and Bow; Extras: Instant Sneak, Red Armor Flash; Third Person: Third Person Blocking). Built on 1.21.1, ported to all 20 live modules in 9 era groups (origin-port), each group reviewed + verified from CI artifacts.
- Will's rule: shields always work and look normal. Sword blocking is LOOKS ONLY: local player, no screen, right-click held, nothing in use, sword in main hand, off hand not a shield/BlockItem. A raised shield is never touched (first person, F5, swing hooks).
- Proof per module: HOOKS table + `missingHooks()` fails the dev-world in-world step; case 11 FAILS unless the off-hand shield really raised (`shield raised normally`); shots `inworld_oldanim_{idle,shield,block,page}`. 1.8.9 drops Sword Blocking / No Hit Dip / Instant Sneak (native there).
- Trap: on 1.21.5+ `isGui3d` is gone and `!usesBlockLight` alone also matches resource-pack 3D swords; flat = front-lit + no special renderer + model depth <= 1/16, cached per held stack. 26.3 split first person into FirstPersonHandsAndItemsRenderer/FirstPersonHandsAndItems. Full era table: origin-port `recipes/particles-entities.md`.
- Open (Low): the "sword block raised" smoke line only logs, never fails; the flat cache survives a resource reload until the held item changes. Unverified: feel and F5 look in game, OptiFine on 1.8.9, a real 3D-sword pack, Forge mod id of older-animations for 1.8.9 step-aside.

## 2026-11-03 - Bug sweep: ~75 fixes across the launcher and all 20 live versions
- How: 5 read-only hunt rounds (launcher, mod-menu config, 1.8.9/Hypixel, social/HUD, HUD readouts/camera, Origin screens, in-world/perf), fixes as specs A-F (side-session notes `bug-sweep-spec-*.md`), split 1.20+ (shared) vs pre-1.20 + 1.8.9, each batch reviewed (opus, all pass after Low fixes) + CI build-check/dev-shots on every module.
- Root causes worth remembering: bundled default config had out-of-range values (now `tools/mod-menu/check_default_config.py` in build-check); Chat mod overrides reached options.txt via OptionsAutosave (now swapped out around Options.save); Toggle Sneak never really sneaked (now KeyMapping.isDown like Toggle Sprint) - this was the "can't place blocks while sneaking" report; PingSampler counted the server's join-time 0; Freelook now off on Hypixel (banned there).
- HUD: HudPos clamps on read (pieces can't be lost off-screen); real scoreboard now honours its options and real size; potion levels; CPS counts the bound keys; default HUD spots no longer overlap; HUD/colour/item-size drags save once on release.
- Camera/world: zoom/freelook reset on world leave, one shared saved perspective for Snaplook+Freelook; Motion Blur re-applies after F5/reload/spectate (vanilla clears the post slot there; never fights a shader pack, backs off); own nametag follows vanilla F1/invisible rules; Snow snows everywhere (client-only precipitation hooks, 1.8.9 client-thread flag).
- Data: singleplayer waypoints keyed by MC version (old file copied, never moved); `originclient-shaders.json` is merge-only under Settings Sync (never rebuilt from one game's view); death waypoint replaces only Origin's flagged marker.
- Launcher: Origin's own jars recognised before the first Play (background hash, Checking state, set-aside leftovers hidden); manual RAM capped by one `RamAdvice.ManualMb`; Settings Sync turned on mid-game harvests that game on exit (`SeedPending`).
- Hypixel: boxed messages split per line before matching (auto-accept, Team View); lobby statuses matched by an anchored pattern (player chat stays).
- Left by choice: starter config keeps Zoom/Freelook/Coordinates off (Will's 2026-07-10 seed); block outline alpha 0 -> opaque (RGB-only colours); F1 hides capes. Unsourced: whether Hypixel bans Snaplook/auto text/waypoints (not blocked).
- Unverified: feel in game (Will's pass), OptiFine + Snow on 1.8.9, Mods page with real jars after a fresh launcher start.

## 2026-11-02 - One zoom only (1.8.9 OptiFine + player zoom mods)
- 1.8.9: OptiFine's own Zoom key (C) ran beside Origin's Zoom. While Origin Zoom is on, WorldHooks parks `ofKeyBindZoom` (key 0, row hidden from Controls) and restores the player's key around every saveOptions (GameSettingsZoomMixin HEAD/RETURN), so options.txt keeps their binding. Commit 237fcc958.
- Every other version: StepAside gains `zoom` -> zoomify, ok_zoomer, logical_zoom, wi_zoom (shared + the 6 pre-1.20 copies), so a player's zoom mod turns Origin's off.
- Unverified: no CI shot shows OptiFine's Controls screen; proven by compile + boot only.

## 2026-11-02 - Linux launcher (AppImage, .deb, .rpm, tar.gz; x64; launcher-v1.0.82)
- src/OriginLauncher.Linux (net10.0) hosts the cross-platform Avalonia UI in src/OriginLauncher.Desktop (code-only, meant for the Mac port too) over Core's `.Linux.cs` seams. Data `~/.local/share/OriginLauncher`; accounts sealed AES-256-GCM with the key in the Secret Service (secret-tool), else a 0600 key file bound to /etc/machine-id. Sign-in: NativeWebView (WebKitGTK, recommended by the deb/rpm) with a paste fallback.
- Self-update: AppImage and tar.gz folder swap in place after SHA-256; deb/rpm update a per-user copy in `~/.local/share/OriginLauncher/app`, and `/usr/bin/origin-launcher` runs whichever VERSION is higher.
- arm64 not shipped: Mojang has no linux-arm64 Java or natives.
- Review fixes (8deca56b8, 904426095, b547c0008): `$APPIMAGE` trusted only when our own files live inside that AppImage's `$APPDIR` (a parent AppImage leaks the variable); deb/rpm start-at-login uses `/usr/bin/origin-launcher`; a swap that failed after restart counts once as DownloadFailed (marker per version) instead of locking Play; launcher-release still publishes Windows when the Linux job fails.
- Trap: the packaged tar.gz/AppImage self-test piped through `tee`, which drops the exit code, so 2 test-side FAILs stayed green (locked keyring outside the unlocked D-Bus session; Exec expectation hard-coded where an AppImage writes `$APPIMAGE`). Fixed db4d80bd1 with pipefail + the same unlocked session.
- Proof: Linux CI self-test PASSED on x64 + arm64 builds; install self-test on ubuntu 22.04/24.04, debian 12, fedora latest, almalinux 9; BOOT-TEST zero mixin-apply failures for 1.21.1, 1.8.9, 1.20.1, 1.21.11, 26.3; 195 Linux launcher shots match Windows; Windows build + 2535/2535 tests + check_consistency OK; release rehearsal attached 4 files + .sha256.
- Unverified: a real Linux desktop (sign-in, keyring in a real session, start at login, drag-and-drop, a real self-update). Fedora 45 AppImage/tar.gz need openssl3-libs. Some Windows wording still shows on Linux screens (C:\Games example, backslash import paths).

## 2026-11-03 - Mac launcher (Mac Beta; Apple Silicon + Intel; launcher-v1.0.84)
- src/OriginLauncher.Mac: Avalonia 12.1.5 + Avalonia.Controls.WebView 12.1.1 (WKWebView, non-persistent store), net10.0, every Windows screen twinned (310 main + 165 social shots, 0 missing twins). Core linked via `<Compile Link=>`, per-OS seams `X.Windows.cs` / `X.Mac.cs` / `X.Linux.cs`. Data `~/Library/Application Support/OriginLauncher`; accounts AES-GCM with the key in the Keychain via `security -i` (app-ACL keychain items die on every ad-hoc re-sign; `security` CLI items survive).
- Launch: MacLaunchPolicy picks per version: 1.19+ native arm64; 1.8.9/1.16.5/1.17.1/1.18.2 x64 under Rosetta (install offer when missing); MojangMetadata.RuntimePlatformFor(version) picks the Java arch. Proof switches: --self-test, --render-shots [--social], --launch-plan, --play-plan, --signin-probe, --update-proof (loopback IP feed only), --list-versions.
- Release: Mac jobs run after the Windows release job succeeds, read-only token + persist-credentials false; only mac-publish writes (gh release upload, 6 files: OriginLauncher-osx-{arm64,x64}.zip + .sha256, Origin-Mac-AppleSilicon.dmg, Origin-Mac-Intel.dmg). Concurrency on the release job only + "keep Latest on the newest tag" guard on both publish steps. Ad-hoc signed, Info.plist min macOS 15.0, icon only 256 px. x64 must build/prove on macos-15-intel: under Rosetta the web view crashed.
- Self-update: a translocated/unwritable copy is never offered the update (chip says "Move to Applications"); swap script refuses leftover folders. Trap: main's `UpdateService.Enabled` (off on any scratch root) silently disabled --update-proof (which runs on one) after the Linux merge; fixed with `UpdateService.ProofFeed` set only by UpdateProof.
- Avalonia traps (proven): Link= per resource; transitions inline in setters; IconPath; plain hyphens wrap (use U+2011); RenderTargetBitmap above 96 DPI doubles BoxShadow children; headless DispatcherTimer never fires (shots drive state directly); focus rings need AdornerLayer.IsClipEnabled=False and a stand-in frame headless; CaptureRenderedFrame crops are pixels; on macOS IOException.HResult is the raw errno (28/69 = disk full).
- Two Avalonia UIs now exist: Linux hosts code-only src/OriginLauncher.Desktop, the Mac its own axaml project (built in parallel sessions). Folding one into the other is open work.
- Website: one download section for three systems (visitor's own OS is the big button; Mac shows Beta + the Open Anyway steps; others as capsules); privacy page names the Mac data folder + Keychain.
- Unverified (no real Mac yet): window opening, a real game launch, shaders, real sign-in (JS redirects, MFA), Gatekeeper Open Anyway, a real update swap + reopen. Home promo banner not ported (off on Windows). Disabled buttons a little more faded than Windows.

## 2026-11-03 - Resource Pack hub: Picks + copy filter (all 20 modules, branch claude/pack-picks)
- Picks = the hub's first tab: tools/packs/picks.json -> `verify_picks.py --write` generates the table in every module's PackPicks.java (Java 8, byte-identical; run.py + build-check enforce). Sources: Modrinth (project id + uploader pinned, normal Modrinth hash check), direct (pinned SHA-256/size/hosts, PackInstaller.startDirect; none ship yet: Faithful's own 1.8.9 build is on Modrinth from Faithful), link-out (PvPRP pages, Vanilla Tweaks).
- Copy filter = PackDedupe on PackCatalog.publish (paging stays on the raw list): strict title, loose title (no res/version/filler), long-description keys; a group never holds two projects by one uploader; generic-only titles never merge; picks' originals win, known copies + other uploaders of a pick's title hidden. Footer says how many copies were hidden.
- Curation runs in CI (.github/workflows/pack-picks.yml): discover.py + verify_picks.py write text to the `claude/pack-picks-data` branch (fetch + grep it; blob-storage log URLs are blocked from cloud sessions). Dropped: Blade 16x + Pxrple 16x (org uploads, creator not confirmable), Dewier/NicoFruit (no original source).
## 2026-11-03 - Sweep follow-ups: update pop-up, clock-proof friends, chat copy, 1.8.9 music, small updates (launcher-v1.0.85)
- Play during a required update opens an "Update required" ConfirmOverlay with an Update button (HomePage.UpdateRequiredRefused -> MainWindow.AskToUpdateAsync); render-shots scene `update-required`.
- Friends with a wrong PC clock. Root cause: the clients used the PC clock for the /v1/auth proof time (Worker accepts +-5 min), the Mojang cert expiry and the Origin token expiry. Fix: ServerClock keeps an offset from the HTTPS `Date` header (Origin answers + Mojang's certificate call) in the launcher and all 20 mod modules; shown times are shifted back onto the PC clock; the launcher re-signs once on a clock 401. No Worker change. TLS and Microsoft sign-in still need a roughly right clock.
- Right-Click to Copy (chat option, on by default) on all 20 versions. It works with the Chat card off (off by default), so it checks only its own toggle; Origin's timestamp/(xN) stripping is gated on the Chat card being on. 26.3 renumbered mouse buttons (LEFT=1, RIGHT=3): always use InputConstants. 1.21.11+ lost the private chat-line lookups, so the clicked line is found by replaying captureClickableText. Pre-1.20 Loom 1.17 jars have no refmap: grep the built mixin classes for intermediary names.
- 1.8.9 music starts off once per game folder via a marker file `config/originclient-music-off-189.done`. NOT a meta flag in originclient-mods.json: Settings Sync copies that file (meta included) into every instance, so a flag there would skip new accounts (review find). Settings Sync also keeps 1.8.9's soundCategory_music out of the store (ISyncFormat.StaysIn), so the 0 doesn't mute other versions.
- Small updates: releases publish OriginLauncher-win-x64.delta-from-<old>.zip (+ .sha256) for the last 3 releases (tools/release/delta.py, rsync-style patches, header ODPATCH1). The Windows launcher stages copy + delta, checks every file against the manifest SHA-256, and falls back to the full zip on any failure. Measured on real releases: 12-47 MB instead of 266 MB (1.0.83->1.0.84: 38.7 MB). Whole-file deltas alone saved nothing (the 89 MB single-file exe and every jar change each release); patches are what make it small. The delta steps carry their own timeout-minutes because continue-on-error doesn't catch the job's 30-minute limit. Mac and Linux still download the full file. First release whose players benefit is the one after v1.0.85.
- Proof: CI [shots:all] on 71a927f1e: all 20 versions 107-125 PNGs, 0 mixin-apply failures; 2810/2810 launcher tests; delta round trip byte-identical on 1.0.83->1.0.84 and 7 tamper cases refused (verifier); code-reviewer opus passes after fixes.
- Unverified: right-click copy and the clock fix in a real game session; a real delta update on an installed launcher (needs the next release after v1.0.85).

## 2026-11-03 - v1.0.86 hotfix + v1.0.87: bug runs 2 and 3 (~110 fixes), Pack hub picks (cloud session)
- v1.0.86 (hotfix): Mac launch fixed (CmlLib DockName emitted `-Xdock:name` `Minecraft` as two argv -> java ran "Minecraft"; now one `-Xdock:name=Minecraft`, JavaArgv check + real `--start-game` CI step), 304 retried, 1.21.2/1.21.3 world crash (1.7 Animations used 1.21.4-only ItemStackRenderState; 1.21.4 CompatMixinPlugin fork skips OldAnimationsHandLayerMixin below 1.21.4), 1.21.1 FireworkStarterMixin (+ CI gate every Fabric module lists it).
- Bug run 2 (26.x, 1.21.11, 1.21.1+shared, 1.8.9; 34 found): one canonical keybind format in originclient-mods.json (GLFW codes, mouse = -100+b, -1 unbound; util/KeyCodes, 26.3 SDL3 table) - pre-fix 26.3 / 1.8.9 mouse binds may need one rebind; screenshot chat links are minted tokens only (security); Modern Mouse toggle on 1.8.9 only (raw input + no look delay), Raw Mouse override removed on 1.16.5+; hitbox invisibles, Clear weather keeps border, O-3 Redirect->WrapOperation, per-group Sodium/Iris signature checks (0.6.5 keeps the gate, only the walk group switches off); step-aside only for fighting mods (Will: minimaps stay on).
- Bug run 3 (launcher, Mac/Linux, installer, website, store service, in-game; 78 found): SettingsStore fails closed (never writes defaults over an unreadable settings.json; callers catch IOException), config-overlays shared again, uninstall trusts only origin-install.json, release publish job is the only writer, Terms "all sales final", rate limits + Appear offline in the service, WorldEdit CUI clamps server numbers.
- Pack hub: 31 picks built into the jar (27 Modrinth, 4 open-page tiles: PvPRP terms forbid automated downloads), copies hidden in Browse (330 tests).
- Traps: the cloud proxy refuses tag pushes (403) - Will pushes tags from a PC chat; never `git fetch` claude/dev-shots (31 GB); release plan now fetches only main + tags (fetch-depth 0 pulled dev-shots: 50-90 min per release).
- Open: E-5 shader-pass overlays on 1.21.11/26.x; 1.8.9 pack thumbnails blank; no CI shots for HUD readouts/hitboxes/weather/nametags; 1.8.9 Forge ids for step-aside; Stripe key needs PaymentIntents Read + Subscriptions Write for chargebacks to end Origin+.

## 2026-11-03 - v1.0.88 candidate: post-v1.0.87 follow-ups (cloud session)
- Launcher: per-game log copy stops at 50 MB (Core/Launch/GameLogFile, all 3 launchers); chromeless window answers WM_GETMINMAXINFO with the monitor work area (snap-maximise keeps the taskbar); locked keyring/Keychain -> KeyStoreLockedException -> Play says "unlock it and press Play again".
- 1.8.9: Modrinth pack/shader thumbnails were WebP (Java 8 ImageIO has none) -> relocated TwelveMonkeys 3.12.0 shaded + gui/WebPImages registration. Step-aside Forge ids: djperspectivemod, bettersprinting, blur, motionblurmod (others unconfirmed, left out). The 1.21.1 HUD-editor "0.6/1.5" sample is the Cooldowns HUD (impossible on 1.8.9). Named mobs show no tag unless under the crosshair: vanilla 1.8.9 (EntityLivingBase.getAlwaysRenderNameTagForRender() = false), not Origin.
- E-5 after-shaders overlay pass ported to 1.21.10, 1.21.11, 26.1, 26.2, 26.3 (hooks before/after GameRenderer.renderLevel's level call; Iris composites there). Deferred: 1.21.6, 1.21.8 (older world events). 26.3's extra dock icon = Mojang's Realms button.
- HUD: hud/HudLayout is the single default table (shared + no-sync copies + 1.8.9); stored old defaults resolve to the new spot, nothing written back; tools/hud-layout-tests guards overlaps (29 pieces, 5 sizes). Party/Portal Calc/Bed Wars Teammates may reach into chat at default GUI scale; Minimap shares the scoreboard spot.
- Dev-shots world set has DevFeatureShots (hud_readouts, hitboxes, rain, snow, nametags_f5) on a cleared stage; 1.21 pack hub existed, only its shots were missing. ref-189 gained `code <Class>#<method>` (javap -c).
- Proof: boot-smoke all 20 in-world; dev-shots rel088w/rel088sh ('after-shaders pass active' on 1.21.10/26.1/26.2), hudlay/hudlay2, 1.8.9 rel088 (111 PNGs, thumbnails shown).
- Open: real-cloud shader check (Complementary) by Will; pre-1.20 modules have no feature shots; 13 modules lack pack-hub shots.

## 2026-11-04 - v1.0.89: QoL run-through on every version + Will's requests (cloud session, overnight)
- Will's asks: one-click Home bring-over button (Win/Mac/Linux; LunarFold makes .minecraft Lunar's only when Lunar's launcher.json names it); 1.8.9 Origin startup screen (SplashProgressMixin/ForgeLoadingPumpMixin/MinecraftSplashScreenMixin, launcher ForgeSplashOff); borderless re-fit while on (BorderlessFit: monitor the window is on, restore maximised first, stop after a no-op re-fit) + alt-tab mouse release (1.8.9 MouseHelperUngrabMixin, 26.3 InputConstantsMouseMixin; GLFW already ignores warps unfocused); in-world menus never blur (MenuBlur.darkenOnly, Menu Blur default off, marker optionVersion.menublur.enabled=2).
- QoL hunt (4 hunters, ~42 findings): 1.8.9 reload overlay/inventory centring/toast stacking/async F2/chat history/F3 HUD/hover text; pre-1.20 key repeat + dark scrollers; 26.x pointer shapes + loading cover; after-shaders overlay pass now on every live version; launcher FirstRunOptions (add-only first-run keys) + mods/1.8.9 adoption; shared: HUD visible unless minimised, ProgressScreen guard, F3 HUD, KeyTaps, B/J/Y defaults (OnceSteps marker optionVersion.defaultKeys), menu FPS = Max Framerate, click sound, WindowTitles, Fly Boost creative-only, transparent/solid one switch, pause Mods -> Origin menu; parity port to 26.x/pre-1.20/1.8.9.
- Two code reviews (fixed: once-only key migration, toggle tap leak, F2-then-quit flush, borderless no-op stop, transfer account switch/Lunar leftover/close-cancels).
- Proof on ae09fe83: build-check, launcher-shots (bring-over 17*), Mac, Linux, boot-smoke all 20 in-world + all borderless=true, dev-shots design rel089 (132/129/131/113/125) + world rel089w (23/23/25/23/18), 0 mixin failures.
- Traps: a bare `git fetch origin` pulls claude/dev-shots (31 GB) - fetch named refs only; two boot-smoke dispatches on one ref cancel each other (concurrency group); an interrupt from Will mid-turn kills just-launched background agents silently (check their worktrees).
- Open: real-PC checks (OptiFine 1.8.9 startup, borderless at 133%, Complementary clouds, pointer shapes, held Backspace, transfer on a real Lunar install); SVC first-run hint left (finishing it enables voice chat); delete claude/fix-borderless-switch-probe.

## 2026-11-04 - v1.0.90: Mods = mod list, new Origin button; bug run 4; background updates; site unfrozen (cloud session)
- Title Options | Mods | Origin and pause Mods | Origin on all 20 modules: Mods = Mod Menu list (gui/PlayerModList; Forge GuiModList on 1.8.9; falls back to Origin's menu if Mod Menu is gone), Origin = Origin's menu. Pause row template = widest BUTTON only (1.20.2+ title is a screen-wide StringWidget).
- Bug run 4 (3 hunters on v1.0.88-89 code + live site): F3-combo suppression (KeyTaps.inF3Combo), 1.8.9 whole-HUD-on-F3, Transparent Menus restores saved Solid value, discardBank frees buffers 1.17.1-1.21.8, loading screens 60 FPS (render/LoadingScreens), LoadingOverlayCoverMixin 1.21.4-1.21.11, profile defaultKeys marker, no click on empty space, 1.8.9 startup resize invoker + F2 queue cap 3.
- Launcher: updates now prefetch in the background (UpdatePrefetch state machine, StageSnapshot re-hash right before swap, per-version stage lock, waits for games/metered/offline); auto-hide taskbar 2 px; ReturnMisplacedShared scans every account; store head from crafthead UUID.
- Website: Vercel had failed EVERY production deploy since v1.0.87 (ignoreCommand 414 chars > 256 limit); fixed (255 chars, check_site.py guards it); live CSP verified on phones (runner Playwright: no errors, try-on, Checkout, Terms line).
- Proof on 4215c042: build-check, boot-smoke 20/20 in-world, dev-shots design rel090 (132/129/131/113/125/123) + world rel090w (23/23/25/23/18/19), 0 mixin failures; launcher-shots/Mac/Linux/website-check green on 51e29217 (launcher unchanged since).
- Traps: dev-shots module names are module dirs (1.20 covers 1.20.1); originclient.org is blocked from the cloud session - use a runner (temporary claude/store-live-check workflow) for live checks; branch deletes and tag pushes are refused here.
- Open: first real background update is the release AFTER v1.0.90; click "Mods" once in game (CI can't click).

## 2026-11-04 - v1.0.91: bug + inconvenience runs 5 and 6 (cloud session)
- Triage rule (DECISIONS): only clear bugs are fixed; maybe-intentional findings go to Will as a list (bugrun5/6 ASK-WILL.md).
- Run 5 (player journey): launcher (neutral line after a normal quit, Windows device-code sign-in fallback, Update Cancel while a game runs, Mac/Linux Update required, Sign in again, SettingsStore.TryUpdate on clicks, FeedPoll ETag/backoff, bring-over refusal shown); in game (HotkeyChord for Auto Text, Waypoints off banner + Turn on, nametag box drawn once behind the name under Iris on 1.20-1.21.4 (root cause: Iris + Text Shadow double box), pre-1.20 cosmetics id cache, 10 baked motion-blur chains 1.21.4-26.3, transparent HUD panels skip baking, OptionsAutosave ignores zoom).
- Run 6: AccountStore damaged-file handling, installer ARP-first + soft finish steps, uninstaller RunningGames (per-folder, fails closed), Settings Sync (packhub lists stay per version, meta/profileApplied carries profile deletions, future timestamps capped), crash analyzer nested-jar ids with Origin-host priority + LaunchedMods record + per-account+version retry guard, PlayerOverrides notices at Play/Mods, Fix Compatibility timeout, ModAddQueue, numeric duplicate keep-newer, InUse message, Mac/Linux Raise memory = Manual, ChatText = service cleanText.
- Proof on a8344280: build-check, Mac, Linux, launcher-shots, crash-plant, boot-smoke 20/20 in-world, dev-shots design rel091 (132/129/131/113/125/123) + world rel091w (23/23/25/23/18/17/18), 0 mixin failures; 1.21.1 nametags white.
- Next run notes: Settings Sync key deleted then set again outside a profile switch is removed on next sync; Mac/Linux crash check uses saved RamMb not the real heap; nametags under Iris unchecked on 1.21.6/1.21.8 (feature shots only on 4 modules); Home running-line notice is cut off with an ellipsis.

