# Recipe: mod-menu cards, options, config, ModIcons, OriginDeck

## Rules that hold everywhere
- **Only that version's options.** A card row for a feature the version lacks is dropped, not shown dead. Examples:
  - 26.2 has no bed block entities, so the Colored Beds row is gone. 26.1 has it.
  - 1.8.9 has no cooldowns, offhand or toasts.
- When a module needs a different option set, fork `Mods.java` and `ModOption.java` by listing them in that module's `overrides.txt`. The JEI modules do this.
- **Dead-option audit** after every port: does each control have a reader?
  - Paren-match each `add(` block.
  - Skip header rows.
  - Scan the helpers that read the option.
  - Its three traps are in the memory note "dead-option-audit".
  - Era facts for it:
    - chat trim options split at 1.21;
    - chat shadow is a lambda on 1.21.6+;
    - 1.21.11 has no chat-shadow flag;
    - 26.2 has no ChatComponent.render;
    - `Particle.scale` exists in every era.
- **Config migration**: never rename a field without a `configVersion` bump. GSON zero-fills silently (category rule).
- Gson differs by version:
  - 1.17.1 has Gson 2.8.0: no public `deepCopy()`, so re-parse instead.
  - 1.8.9 has Gson 2.2.4: no `keySet()`, `size()`, `JsonArray.isEmpty()/remove()` or `parseString`. Use `new JsonParser().parse(s)`.

## ModIcons and smooth panels per era
- Real Minecraft items are the mod icons. Item stacks can't be built before a world loads on 26.x.
- **Baked masks**: rasterise anti-aliased corners and glows at PHYSICAL resolution into a texture, then blit them.
  - Per-pixel AA in GUI pixels looks jagged once guiScale magnifies it. That is why every version except 1.21.1 / 1.21.11 had jagged corners.
  - On deferred GUI (1.21.6+) per-pixel panels cost about 130 draws each (mod list 195 → 724 FPS after baking).
- Blit argument order flips: classic `x,y,w,h,u,v` versus tinted `x,y,u,v,w,h`.
  The per-era blit / tint / NativeImage / DynamicTexture matrix is in the memory note "smooth-ui-baked-masks".
- MSDF text donors: 1.21.8 ← 1.21.6, and 1.21.10 ← 1.21.11 (not 1.21.6).
- 1.17.1 core shaders: 1.17 ShaderInstance has no namespace handling. Compile with un-namespaced names from `assets/minecraft/shaders/core`.
  Without this the MSDF silently falls back to the vanilla font.
- 1.16.5: no core shaders at all. ModIcons is a mask atlas; no OriginShaders, OriginSdfFont or ColorGrade.

## OriginDeck / mod-menu redesign method (proven 2026-10-12)
- Regenerate the heavily diverged files (OriginUi, OriginButtonRenderer, OriginScreenRenderer, ModIcons, OriginShaders,
  OriginText, OriginTheme, Mods.java) from 1.21.1 plus scripted era edits with exact-count assertions. Don't hand-resolve hunks.
- When slicing a method body in a script, search `'\n\t}\n'`, not `'\t}\n'` (stray `}` trap, build-check 135).
- 1.16.5 conversion from records (java8.py): name the equals() parameter `obj` and qualify fields with `this.`.
  Otherwise a component named `o` compares the parameter to itself.

## Proof
- `dev-shots set=design` shows the mod-menu cards. Video Settings shots need `set=perf`.
- Run the dead-option audit output in the report.
