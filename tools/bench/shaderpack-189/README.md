# origin-bench shader pack (bench only)

A minimal OptiFine shader pack written for the 1.8.9 bench (Phase 6, B-04). It is not a player pack and
never ships: `prod189.py run --shaderpack tools/bench/shaderpack-189/origin-bench` copies it into the
fresh bench instance and selects it in `optionsshaders.txt`.

What it makes OptiFine do, so Origin's cost can be measured with shaders on (origin-default minus the
same round's optifine-only control, both with this pack):
- the shader pipeline itself (gbuffers render targets, a final pass, OptiFine's shader entity and
  block paths) instead of the fixed-function renderer;
- a shadow pass (`shadow.vsh/fsh`, 1024 px map, distance 64): terrain and entities are drawn twice per
  frame, as in a real pack, so per-entity hooks (nametags, cosmetic layers, cape physics) run twice.

The look is plain vanilla-like colour with a faint shadow term; quality is not the point.
Written for this repo (no third-party shader code). Only `gbuffers_textured` is written: OptiFine's
fallback chain sends terrain, entities, hand and the rest to it.
