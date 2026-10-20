---
paths:
  - "src/mods/**"
  - "tools/mod-menu/**"
  - "tools/shared-sync/**"
  - "tools/boot-smoke/**"
  - ".github/workflows/**"
---
# Mod-side detail (loads only when working on the mods)

Moved verbatim from CLAUDE.md on 2026-10-17 (Claude Lean) so sessions that never
touch the mods don't pay for it. `src/mods/VERSIONS.md` stays the single source of
truth for per-version status.

**Porting a feature to other versions** ("port X to every version", "bring to
1.20 / 26.x / 1.8.9"): use the `origin-port` skill (`.claude/skills/origin-port/`).
It holds the era map, era groups, per-category recipes (proven hooks and traps), the
brief template and the scoped CI verify loop.

## Supported versions (full detail)
**Fabric, plus 1.8.9 on Forge.** 1.20, 1.20.1, 1.20.2, 1.20.4, 1.21, 1.21.1, 1.21.2/3/4,
1.21.6/7, 1.21.8, 1.21.10, 1.21.11 are LIVE, **plus the pre-1.20 set 1.16.5,
1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 (LIVE 2026-07-15, launcher-v1.0.24)**.
**1.8.9 LIVE again (2026-10-13, Will's call) on Forge + OptiFine** with the
full current Origin experience (`src/mods/versions/1.8.9/PORT-189.md`).
**26.2 LIVE 2026-10-13** (ported from 1.21.11; unobfuscated, Java 25 — the 26.x era,
see PORTING-ERA-NOTES "26.x era"), **plus 26.3 LIVE 2026-10-13** (`versions/26.3`, derived from 26.2;
SDL3 + renderpearl render API — PORTING-ERA-NOTES "26.3"), **plus 26.1, 26.1.1, 26.1.2 LIVE
2026-10-13** (one jar, `versions/26.1`, derived from 26.2 — PORTING-ERA-NOTES "26.1.x").
**PULLED 2026-08-06 (Will's call):** 1.21.5 and 1.12.2 — removed
from `VersionCatalog`, so they don't appear in the picker, and CI doesn't build
or bundle them. Their modules stay on disk. **Pre-1.20 is a SECOND rendering backend**, not a
gap-port: `GuiGraphics` only exists since 1.20, so those six modules draw via
`PoseStack` through a per-module `Gfx` wrapper that mirrors the GuiGraphics
method shapes (1.16.5 additionally is Java 8 + fixed-function GL). They are
`.no-shared-sync` for that reason. 1.18.1 is deliberately NOT offered (its
only Sodium is an alpha, its only Iris a pre-release). The 1.21.x line is NOT
one build — Minecraft rewrote its render/GUI/input system in stages, so each
sub-family is its own port. **No "Coming Soon" entries (Will, 2026-10-12):**
the picker shows ONLY the versions above — every unbuilt version (26.x except 26.1–26.1.2, 26.2 and 26.3,
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

## Mod stack (full detail)
- Mod: Java + Fabric (loader + API) + Loom + official Mojang mappings. 1.21.x
  on Java 21, 1.20.x/1.18–1.19.x on Java 17 bytecode, 1.17.1 on 16, 1.16.5 on
  8, 26.1.x + 26.2 + 26.3 on Java 25 (unobfuscated: no mappings line, `net.fabricmc.fabric-loom`; CI builds it on
  JDK 25 via `"java": "25"` in live-modules.json). Shaders via Iris; perf via Sodium/Lithium/FerriteCore.
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
- 1.8.9 mod: Java 8, Forge 11.15.1.2318, MCP stable_22 names via
  gg.essential.loom 1.3.12 (Gradle 8.7, foojay-provisioned JDK 8) + Mixin
  0.7.11 shadowed into the jar and loaded by its tweaker (Forge events first,
  fail-soft mixins second — OptiFine replaces whole vanilla classes, so HEAD/
  RETURN injections only there). Same package layout and public API as the
  1.16.5 module it was ported from. The sandbox can't reach Forge's maven:
  `.github/workflows/ref-189.yml` compiles it and answers 1.8.9 name/SRG
  lookups in CI logs (PORT-189.md). (1.12.2's module is the old event-only
  Forge build, pulled.) Launcher side: `LegacyForgeInstaller` (bundled version JSON +
  universal jar), `OptiFineInstaller` (download-at-install, SHA-1-pinned,
  official-site-then-mirrors, never redistributed), `LegacyStackInstaller`
  (era perf mods + FML splash theme); Forge has no fabric.modsFolder, so the
  shared `instances/1.8.9/mods/` is mirrored into each account's `mods/` at
  launch (SharedMods.Mirror). Shaders via OptiFine.
