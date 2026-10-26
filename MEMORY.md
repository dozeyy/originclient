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
