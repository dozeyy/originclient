# Porting the 1.21.1 design to every version — era notes (2026-10-11)

Kept from the October 2026 port that brought every live module onto the 1.21.1
design (title screen, blurred-panorama menus, Workbench skin, Command Deck mod
menu, full mod set). Written by the porting agents as they went; each bullet
says which module or CI run proved it. Read this before porting a future 1.21.1
change to another version — the per-era spellings below are the expensive part.

Method that worked: a 3-way merge per module (base = the pre-redesign 1.21
generation, ours = the module, theirs = 1.21.1; pre-1.20 modules merge against a
GuiGraphics->Gfx transformed copy of 1.21.1), then a script of exact-count
asserted edits for the era. Before pushing, a syntax-only
`javac -proc:none -d <tmp> $(find <module>/src -name '*.java')` catches brace and
language-level mistakes (add `--release 8` for 1.16.5).

## Process (what made 1.21 and 1.20 fast)
- 3-way merge (port.py) + a scripted edit file with exact-count assertions beats hand-editing:
  when an assertion fails you immediately see which 1.21.1 snippet drifted.
- Files that diverged heavily from the old generation (OriginUi, OriginButtonRenderer,
  OriginScreenRenderer, ModIcons, OriginShaders, OriginText, OriginTheme, Mods.java) are
  faster to REGENERATE from 1.21.1 + era edits than to resolve hunk by hunk.
- Clean merges still need an import check: 1.21.1 hunks can introduce types the module's
  import list lacks (1.20: HudElements needed `import net.minecraft.core.Holder;` for the
  new biome helpers).
- After the port, grep the module for the 1.21-only API list in BRIEF.md and reason per hit.
- 1.21 transplant: identical API era -> wholesale copy of 1.21.1/src, keep own
  fabric.mod.json + FireworkStarterMixin (+ its mixins.json line). CI green first try.

## 2026-10-12 — porting the mod-menu redesign (OriginDeck) — plan from the October port
The redesign (design/menu-redesign; reference = 1.21.1, `git diff 27b5c3e..HEAD -- src/mods/versions/1.21.1/src`)
adds gui/OriginDeck (design-px kit, physical-pixel pose, click targets recorded while drawing),
rewrites OriginModMenuScreen, adds a sized MSDF API to OriginSdfFont, OriginUi.roundRect/softGlow,
the Right Shift centre group in HudEditorScreen, Mods.reset/metaText, and the dev-shots design set.
What carries over from the October port (sections below are the evidence):
- Order: 1.21 = wholesale copy; 1.20.2/1.20.4 near-verbatim; 1.20 = buffer API + 3-arg scroll;
  1.21.8 then 1.21.6 (byte-identical trees); 1.21.11 then 1.21.10 via the back-map; 1.19.4 ->
  1.19.3/1.19.2/1.18.2; 1.17.1 -> 1.16.5 (Java 8, no shaders).
- Layering: 1.21.2+ GUIs are batched — no immediate SDF panels (1.21.4 keeps baked masks; 1.21.6+
  submits GuiElementRenderStates); immediate custom-shader draws need g.flush() first on 1.21.4.
