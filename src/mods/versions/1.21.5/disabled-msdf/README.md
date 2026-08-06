# 1.21.5 — MSDF vector text, parked (not deleted)

**Status: unfinished port. Parked here 2026-08-06 so the module compiles.**

These files came in with the 2026-08-02 sweep that propagated MSDF vector text
across the version modules. On 1.21.5 they were copied from 1.21.1 verbatim and
**never compiled** — 1.21.5 deleted the entire API they submit through:

| What the port calls | State on 1.21.5 |
|---|---|
| `RenderSystem.setShader(Supplier)` | gone |
| `RenderSystem.enableBlend` / `defaultBlendFunc` | gone (blend belongs to the pipeline now) |
| `BufferUploader` | gone |
| `ShaderInstance` | gone |
| `CoreShaderRegistrationCallback` (Fabric) | gone |
| `new DynamicTexture(NativeImage)` | gone (needs the name-supplier ctor) |

They are parked at the module root — **outside `src/`, so nothing compiles
them** — rather than deleted, because they are untracked working-tree files:
git cannot restore them, so deleting would be irreversible.

## Why 1.21.5 is genuinely its own port

Neither neighbour's approach transfers:

- **1.21.4 and earlier** use `ShaderManager` / `ShaderInstance` + immediate-mode
  `BufferUploader.drawWithShader`. All removed by 1.21.5.
- **1.21.6 and later** declare a `RenderPipeline` and register it in vanilla's
  `RenderPipelines` registry. 1.21.5 **has** `com.mojang.blaze3d.pipeline.RenderPipeline`
  but **not** the `net.minecraft.client.renderer.RenderPipelines` registry — that
  arrives in 1.21.6.

So finishing this means building a `RenderPipeline` by hand and finding 1.21.5's
own way to submit GUI quads with it. That is real porting work, not a retarget.

## What 1.21.5 ships in the meantime

Menu **text** uses the vanilla font — exactly what the released 1.21.5 build does
today, so this is a held position, not a regression.

Everything that does **not** need a shader is anti-aliased on 1.21.5 like every
other version: panels, cards, chips, switches, tooltips, menu buttons and the
vector icon glyphs all draw through `OriginUi`'s physical-resolution baked masks.

## To finish it

1. Build the MSDF `RenderPipeline` directly (vertex format `POSITION_TEX_COLOR`,
   the fragment shader in `assets/shaders/`).
2. Find this version's GUI-quad submission path for a custom pipeline.
3. Move these files back under `src/client/java/.../gui/` and the assets back to
   `src/client/resources/assets/originclient/shaders/core/`.
4. Restore `OriginShaders.register()` in `OriginClientMod` (line ~59) and flip
   `OriginShaders.enabled()` back to `true`.
5. Re-point `OriginText` at `OriginSdfFont.active()` (see the sibling 1.21.6
   module for the shape).
