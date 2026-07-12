# Origin Client — Forge port for the classics (1.8.9 / 1.12.2)

Decision (Will, 2026-07-10): **full Forge port of the Origin UI + features** for
1.8.9/1.12.2, running **alongside OptiFine** (OptiFine provides the shaders — the
only shader path on these versions), with a **curated set of Forge-equivalent
mods**. Fabric/Legacy-Fabric is dropped on these two versions (done: launcher now
offers Vanilla + Forge(+OptiFine) there, Forge recommended).

> ⚠️ This port is a **reimplementation guided by the current design, not a code
> transfer.** The loader, rendering API, mappings, and Java version all change at
> once. None of it can be built or tested in the current sandbox — this doc makes
> the real build (on a dev machine) mechanical.

## The four things that change at once

| | Fabric 1.21.1 (now) | Forge 1.8.9 / 1.12.2 |
|---|---|---|
| Loader | Fabric + Fabric Mixins | Forge; Mixin via **MixinBootstrap** (or coremod ASM) |
| Rendering | `GuiGraphics` / `PoseStack` | fixed-function GL: `GlStateManager`, `Tessellator`/`WorldRenderer`, `FontRenderer` |
| Mappings | Mojmap (real names) | **MCP/Searge** (every class + method renamed) |
| Java | 21 (records, switch-expr, var) | **8** (no records/switch-expr/var/text-blocks) |

## Recommended architecture: shared core + thin Forge adapter

Don't fork the whole mod. Split into:

1. **`origin-core` (loader-agnostic, plain Java, no Minecraft imports):** the
   feature state machines, the settings registry + JSON schema
   (`Mods`/`ModsConfig`/`ModOption`), layout math (`hud/HudPos`), theme token
   *values* (`theme/OriginTheme` constants), and the palette. This is ~the
   version-agnostic 95% and it compiles on Java 8 once records → classes.
2. **Per-loader render/hook adapters:** a Fabric adapter (current code) and a
   **Forge adapter** that implements the same drawing interface with era GL and
   wires the same hooks with Forge events/Mixins.

This is the Architectury-style shared core `VERSIONS.md` anticipated. Extracting
it is the **highest-leverage prep** — it also shrinks the Stonecutter modern port,
and it can begin on the Fabric side today (isolate the core, define a
`Renderer`/`Hooks` interface the current renderers implement).

## Tooling, per version (verify at a build machine)

- **1.12.2 first** (better tooling): ForgeGradle 3.x, MCP mappings
  (`snapshot`/`stable`), MixinBootstrap 0.8.x (1.12.2 Mixin support is solid),
  Java 8. Most 1.12.2 QoL clients use exactly this stack.
- **1.8.9 second** (harder): ForgeGradle 2.x, older MCP, MixinBootstrap 0.7.11
  (1.8.9 Mixin is workable but pickier); Java 8. If Mixin proves unstable here,
  fall back to a Forge **coremod ASM transformer** for the few injection points.
- Both: OptiFine is a Forge coremod on these versions and coexists with other
  Forge mods — the port must not shade anything that collides with it.

## Rendering rewrite map (the bulk of the work)

Every draw call in `OriginScreenRenderer`, `OriginButtonRenderer`, `gui/OriginUi`,
the HUD elements, and the mod-menu screens is rewritten:

| Current (`GuiGraphics`) | 1.8.9 / 1.12.2 equivalent |
|---|---|
| `fill` / rounded panel | `Gui.drawRect` / manual `Tessellator` quads + `GlStateManager` blend |
| `blit` (textured) | `Gui.drawModalRectWithCustomSizedTexture` + `bindTexture` |
| `drawString` (Font) | `FontRenderer.drawString` / `drawStringWithShadow` |
| glow / gradient | `Tessellator` with `POSITION_COLOR`, manual vertex colors |
| `enableScissor` | `GL11.glScissor` + `glEnable(GL_SCISSOR_TEST)` (scale by GUI factor) |
| `pose().pushPose()`/translate/scale | `GlStateManager.pushMatrix`/`translate`/`scale` |