- Deferred-GUI double-blur trap (1.21.6+): never call renderTitleBackground() from render().
- The smooth font had only ever run on 1.21.1 — every other module's MSDF path must be proven
  (1.21.4's old program json used a dead id form; 1.17.1 compiles shaders on demand from
  assets/minecraft; 1.16.5 has no shaders at all -> Minecraft's own ttf font provider).
- Gate before every push: `javac -proc:none --release N` over the module (parse + language level),
  then CI build-check, then `[shots:<modules>]` dev shots compared against 1.21.1's.

### Findings from the redesign port (2026-10-12, six agents, one per era)
- The smooth (MSDF) text path had never run outside 1.21.1, and was broken in three places:
  1.20.x wrote each vertex's colour before its UV (POSITION_TEX_COLOR needs format order; the
  builder skips the attribute and endVertex throws); 1.21.10's msdf.fsh lacked the `float
  LineWidth;` DynamicTransforms member (link failure = no text); 1.21.6-1.21.10 registered the
  atlas without setFilter(true,false) (NEAREST sampling = jagged glyphs; 1.21.11 picks the
  sampler per draw instead). 1.21.6/1.21.8 also gave glyph quads z=0 instead of the state's depth.
- Pre-1.19 (1.18.2/1.17.1): a buffer that is begun and ended but never drawn shifts every later
  draw by one. OriginSdfFont.emit now returns BEFORE begin() when nothing is drawable.
- Batched GUIs: an immediate-mode draw that must sit above earlier batched draws needs
  g.flush() first (1.21.4), and a raw GL scissor must flush on enter and exit. 1.21.6+ clips go
  through GuiGraphics.enableScissor under the screen's BASE pose (saved before the deck's
  1/guiScale scale), box converted to GUI px rounded inward.
- Rounded boxes without a ROUND shader (1.21.4+): bake corner masks at physical-pixel size and
  blit 1:1; never use OriginUi.panel's 32-entry whole-box LRU for the menu (>32 sizes a frame
  evicts textures this frame's queued blits still use).
- 1.21.9+ dev shots: Screenshot.grab is the 3-arg (dated file) form there, so the harness waits
  for the PNG and renames it; windows resize via GLFW.glfwSetWindowSize(window.handle(), w, h).
- 1.16.5 has no shaders, and its text render type samples font textures NEAREST, so a ttf
  provider drawn at window-dependent sizes would be jagged. OriginTtfFont rasterises each glyph
  with LWJGL's stb_truetype at the exact on-screen pixel size (4 horizontal sub-pixel phases,
  LRU-capped atlas pages), blitted 1:1. This is a hand-rolled glyph path, which the font history
  above banned after three blind failures; the ban's stated cause was having no way to SEE the
  client, which dev shots now provide. Verify it on every change with [shots:1.16.5].
- dev-shots.yml: put the module list in the tag, `[shots:a,b]`; a listed tag wins over any bare
  `[shots]` mentioned in prose, and runs without a tag never cancel a capture in progress.

## GuiGraphics era, pre-1.21 — ALL CONFIRMED by CI: 1.20 built green first try (build-check run 131)
- ResourceLocation: `new ResourceLocation(ns, path)` / `new ResourceLocation("textures/...")`.
- Immediate-mode quads: `BufferBuilder bb = Tesselator.getInstance().getBuilder();
  bb.begin(Mode, Format); bb.vertex(m, x, y, z).uv(u, v).color(r,g,b,a).endVertex();
  BufferUploader.drawWithShader(bb.end());` — attributes MUST be called in format order
  (POSITION_TEX_COLOR = position, uv, colour). No MeshData.
- Menu blur without processBlurEffect: render the panorama into a half-size TextureTarget
  (bindWrite(true) sets the viewport; CubeMap.render draws into the bound target with an
  opaque first pass), halve 3x through GL_LINEAR targets, stretch back with one clip-space
  quad under GameRenderer::getPositionTexShader with identity ModelView + Proj
  (RenderSystem.backupProjectionMatrix / setProjectionMatrix(new Matrix4f(),
  VertexSorting.ORTHOGRAPHIC_Z) / restoreProjectionMatrix; getModelViewStack() is a
  PoseStack here). Implementation: port_120.py "OSR blur helpers".
- Title icon buttons are ImageButtons before 1.20.3; tell Language/Accessibility apart by the
  message's TranslatableContents key (narrator.button.language / .accessibility).
- AbstractWidget has no setHeight before 1.20.3 -> @Accessor("height") setter.
- Hearts/XP bar before the 1.20.2 sprite atlas: textures/gui/icons.png — heart container
  (16,0), full (52,0), half (61,0) 9x9; XP bar background (0,64) 182x5.
- Scoreboard before 1.20.3: `getDisplayObjective(Scoreboard.DISPLAY_SLOT_LIST)` (int slot,
  1.20.1) and `getOrCreatePlayerScore(name, objective).getScore()`.
- Player heads 1.20.1: `mc.getSkinManager().getInsecureSkinLocation(new GameProfile(
  user.getProfileId(), name))` + `PlayerFaceRenderer.draw(g, ResourceLocation, x, y, size)`;
  tab list uses `PlayerInfo.getSkinLocation()`.
- EntityRenderer.renderNameTag has 5 params (no float partialTick) before 1.21 — the
  NametagStyleMixin captured-params list must drop it.
- Gui hooks: render(GuiGraphics,float); XP bar `renderExperienceBar(GuiGraphics,int)`,
  hotbar `renderHotbar(float,GuiGraphics)` — locator bar hooks go there (see 1.20
  GuiHudMixin), not renderHotbarAndDecorations (1.21+).
- "Preparing..." screen is GenericDirtMessageScreen until 1.20.5 (mixin at render HEAD,
  non-cancelling, draws renderLoadingScene(g, null); ScreenBackgroundMixin already cancels
  the dirt while out of a world).
- mouseScrolled is 3-arg (mx,my,amount) on 1.20/1.20.1, 4-arg from 1.20.2.
- Item Size screen: no DataComponents/MaceItem before 1.20.5/1.21 -> `item instanceof
  DiggerItem` for tools, `item.isEdible()` for food, drop the MaceItem clause.

### 1.20.2 / 1.20.4 (agent: 1.20.2+1.20.4 port, scripts port_1202.py / port_1204.py)
- CORRECTION to BRIEF.md: SpriteIconButton EXISTS on 1.20.2 (not 1.20.3+). Evidence: the
  boot-swept 1.20.2 module's own TitleScreenMixin imports it, and MEMORY.md's 1.20.4 javap
  table lists "SpriteIconButton on the title screen" under "arrived 1.20.2/1.20.3". So the
  1.21.1-flavour GUI set is ALL present from 1.20.2: SpriteIconButton (CenteredIcon /
  TextAndIcon override renderWidget; `sprite`/`spriteWidth`/`spriteHeight` fields), the GUI
  sprite atlas (blitSprite, hud/heart/{container,full,half}, hud/experience_bar_background,
  icon/language, icon/accessibility), PlayerSkin + PlayerInfo.getSkin() +
  SkinManager.getInsecureSkin(GameProfile)->PlayerSkin, DisplaySlot enum, 4-arg
  mouseScrolled, 4-arg Screen.renderBackground, LevelRenderer.countRenderedSections.
  1.21.1's SpriteIconButtonMixin/Accessor, OriginButtonRenderer, WaypointScreen,
  OriginModMenuScreen and the tab-list heart/skin code apply VERBATIM on 1.20.2 and 1.20.4
  (no ImageButtonMixin there).
- Scoreboard split inside the 1.20.x line: ScoreHolder / ReadOnlyScoreInfo /
  Scoreboard.getPlayerScoreInfo are 1.20.3+ (1.20.4 keeps 1.21.1's OriginTabList code);
  1.20.2 reads `scoreboard.getOrCreatePlayerScore(name, objective).getScore()` (the call
  its own vanilla PlayerTabOverlay makes). DisplaySlot.LIST is fine on both.
- TitleScreen on 1.20.2-1.20.4 (and 1.20.1): NO renderPanorama method and an empty
  renderBackground override -- render() calls `PanoramaRenderer.render(FF)V` and then
  `GuiGraphics.blit(ResourceLocation;IIIIFFIIII)V` for PANORAMA_OVERLAY inline (both
  modules' previous mixins, javap-confirmed against 1.20.4; MEMORY.md "1.20.4-ONLY shapes").
  The port keeps those two @Redirects in place of 1.21.1's renderPanorama/renderBackground
  cancel-injects, which bind NOTHING on any 1.20.x (MEMORY.md 2026-08 audit line ~4215).
  WARNING for the 1.20 port: its TitleScreenMixin carries the dead injects; the panorama
  stays hidden there only by accident (Origin's GUI fills write depth at GUI z, CubeMap's
  perspective faces fail the LEQUAL test), and the PANORAMA_OVERLAY vignette still blits
  over the Origin backdrop. 1.20.1's render() has the same two calls -> port_120.py
  should adopt the same two @Redirects.
- Account chip on 1.20.2+: `new GameProfile(uuid, name)` has NO textures property and
  the rewritten SkinManager no longer fills profiles itself, so getInsecureSkin returns
  the default head forever (the bug 1.21.1 fixed, MEMORY.md 2026-09). The port passes
  `mc.getGameProfile()` (Minecraft, 1.20.2+ authlib-5 ProfileResult era -- REASONED, not
  module-evidenced; a one-line revert to the module's own profile build if CI rejects it)
  to `getInsecureSkin(profile)` -> PlayerSkin (module-evidenced).
- AbstractSelectionList: 1.20.2 still draws in render(); 1.20.3+ moved it to renderWidget()
  (the two modules' own AbstractSelectionListMixin differ on exactly that line). Keep each
  module's own; it is ours-only in the merge.
- Both modules bundle JEI (1.20.2: 16.0.0.2, 1.20.4: 17.3.1.5 -- gradle.properties). Keep
  their own JeiSettings + JeiGuiEventHandlerMixin + JeiClientInputHandlerMixin (identical
  to 1.21.1's base so the merge keeps them); the JEI self-check method names in 1.21.1's
  OriginClientMod match both JEI versions (the modules' previous self-checks listed the
  same six names). Only BUNDLED_JEI changes.
- IrisListDecorationsMixin (1.20.2/1.20.4 only) targets Iris's renderDecorations override;
  MEMORY.md notes the target does not resolve on current Iris (dead, require=0, harmless).
  Kept as instructed; candidate for deletion in a later cleanup.
- Gui.render(GuiGraphics,float) / renderExperienceBar(GuiGraphics,int) /
  renderHotbar(float,GuiGraphics) are unchanged 1.20 -> 1.20.4 (MEMORY.md "Mod120-flavor
  (changes only at 1.20.5/1.21)"): the 1.20 GuiHudMixin is reused byte-for-byte.
- Process: port_1202.py takes `--reset`, which restores the module's src/ + overrides.txt
  from `git archive HEAD` (plain file ops, no git write commands) and re-runs port.py
  before the edits, so a port is reproducible from a clean tree in one command; it ends by
  asserting no conflict markers and mixins.json == the mixin files on disk.

## 1.21.x RenderPipeline era
- (agents: add here)

### 1.21.4 (covers 1.21.2-1.21.4, blit-rework era; agent: 1.21.4 port, script port_1214.py --reset)
Module-evidenced (from the module's own boot-verified HEAD code) unless marked REASONED:
- Blits: `g.blit(RenderType::guiTextured, id, x, y, u, v, dstW, dstH, srcW, srcH, texW, texH[, argb])` and
  the 9-arg `(RenderType::guiTextured, id, x, y, u, v, w, h, texW, texH[, argb])`; `blitSprite(RenderType::guiTextured,
  sprite, x, y, w, h)`. Tints/alpha MUST ride the trailing ARGB (`ARGB.white(alpha)` or a literal int):
  the GUI is BATCHED here, so `setShaderColor` around a blit is never seen (the "TV static" grain bug).
- Same API as 1.21.1 for: Gui.render(GuiGraphics, DeltaTracker), SpriteIconButton.CenteredIcon.renderWidget
  + ResourceLocation `sprite`, setHeight, 4-arg mouseScrolled, PlayerTabOverlay/authlib getId()/getName(),
  WorldRenderContext.matrixStack()/camera()/consumers(), Tesselator.begin/MeshData/BufferUploader,
  setShaderColor (still exists, just useless for batched blits). 1.21.1's TitleScreenMixin applies VERBATIM
  (the module's HEAD copy differed from the old generation only in comments).
- Renames vs 1.21.1: getTimer()->getDeltaTracker(); processBlurEffect() takes no float AND needs no
  bindWrite rebind; Level.getMinBuildHeight()/getMaxBuildHeight() -> getMinY()/getMaxY(); Inventory.armor gone
  (getItemBySlot); shouldShowName(LivingEntity, double); EntityRenderer.renderNameTag(EntityRenderState,
  Component, PoseStack, MultiBufferSource, int) (no Entity -> NametagStyleMixin matches "self" by
  PlayerRenderState + display-name text); NativeImage.setPixel takes ARGB; SkinManager.getInsecureSkin(profile).
- OriginUi: KEEP the module's physical-resolution baked-mask renderer (panels + close/edit/chevron glyphs)
  instead of 1.21.1's immediate-mode ROUND shader -- an immediate quad in a batched GUI lands UNDER
  everything drawn earlier in the frame. Port only 1.21.1's behaviour onto it (craftedRadius Workbench
  radii, switch/slider geometry + tokens, full-ARGB icon tint, no-op glow). No ROUND program shipped.
- REASONED (pending CI/boot): any immediate-mode custom-shader quad (ModIcons' SDF icon) must call
  `g.flush()` first so the batched content beneath it draws first.
- REASONED (pending boot): 1.21.2+ core-shader program json = `{"vertex": "ns:core/<name>", "fragment":
  "ns:core/<name>", "samplers": [...], "uniforms": [...]}` -- full source ids (vanilla: "minecraft:core/..."),
  no "blend"/"attributes" (set blend in Java). The module's old MSDF json said "originclient:rendertype_origin_msdf"
  (would not resolve); it was dead code (OriginText is vanilla font) so it never surfaced. Key = new
  ShaderProgram(ns:"core/<name>", format, ShaderDefines.EMPTY) (module-evidenced), compiled via
  Minecraft.getShaderManager().getProgram(key) (module-evidenced).
- REASONED: do NOT cache a CompiledShaderProgram across frames -- a resource reload closes every program the
  ShaderManager handed out. Re-ask getProgram(key) on use (it caches per reload; a failure is remembered as
  null without recompiling). OriginShaders.icon()/grade() do this.
- REASONED (pending CI): `new TextureTarget(w, h, useDepth)` -- 1.21.2 dropped the clearError flag
  (1.21.5+ adds a String name first, 1.21.6/8 modules show that form).
- Item Size: extractRenderState(ItemEntity, ItemEntityRenderState, F) TAIL -> stash scale on the state via a
  duck (client/ext/ItemScaleState + mixin/ItemEntityStateMixin, the 1.21.11 pattern) -> push/scale at
  render(ItemEntityRenderState, PoseStack, MultiBufferSource, I) HEAD, pop at RETURN. Full descriptors so
  neither hook can hit the generic EntityRenderer bridge.
- No Fabric JEI: drop the "jei" card (Mods), JEI fields/notice/self-checks (OriginClientMod), the dispatch
  (OriginModMenuScreen -> plain Mods.*), JeiSettings + both Jei mixins. JeiSettings.prettify is used by
  OriginMultiSelect + the MULTISELECT row summary: move it into OriginMultiSelect (pure string code).
  1.21.8 (also JEI-less) will need the same.
- overrides.txt for a ToastManager-era module must list shared's mixin/ToastComponentMixin.java (else
  sync.py would COPY it in) plus its GameRendererAccessor/GameRendererMixin forks, on top of 1.21.1's list.
- Color Saturation: 1.21.1's ColorGrade (blit scene -> swap TextureTarget -> back) ports with only
  RenderSystem.setShader(program) (no Supplier) + the TextureTarget ctor + the on-demand program.
- (1.21.11, port_12111.py) 1.21.1's mod_icons_sdf.png drops straight onto this era's ICON
  RenderPipeline: rendertype_origin_icon.fsh on both modules samples `.r` with
  DISTANCE_RANGE 16 (only the std140 DynamicTransforms block differs). ModIcons' SDF quad is
  a GuiElementRenderState submitted to g.guiRenderState with
  TextureSetup.singleTexture(view, getSamplerCache().getClampToEdge(LINEAR)) -- the exact
  shape of the module's own OriginUi.IconSdfRenderState. icon_sdf.png keeps serving the
  close/chevron/edit glyphs; both atlases share the one ICON pipeline.
- (1.21.11) DON'T trust "kept ours" for files 1.21.1 never touched in September: the pristine
  1.21.11 HudElements had a 1.21.11-ONLY coords direction column (cardinal8/facingSign,
  DIR_COL_W) that no other module has; the port takes 1.21.1's "Facing: X (deg)" row so the
  HUD matches 1.21.1. Diff pristine-vs-ported, not only base-vs-1.21.1.
- (1.21.11) Account chip: the module still built `new GameProfile(uuid, name)` (default-head
  bug 1.21.1 fixed in Sept). Port passes `mc.getGameProfile()` into the module's own
  boot-verified `getSkinManager().createLookup(profile, false).get()` -- getGameProfile() is
  REASONED for this era (authlib 7 record profile), not module-evidenced.
- (1.21.11) Title-dock "pressed" state: MouseHandler.isLeftPressed() is unverified after the
  1.21.9 input rework; `GLFW.glfwGetMouseButton(mc.getWindow().handle(), LEFT)` is safe
  (Window.handle() is evidenced in the module's GeneralSettings).
- (process) NEVER pipe a port script that resets src/ through `| head`: SIGPIPE kills it
  between the 3-way merge and the edits and leaves the module full of conflict markers.
  Redirect to a log file and tail it.
- (1.21.11 + 1.21.10, deferred-GUI era 1.21.6+) DOUBLE-BLUR TRAP: 1.21.1's OriginModMenuScreen
  calls OriginScreenRenderer.renderTitleBackground() itself when level == null. On this era
  vanilla runs renderBackground BEFORE render for every screen, and ScreenBackgroundMixin has
  already drawn the Origin backdrop (with blurBeforeThisStratum) there -- a second blur in one
  frame throws "Can only blur once per frame" (pristine 1.21.11 OMMS comment, proven on 26.2),
  OSR's catch calls fail(), and broken=true switches Origin backdrops off for the session.
  Both ports drop that call. TitleScreen is fine: its renderBackground is an empty override,
  so TitleScreenMixin's render-HEAD backdrop is the only blur there. Check 1.21.6/1.21.8 too.
- (1.21.10, port_12110.py) 1.21.10 = 1.21.11 minus the rename wave; derive design files from the
  FINISHED 1.21.11 through backmap.py instead of a fresh 3-way merge. Evidenced back-map
  (pristine pairs): Identifier->ResourceLocation; rendertype.RenderType(s)->RenderType;
  ResourceKey.identifier()->location(); TextureSetup.singleTexture(view) one-arg (no
  getSamplerCache); GuiElementRenderState `Matrix3x2f pose` (not Matrix3x2fc);
  DynamicTexture.setFilter(true,false) still exists; no per-vertex .setLineWidth on lines
  (LineWidth is the 5th DynamicTransforms UBO member -> any new .fsh needs it, as the module's
  grade.fsh has); renderWidget (not renderContents) on SpriteIconButton/Checkbox/AbstractButton
  subclasses. Same as 1.21.11: input events, WidgetSprites, Matrix3x2fStack, getPanorama +
  blurBeforeThisStratum, createLookup(profile,false), getMobEffectSprite, EntityEquipment,
  SPEED/STRENGTH, getMinY, authlib-7 name()/id(), drawString returns void.
- (1.21.10) PRE-EXISTING BUG found + fixed by the port: PlayerTabOverlayMixin cancelled vanilla's
  player list but no tabListVisible/OriginTabList draw existed, so Tab showed nothing.
- (1.21.10) Still silently dead (not touched, flagged): EntityNametagMixin targets
  renderNameTag(Entity,...) and BlockEntityRenderDispatcherMixin targets render(BlockEntity,...)
  -- both gone in the extract/submit era; 1.21.11 fixed its copies on 07-26 (submitNameTag /
  submit with AvatarRenderState / BlockEntityRenderState). Port after a javap of the 1.21.10 jar.
- (1.21.8 + 1.21.6, port_1218.py / port_1216.py) The two modules' src/ trees were BYTE-IDENTICAL
  at HEAD (diff -r; only build.gradle archivesName, gradle.properties MC/fabric-api pins and one
  overrides.txt comment differ), so one edit set serves both; port_1216.py runs port_1218.py with
  the 1.21.6 pristine extracted read-only by `git archive HEAD`. Post-port src/ is identical again.
- (1.21.8) Era spellings, module-evidenced: ResourceLocation; WorldRenderEvents/WorldRenderContext
  in plain rendering.v1 (ctx.camera()/matrixStack()/consumers()); Matrix3x2fStack pose; blit/
  blitSprite take RenderPipelines.GUI_TEXTURED first + trailing ARGB; drawString returns void;
  splash render(...F)V; SharedConstants...name(); GuiElementRenderState.buildVertices(vc, float)
  + addVertexWith2DPose(Matrix3x2f, x, y, z) + TextureSetup.singleTexture(view) (OriginSdfFont);
  DynamicTexture(Supplier, img) + setFilter(bool,bool); SpriteIconButton renderWidget + plain
  ResourceLocation `sprite`; getFov returns float; getMinY; getMobEffectSprite; SPEED/STRENGTH;
  getItemBySlot armour; renderNameTag(EntityRenderState,...); pre-1.21.9 input callbacks, so
  1.21.1's screen bodies need only the pose edits. 1.21.1 input signatures stay.
- (1.21.8) ICON pipeline: RenderPipelines.register(builder(GUI_TEXTURED_SNIPPET) + fragment
  core/rendertype_origin_icon, POSITION_TEX_COLOR QUADS); the .fsh is 1.21.1's body in #version
  330 with the 5-member DynamicTransforms block (LineWidth). LINEAR sampling = setFilter(true,
  false) on the DynamicTexture (no sampler arg in TextureSetup before 1.21.11).
- (1.21.8) PRE-EXISTING BUG (same as 1.21.10's): HEAD 1.21.6/1.21.8 cancelled vanilla's player
  list in PlayerTabOverlayMixin but nothing drew OriginTabList (no tabListVisible). Fixed by the
  merge of 1.21.1's OriginClientMod + GuiHudMixin.
- (1.21.8) Background in the deferred era: vanilla runs renderBackground as its own stratum before
  render(). 1.21.1's OriginModMenuScreen / OriginItemSizeScreen never called super/renderBackground,
  so in-world the live world showed UNBLURRED. To match, both override renderBackground: call super
  only when level == null (ScreenBackgroundMixin then draws the Origin backdrop: the one blur),
  skip in-world. Also covers the double-blur trap above.
- (1.21.8) WaypointHud without RenderSystem matrices: viewProj = new Matrix4f().perspective(
  toRadians(getFov via GameRendererAccessor invoker), windowW/H, 0.05, 1024) .rotate(conjugate(
  ctx.camera().rotation())), which is what vanilla's renderLevel builds. Camera.getYRot() still
  exists here for the locator bar. REASONED (Camera.rotation()).
- (1.21.8) Font.drawInBatch(Component...) return type: V from the 1.21.6 PreparedText rework
  (1.21.11 evidence), I before. REASONED, so NametagStyleMixin redirects BOTH descriptors.
  Exactly one binds; the int handler returns 0, which renderNameTag discards. A free hedge for
  any redirect whose only uncertainty is the return type.
- (1.21.8) REASONED: GuiGraphics.renderTooltip(Font, Component, x, y) -> setTooltipForNextFrame
  from 1.21.6 (Item Size hover tooltip). EquipmentSlot.Type HUMANOID_ARMOR/ANIMAL_ARMOR used for
  the armour category instead of 1.21.11's EquipmentSlot.isArmor() (unverified on 1.21.8).
- (process) Asserting "no RenderSystem in t" after regenerating a file trips on your OWN
  per-version comments that mention the removed API. Assert on call shapes
  ('RenderSystem.setShaderColor(', 'systems.RenderSystem') instead.

## Pre-1.20 Gfx era
- (agents: add here)
- (1.19.4/3/2 agent) gfx_era.py FIX, affects every Gfx module: the blur pyramid's
  drawFullscreenTexture no longer calls RenderSystem.setProjectionMatrix(new Matrix4f()).
  Two traps: setProjectionMatrix's arity moves inside this era (VertexSorting), and the
  pre-JOML `new com.mojang.math.Matrix4f()` is ALL ZEROS (not identity) on 1.19.2/1.18.2 —
  the quad would vanish. It now draws a GUI-space quad (0,0)-(window.getWidth()/getGuiScale(),
  ...) under the GUI's own ortho projection, which maps onto whatever viewport
  bindWrite(true) set; render-target textures are bottom-up so v=1 at the top edge.
- (1.19.4/3/2 agent) gfx_era.py FIX: hud_elements(get_fps=False) now calls the STATIC
  `MinecraftFpsAccessor.originclient$fps()` — both snap_1.19.2 and snap_1.18.2 declare the
  accessor as a static @Accessor("fps") on the private static field (the earlier
  instance-cast `((MinecraftFpsAccessor) mc).originclient$getFps()` named nothing).
- (1.19.4/3/2 agent) client_mod() takes `hover_expr` (default 'w.isHovered()'): the
  beforeRender backdrop now blooms the spotlight on hover like 1.21.1. isHovered() is
  1.19.4+; pass 'w.isMouseOver(mx, my)' on 1.19.3 and older.
- (1.19.4/3/2 agent) Minecraft.getFps() EXISTS on 1.19.3 (snap_1.19.3 HudElements calls
  it, CI/boot-green) — only <=1.19.2 needs MinecraftFpsAccessor.
- (1.19.4/3/2 agent) CORRECTION to the brief: PlayerFaceRenderer EXISTS on 1.19.2 (1.19.1+,
  javap per 1.19.2 PORT-NOTES; snap_1.19.2's account chip calls
  PlayerFaceRenderer.draw(pose, x, y, size) after setShaderTexture). Only <=1.18.2 needs the
  manual 8x8 face + hat blit.
- (1.19.4/3/2 agent) gfx_calls_check.py <module>: javac without the MC classpath never
  attributes method bodies, so a 1.21.1 GuiGraphics call Gfx lacks is invisible to
  syntax_check(). This script lists every `g.method(...)` on a Gfx-typed variable whose
  name/arity Gfx does not declare (JsonObject-named-g hits in OriginSdfFont are false
  positives).

- (1.19.4/3/2 agent) gfx_era.py FIX: GFX_EXTRA's Gfx.blit(tex,x,y,u,v,w,h) now calls the
  static 9-arg float-UV GuiComponent.blit(pose,x,y,(float)u,(float)v,w,h,256,256) — the
  7-arg GuiComponent.blit is an instance method (blitOffset) before 1.19.4, the 9-arg
  static exists on every pre-1.20 version (1.18.2 agent's gfx_blit7_static already
  no-ops on this form).
- (1.19.4/3/2 agent) gfx_119.py: title_screen_mixin(mod, era, logo_block, note) builds
  1.21.1's TitleScreenMixin for any pre-1.20 era (panorama/overlay/splash/version
  redirects + the module's own wordmark block via snap_block(); era.hover_widget,
  era.widget_xy='fields' for public x/y, era.button_builder=False for `new Button`);
  button_renderer_119 (hover per era, x/y fields), foreign_widgets_fields,
  screen_invoker_erased, tab_suppress_guarded. port_1194/1193/1192 use them.
- (1.19.4/3/2 agent) ScreenInvoker: Screen.addRenderableWidget's bound is
  `Widget` <=1.19.2 and `Renderable` from 1.19.3. @Invoker binds on the ERASED
  descriptor (first bound GuiEventListener), so declaring
  `<T extends GuiEventListener & NarratableEntry>` binds on every 1.19.x without naming
  either class (screen_invoker_erased). 1.19.4 keeps 1.21.1's Renderable form.
- (1.19.4/3/2 agent) A CLEAN merge can still import a newer API: 1.21.1's
  BlockOverlayRenderer gained a "mod off = hairline outline" hunk drawing into
  RenderType.debugQuads() (1.19.4+). 1.19.3/1.19.2 (and older) must swap in the module's
  render/FillQuads.INSTANCE. Found by import_check.py (unresolved simple names; the
  OnPress/ClassValue/NoSuchMethodException/"User-Agent" hits are false positives).
- (1.19.4/3/2 agent) 1.19.2: OriginShaderButton is a real conflict (1.21.1's eased hover
  vs the module's this.x/this.y) — take 1.21.1's side, then getX()/getY() -> this.x/this.y.
- (1.19.4/3/2 agent) PlayerTabOverlayMixin now cancels vanilla's list ONLY when
  `(Object) this instanceof PlayerTabOverlayAccessor` (tab_suppress_guarded): if the
  accessor's getPlayerInfos @Invoker does not resolve on a version, the accessor mixin
  fails to apply and vanilla's tab list stays instead of the tab key showing nothing.
  1.19.3 relies on getPlayerInfos (reasoned: arrived with 1.19.3's listed-players change);
  1.19.2 uses the 1.18.2 agent's tab_list_no_getplayerinfos (vanilla order rebuilt).
- (1.19.4/3/2 agent) Era hover idioms (each module's own code): 1.19.4 isHovered();
  1.19.3 OriginButtonRenderer.hovered(widget) (isMouseOver at the live GUI cursor);
  1.19.2 isHoveredOrFocused() in the widget skin, isMouseOver(mx, my) for the spotlight.
- (1.18.2 agent, port_1182.py + my helpers in gfx_old.py) gfx_era.origin_ui's pre-JOML branch asserts an
  `import com.mojang.math.Axis;` that 1.21.1's OriginUi does not have (it spells
  `com.mojang.math.Axis.ZP` inline, twice) -> ASSERT on any matrix4f != JOML era. Workaround
  that passes: run origin_ui with a default (JOML) Era, then swap the Matrix4f import and the
  two inline `com.mojang.math.Axis.ZP.rotationDegrees(` -> `com.mojang.math.Vector3f.ZP.rotationDegrees(`
  (gfx_old.origin_ui_prejoml). 1.19.2 will hit the same.
- (1.18.2) BufferBuilder.end() is VOID before 1.19 and the draw is `BufferUploader.end(bb)` (the
  module's own Gfx.fillGradient / OriginSdfFont). Every gfx_era `BufferUploader.drawWithShader(x.end());`
  needs `x.end(); BufferUploader.end(x);` there (gfx_old.buffer_upload_118, whole-module regex).
- (1.18.2) Widget API before 1.19.3, from the module's own code: x/y are PUBLIC FIELDS (no
  getX/getY/setX/setY), no Button.builder (ctor), Screen.addRenderableWidget's bound is
  `GuiEventListener & Widget & NarratableEntry` (Renderable is the 1.19.3 rename) -> ScreenInvoker
  needs `Widget`. No public isHovered() (module uses isHoveredOrFocused(); mouse-only tests use
  isMouseOver(mx,my)). Title icon keys: no TranslatableContents before 1.19 — the label itself is
  a TranslatableComponent (`((TranslatableComponent) c).getKey()`).
- (1.18.2, reasoned) PlayerTabOverlay.getPlayerInfos() does not exist before 1.19.3 (vanilla sorts
  inline in render() with PLAYER_ORDERING). The accessor's @Invoker would fail to apply and the
  GuiHudMixin tab draw would throw -> no tab list at all. port_1182 drops the invoker and builds the
  same order itself (gfx_old.TAB_ORDER_HELPER -> OriginTabList.vanillaPlayerList(mc)). 1.19.2 needs it too.
- (1.18.2) No PlayerFaceRenderer / SkinManager.getInsecureSkinLocation (module's own chip code):
  chip head + tab heads blit face (8,8) + hat (40,8) from the 64x64 skin; chip skin resolved via
  SkinManager.registerSkins (gfx_old.CHIP_SKIN_MANUAL / CHIP_SKIN_HELPER / TAB_FACE_MANUAL).
- (1.18.2) No OptionInstance below 1.19: Options.guiScale / fov / sensitivity / chatScale /
  textBackgroundOpacity / chatOpacity are plain fields (OriginDevShots, WaypointHud FOV, OCM).
- (1.18.2) TitleScreen keeps the module's 6 boot-verified redirects (PanoramaRenderer.render(FF),
  10-arg overlay blit, blitOutlineBlack x2 -> wordmark, 8-arg edition blit, splash
  drawCenteredString, version drawString) — gfx_old.title_screen_118 builds 1.21.1's layout on them.
- (1.17.1, port_1171.py) Derived from the PORTED 1.18.2 tree, not from 1.21.1: every 1.18.2 file the
  port left untouched -> 1.17.1's own pristine file; every file the port changed -> the ported text +
  the asserted 1.17.1 deltas (the script refuses to finish if a changed file carries an unhandled
  1.17.1-vs-1.18.2 delta). Re-run port_1182.py first whenever 1.18.2 changes.
- (1.17.1) No CoreShaderRegistrationCallback in Fabric API 0.46.1+1.17 (module's own OriginShaders).
  1.17's ShaderInstance builds `"shaders/core/" + name + ".json"` with NO namespace handling, so the
  module's old `"originclient:rendertype_origin_msdf"` name lands INSIDE the namespace
  ("shaders/core/originclient") -> ResourceLocationException -> MSDF never loaded there (silent
  vanilla-font fallback; reasoned from the vanilla source shape, not boot-checked). The port compiles
  all four core shaders on demand from assets/MINECRAFT/shaders/core with un-namespaced names and
  un-namespaced vertex/fragment refs; ensureCompiled() is skipped while the loading overlay is up.
- (1.17.1) Gson 2.8.0: besides keySet()/JsonArray.isEmpty(), JsonElement.deepCopy() is not public
  (2.8.2) -> 1.21.1's ModsConfig.deepCopy re-parses via `new JsonParser().parse(e.toString())`.
- (1.17.1) No JEI: gfx_old.strip_jei_{mods,omms,ocm,multiselect}. OriginMultiSelect gets its own
  prettify (copied from 1.21.1's JeiSettings, as port_1218 did). NOTE for anyone reusing port_1218's
  OMMS dispatch splice: `t.index('\t}\n', vSetMulti)` matches the INNER `\t\t}` of the if/else first
  and leaves a stray `}` (javac: "class, interface, enum, or record expected"); search '\n\t}\n'.
- (1.18.2/1.17.1/1.16.5 agent) MISSED BY THE BRIEF'S SWEEP: 1.21.1's BlockOverlayRenderer has a new
  "mod off = vanilla hairline" branch drawing into RenderType.debugQuads() (1.19.4+, and RenderType was not
  even imported after the merge). <=1.19.3 modules must use their FillQuads.INSTANCE. Add `debugQuads` to
  every pre-1.19.4 sweep (the 1.19.2/1.19.3 ports should check BlockOverlayRenderer line ~57).
- (all three) `javac --release N -proc:none` with no classpath is a real Java-level verifier: it reports
  records/switch rules/patterns/text blocks below N, `var` as "cannot find symbol: class var", and JDK
  API misses (List.of, isBlank, readAllBytes, Files.readString, Stream.toList) as cannot-find-symbol whose
  location is a JDK type. gfx_old.release_check filters exactly those; the pristine snap_1.16.5 scores 0
  at --release 8 (no false positives). It cannot see MC-API drift — that stays a reasoned review.
- (1.16.5, port_1165.py) Derived from the ported 1.17.1 tree with a 3-way merge for files where 1.16.5 had
  its own delta (ours=snap_1.16.5, base=snap_1.17.1, theirs=ported 1.17.1). CONFLICTED files must be taken
  WHOLE from theirs, not take_theirs() on the merged text: hunk-mixing produced a half arrow-switch /
  half classic switch hybrid ("orphaned case"). Then java8.py (records -> value classes, statement
  switch rules -> classic, List.of/toList/isBlank/readAllBytes/Path.of) + explicit edits for var,
  instanceof patterns and switch EXPRESSIONS (java8.py skips those on purpose).
- (1.16.5) java8.py record conversion: name the equals() parameter `obj` and qualify fields with `this.`
  — a record component named `o` (OMMS.SRow) otherwise silently compares the parameter to itself.
- (1.16.5) Fixed-function alpha test: vanilla 1.16.5 GuiComponent.blit force-enables the alpha test at
  GREATER 0.1, which drops every faint pixel (software AA corners, glows, hover washes, panels mid-fade).
  The module's Gfx now runs fill/blit with RenderSystem.alphaFunc(GL_GREATER, 0) and restores
  RenderSystem.defaultAlphaFunc() (reasoned from the 1.16 innerBlit shape; not boot-checked).
- (1.16.5) Blur pyramid is expressible in fixed function: RenderTarget(w,h,useDepth,clearError) is the
  concrete off-screen target (no TextureTarget), source.bindRead()/unbindRead(), GL_QUADS POSITION_TEX via
  Tesselator.end(), quad in GUI space under the legacy GUI ortho matrices. No core shaders at all:
  OriginShaders/OriginSdfFont/ColorGrade not ported, Color Saturation card removed, ModIcons = mask atlas.
- (1.16.5) Pre-1.17 shapes used: Screen.addButton (ScreenInvoker @Invoker("addButton")), AbstractWidget.
  isHovered() already includes focus, Entity fields yRot/inventory/abilities, no getMinBuildHeight (0),
  WaypointHud projection from WorldRenderContext.matrixStack()/projectionMatrix() (RenderSystem has no
  model-view/projection getters), Screenshot.grab(File,String,w,h,RenderTarget,Consumer).

## CI failures and their fixes (orchestrator appends after each build-check)
- build-check 130 (1.21 transplant) and 131 (1.20 port) both green on the first push — the
  method in BRIEF.md works; no fixes needed so far.
- (orchestrator) 1.20 fixed per the 1.20.2 agent's warning: 1.20.1's TitleScreen.render() also
  calls PanoramaRenderer.render(FF)V + blit(RL;IIIIFFIIII)V inline (no renderPanorama method,
  empty renderBackground), so 1.20 now carries the same two @Redirects instead of cancel-injects.
- build-check 132 (1.20.2 + 1.20.4 ports + 1.20 title redirects) green on the first push.
  Confirms: Minecraft.getGameProfile() + SkinManager.getInsecureSkin(profile) compile on 1.20.2+;
  SpriteIconButton exists on 1.20.2; PanoramaRenderer.render(FF)V / blit(RL;IIIIFFIIII)V redirects
  compile on 1.20.1 too.
- build-check 135: 1.21.4, 1.21.10, 1.21.11, 1.19.4, 1.19.3, 1.19.2 all GREEN first push.
  1.21.6 + 1.21.8 failed on ONE syntax error: a stray `}` after vSetMulti in OriginModMenuScreen
  (class closed early -> "unnamed classes are a preview feature"). Cause: slicing a method body
  with t.index('\t}\n') also matches the inner '\t\t}\n' of a nested block. Search '\n\t}\n'.
  Cheap local guard that would have caught it: `javac -proc:none -d /tmp/x $(find <module>/src
  -name '*.java')` and look for parse-level errors only (ignore cannot-find-symbol etc.).
