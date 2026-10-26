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

## PORTING: Origin steps aside (StepAside, 2026-10-26; wired on 1.21.1 only)
A player's own mod that does the same job as an Origin mod switches Origin's off, and the card says
"Replaced by <mod>". The player's switch press sets or clears `forcedOn` in that mod's block of
originclient-mods.json. An absent key means not forced, so no configVersion bump is needed, and
clearing the force restores the block byte for byte. Settings Sync carries it with the file.
- **Shared, already in every synced module (sync.py):**
  - `mods/StepAside.java` holds THE table and the pure logic. It is tested by
    `tools/step-aside-tests/run.py` in build-check.
  - `mods/StepAsideDetect.java` uses Fabric Loader and runs once.
  - Both compile everywhere but do nothing until a module wires them.
  - The `.no-shared-sync` modules (pre-1.20 and 1.8.9) need a hand copy:
    - StepAside uses records, so 1.16.5 (Java 8) needs them rewritten as classes.
    - 1.8.9 has no Fabric Loader: detect with Forge `Loader.isModLoaded`, and use Forge mod ids.
- **Per module (copy 1.21.1's hunks):**
  1. `Mods.java`:
     - `on()` returns `StepAside.effectiveOn(id, enabled, forcedOn)` behind the `StepAside.any()` fast path.
     - `setOn()` writes `StepAside.toggle(...)` for a replaced id.
     - Add `forcedOn(id)`.
     - Modules still on the shared Mods.java get this by editing shared/Mods.java. Do that only in the
       change that also ports every un-forked module's card note.
  2. `OriginClientMod.onInitializeClient`: call `StepAsideDetect.run()` FIRST.
  3. Mod menu, plain text in the existing tokens (no chip, no new colour):
     - `StepAside.cardNote` under the card name; the icon and name lift 9 px.
     - On the list row, the note goes after the name (MUTE).
     - `StepAside.pageNote` at the top of the mod page.
  4. WorldEdit CUI init: `if (StepAside.aside(ID, Mods.forcedOn(ID))) return;`. This replaces `isModLoaded`.
  5. JEI-bundling modules:
     - Drop the "remove that jar" chat nag.
     - The JEI row is `asideOn`: stepping aside means Origin's switch stops hiding the player's JEI.
     - Only a JEI NOT nested in originclient counts, and never in dev.
  6. Dev-shots:
     - Add the STEP_CARD, STEP_ROW and STEP_PAGE kinds and `set=stepaside`.
     - Add `tools/frame-log/mods-<module>-stepaside.json`: Fabric API plus Freecam, using that version's
       Modrinth version ids. dev-shots.yml loads it via prodClient.
- No new mixins: every hook is Origin code. The only era-specific part is the menu's draw API.
- Old versions: zoomify and appleskin ids may not exist there, which is harmless.
- Proof:
  - `dev-shots modules=<m> set=stepaside`: the card, row and page all show "Replaced by Freecam".
  - boot-smoke: 0 mixin-apply failures.