Baked assets, colors, spacing, and layout math carry over unchanged — only the
primitive calls differ.

## Hook/mixin remap (confirm each target exists in the era)

| Fabric mixin target | Classic-era target (MCP) | Notes |
|---|---|---|
| `TitleScreen` | `GuiMainMenu` | button list + draw shape differ |
| `Gui` HUD (`GuiHudMixin`) | `GuiIngame` (`renderGameOverlay` event) | Forge has a HUD event — prefer it over a mixin |
| `LoadingOverlay` | no direct equiv | resource load screen differs; may drop the branded loader here |
| loading/* screens | `GuiScreenWorking` / connect screens | per-version names differ |
| `Options*` widgets | `GuiButton`/`GuiSlider` | widget restyle via draw override |
| Iris `PackShadowDirectives` | **N/A** | no Iris; shaders + their settings live in OptiFine's own menu |

The shader-performance features (shadow-halving, `IrisBridge`) **do not port** —
OptiFine owns shaders here and has its own shader options menu.

## Java 21 → Java 8 conversions (in `origin-core`)

`record` → final class + fields + accessors; switch-expressions → `switch`
statements or if/else; `var` → explicit types; text blocks → concatenation;
`List.of(...)` is fine (Java 9+ — use `Arrays.asList`/`Collections` on 8);
`String.isBlank`, `Stream.toList` → Java 8 equivalents. Audit `Mods.java`,
`ModOption`, `ModsConfig`, `HudPos` for these.

## Curated Forge mods for the classics (the "all the mods" set)

Downloads are blocked in the sandbox — this is the list to wire into the launcher
(the way `PerformanceModCatalog` wires the Fabric stack). Per version, Forge:

- **Shaders + perf:** OptiFine (already installed by the launcher — shaders +
  render perf).
- **1.12.2 extra perf:** FoamFix, VanillaFix (stability), Phosphor-forge
  (lighting) or BetterFps. All Forge 1.12.2, OptiFine-compatible.
- **1.8.9 extra perf:** BetterFps. (1.8.9's Forge perf ecosystem is thin;
  OptiFine does most of the work.)
- **QoL:** most of Origin's QoL (zoom, keystrokes, HUD, freelook, fullbright)
  comes from the **Origin Forge mod itself** once ported — so external QoL mods
  are minimal. Where a feature is out of scope early, established Forge mods
  exist (e.g. dedicated zoom/keystrokes mods common in 1.8.9 PvP).

Config/settings **carry over unchanged** — same `originclient-mods.json` schema
via `origin-core`, so a player's settings + feel are identical to the Fabric
versions. That satisfies "same auto-loaded settings, same feel."

## Milestones (build order, on a real machine)

1. **Extract `origin-core`** (loader-agnostic, Java 8-clean) + define
   `Renderer`/`Hooks` interfaces; keep Fabric building against it (no behavior
   change — a safe refactor to verify first).
2. **1.12.2 Forge adapter**: bootstrap Forge+Mixin project, port the renderer
   primitives, then title screen → HUD → mod menu → features, one surface at a
   time (fail-soft per surface, same contract as Fabric).
3. **Curated 1.12.2 Forge mod list** wired into the launcher; OptiFine shader
   flow verified end-to-end.
4. **1.8.9 Forge adapter** (repeat 2–3; expect more friction on Mixin/GL).
5. Launcher: install the Origin-Forge jar + curated mods for the classics, the
   way it installs the Fabric stack for modern versions.

## Anti-ban / legal

- Same Lunar-tier posture — all client-side QoL. Fly Boost stays creative/
  spectator-only if implemented.
- OptiFine is fetched per-user via the existing BMCLAPI mirror (not redistributed).
