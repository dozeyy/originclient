# Recipe: anything on 1.8.9 (Forge + OptiFine)

Always read `src/mods/versions/1.8.9/PORT-189.md` first. It is the brief: toolchain, hook order, the full Mojmap →
MCP type map, keys, area ownership, ref-189 and the foundation API. This file holds only the porting method and the
traps proven since then.

## Toolchain (don't change build.gradle without asking)
- Forge 1.8.9-11.15.1.2318, MCP `stable_22`, gg.essential.loom 1.3.12, Gradle 8.7, Java 8 language level.
  Runtime is Mojang's jre-legacy, possibly 8u51.
- Run tasks need an explicit Java 8 `javaLauncher`. Dev resources need `resourcesDir = classesDirectory`.
  OptiFine comes from a SHA-1-pinned mirror chain.
- **Mixin 0.7.11** is shadowed in, with six configs (one per area), all `required:false` + `defaultRequire:0`.
  - There is no MixinExtras (no @WrapOperation, @Local or @ModifyExpressionValue). Never @Overwrite.
  - The old memory note "no mixins on legacy" is stale.
- LWJGL 2.9.4 (no GLFW), Gson 2.2.4, Guava 17.

## Hook order
1. **A Forge event** when one fires at the right moment. Register in the area's `hooks/<Area>Hooks`. The foundation registers both buses.
2. **A Mixin.** OptiFine REPLACES many vanilla classes (EntityRenderer, RenderGlobal, ItemRenderer, FontRenderer, GuiVideoSettings, ...).
   - In those classes use only HEAD/RETURN/TAIL with cancel. INVOKE/FIELD/ordinal points may silently miss under OptiFine.
   - Dev shots run WITHOUT OptiFine, so they won't show such a miss.
3. **@Accessor / @Invoker** in the area's mixin package. The refmap maps MCP to SRG. Guard with `instanceof`.
4. **Reflection** last, through `util.Reflect` with BOTH names (MCP + SRG). Get SRG names from ref-189. Never guess.
   Forge-added members need `remap = false`.

## Method (proven: 1.8.9 ports 2026-10-13 → 10-19)
- Port from **1.16.5** (closest Java 8 shape) using PORT-189's type map, not from 1.21.1 directly.
  The look still has to match 1.21.1's shots.
- Look names up with the **ref-189** workflow (lookup jobs: `class` / `member` / `find`, the srg topic, compile-errors-only).
  Put the misses under "LOOKUPS NEEDED" / "SRG NEEDED".
- Gate: `javac --release 8 -proc:none` on the changed files, with no parse errors and no Java 9+ API.
- Check the built jar's **srg refmap** for every new mixin target. Dev runs are MCP-named; players run SRG.

## Proven traps
- `drawPanorama` leaves depth and cull ON. Restore them, or later GUI draws break.
- Cancelling `GuiSlot.overlayBackground` needs a scissor around `drawSelectionBox` and `drawListHeader`.
- A dev `GuiConnecting` created via Unsafe needs the GuiScreen fields copied over.
- Never show `GuiModList` (Forge's mod list). Origin's menu replaces it.
- Every hook body is fail-soft: catch Throwable, volatile latch.
- Missing on 1.8.9, so drop the card: cooldowns, offhand, elytra, shields/totems, sweep, toasts (use GuiAchievement),
  boss list (one BossStatus), bed block entity, subtitles toggle, attack indicator, narrator.

## Proof
- `dev-shots` for module `1.8.9` (no OptiFine), plus ref-189 compile.
- OptiFine-only behaviour and shaders: UNVERIFIED until Will plays. Say so.
