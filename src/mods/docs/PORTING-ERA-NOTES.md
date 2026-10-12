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

### Findings from the feature port (2026-10-12: cosmetics + Portal Calculator, FPS Coach, feedback round)
- Capes by era: before 1.20.2 there is no PlayerSkin, so hook AbstractClientPlayer's four
  getters (isCapeLoaded, getCloakTextureLocation, isElytraLoaded, getElytraTextureLocation);
  1.20.2+ swap the cape inside getSkin's PlayerSkin; 1.21.2+ the elytra fallback texture is
  textures/entity/equipment/wings/elytra.png; 1.21.11 wraps textures in ClientAsset.ResourceTexture.
- Halos/crown head layer: 1.21.2+ draws from PlayerRenderState (look the wearer up by state id);
  1.21.10+ submits custom geometry from an AvatarRenderState layer. 1.16.5's Fabric API has no
  feature-renderer registration callback -> add the layer from the PlayerRenderer constructor.
- Cosmetics preview (spinning player): renderEntityInInventory has a different shape in nearly
  every era. Before 1.20 it can't take a yaw -> a mixin at its head sets the body/head rotation;
  1.21.6+ uses the boxed (x0,y0,x1,y1) form; 1.21.10+ draws picture-in-picture.
- Item Physics: from 1.21.2 one ItemEntityRenderState object is reused for every dropped item,
  so per-item easing must be keyed off the state's entity id, never stored on the state alone.
- FPS Coach: ParticleStatus moved package in the 1.21.2 line -> don't name the type; match the
  option's value by name() and pick a sibling constant. 1.21.11 has no Fabulous graphics mode;
  the tip turns off Improved Transparency instead. 1.18.2 writes the plain Options fields and
  then runs vanilla's own reactions (no OptionInstance yet).
- Screenshot chat buttons: 1.21.5+ ClickEvent/HoverEvent are records (ClickEvent.OpenFile etc.).
- Networking: 1.16.5 is Java 8 -> HttpURLConnection, no java.net.http.
- Color Saturation on 1.16.5: no core shaders, so it's its own vanilla PostChain.

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

## Color Saturation black world / white vignette on 1.17.1-1.20.4 (satbug, 2026-10-12)
- Symptom: Color Saturation on, no shader pack -> world black (daylight) or a white ring round the
  screen edges (dim light); HUD fine. 1.21.x fine; an Iris pack hid it.
- Root cause: before 1.20.5 every ShaderInstance applies its json "blend" through BlendMode.apply(),
  which caches the last mode in a static and only touches GL when the mode CHANGES. Our grade shader
  is opaque, so it left the cache on "opaque". Gui.render then draws the vignette with
  blendFuncSeparate(ZERO, ONE_MINUS_SRC_COLOR) and blits via position_tex, whose json blend is
  srcalpha/1-srcalpha: apply() saw opaque -> translucent and re-set blendFunc(SRC_ALPHA,
  ONE_MINUS_SRC_ALPHA), wiping the vignette's multiply. The opaque vignette texture (black centre,
  light edges, tinted by vignetteBrightness) got pasted over the world. 1.20.5+ has no BlendMode.
- Fix (render/ColorGrade, 1.17.1-1.20.4): after the grade passes, apply a
  BlendMode(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ADD) (GUI_BLEND) so the cache holds the mode vanilla's GUI
  shaders declare, then reset GL blend as before. 1.16.5 is unaffected (fixed-function HUD; its
  grade is a PostChain whose EffectInstances re-apply their own blend).
- Rule for any custom core shader drawn mid-frame on 1.17-1.20.4: leave BlendMode's cache in the mode
  vanilla's next draw expects, not just the raw GL blend state.

## Fix-round port (2026-10-12) — 1.21.4 (covers 1.21.2-1.21.4; agent port-b)
- 1.21.4's copies of every fix-round file except OriginDeck/CosmeticsScreen/Shader browser/Minimap
  were BYTE-IDENTICAL to 1.21.1's pre-round copies (d0b50d6), so a 3-way merge (base = d0b50d6
  1.21.1, ours = 1.21.4, theirs = 1.21.1 HEAD) took them clean. Dev shots design + world matched
  1.21.1-final on the first CI run (run 38283734918 / 38283738772), zero mixin failures.
- Blits in the new windows (Cosmetics cape tiles, shader previews): `g.blit(RenderType::guiTextured,
  id, x, y, u, v, dstW, dstH, srcW, srcH, texW, texH, ARGB.white(alpha))` — the fade rides the
  trailing ARGB, never setShaderColor. The deck's raw-scissor clip already flushes on enter/exit, so
  queued blits inside the shader list stay clipped.
- See-through halos: OriginDeck.halo snaps each pill to whole physical px before OriginUi.roundRect,
  so the baked-mask renderer bakes one cap mask per pill HEIGHT (shared by every label that size)
  instead of a whole-shape mask per label per ring, which would churn the 160-entry mask LRU.
- MenuPlayerPreview (title-screen character): this era has no model-only path worth copying
  (no renderCloak; cape is PlayerCapeModel) and no EntityRenderDispatcher.getSkinMap (gone by 1.21.2,
  checked against yarn 1.21.4 mappings). Instead: OriginClientMod's
  LivingEntityFeatureRendererRegistrationCallback hands its EntityRendererProvider.Context to
  MenuPlayerPreview.onRenderersCreated (refreshed every reload); the preview builds its own
  `new PlayerRenderer(ctx, slim)` (anonymous subclass whose initializer calls addLayer(new
  CosmeticHeadLayer(this)), since Fabric's callback only runs for the game's renderers), fills a
  PlayerRenderState by hand (skin with the Origin cape swapped in, bodyRot = 180 + yaw, show* parts
  from options, id = STATE_ID) and draws it with `g.flush(); g.drawSpecial(buf -> renderer.render(st,
  pose, buf, LIGHT))` under Lighting.setupForEntityInInventory. GuiGraphics has no bufferSource()
  getter here. CosmeticHeadLayer reads the head item from MenuPlayerPreview.headId() when
  state.id == STATE_ID (no level needed). Proven by dev shots: cape + halo on the title preview.
- MinimapScreen: 1.21.1's restyle with the map as GuiMesh geometry (MinimapRenderer takes g; GuiMesh
  flushes the batch itself), so the deck's well lands under it and the chrome over it.
- CosmeticsPauseMixin: 1.21.2-1.21.4 createPauseMenu is still the GridLayout + alignInRectangle(0.5,
  0.25) shape, so 1.21.1's TAIL logic applies verbatim (pause shot: Cosmetics row identical to its
  neighbours, stack lifted like vanilla).

## Fix-round port (2026-10-12) — 1.20, 1.20.2, 1.20.4, 1.21 (GuiGraphics, pre-1.21.2; agent port-a)
- Same method as port-b: 3-way merge (base = d0b50d6 1.21.1, ours = module, theirs = 1.21.1 HEAD).
  1.21 was byte-identical to the base for every file (wholesale copy; only CosmeticsPauseMixin's
  comment differed). 1.20.2/1.20.4 conflicted only where they already carried era deltas; the
  merged trees for the two are identical. Dev shots design + world (label port-a, runs 38283557661 /
  38283561618) matched 1.21.1-final on the first run on all four, zero mixin failures.
- 1.20 (1.20.1) era edits on the 1.21.1 windows: 3-arg `mouseScrolled(mx, my, amount)` (OriginWindow
  and every window that overrides it), `renderBackground(GuiGraphics)` one-arg (OriginWindow keeps it
  as an empty override; Screen.render never calls it before 1.20.2, so MinimapScreen.render calls
  its own), `mc.getFrameTime()` for the map's partial tick (1.20.2/1.20.4 too). The 1.20 windows'
  old explicit `renderBackground(g)` calls are gone: OriginWindow.render paints the backdrop itself.
- In-world Cosmetics preview: renderEntityInInventory takes an INT scale before 1.20.5 (round it);
  1.20.2-1.20.4 have float x/y + the Vector3f centring offset; 1.20.1 has int x/y and no offset
  ((x, y) = feet) -> push, translate to the exact feet point (y + bbHeight/2 * scale), draw at 0,0.
- MenuPlayerPreview: `renderToBuffer(..., 1f, 1f, 1f, 1f)` (the int-colour form is 1.21+).
  1.20.2/1.20.4: `getSkinManager().getInsecureSkin(mc.getGameProfile())` -> PlayerSkin each frame
  (SkinManager caches; module-evidenced in the title chip) instead of 1.21.1's lookupInsecure.
  1.20.1 has no PlayerSkin: a private Skin(texture, slim, cape) record, NickHider's three getters
  (skinFor/modelFor/capeFor), and the session profile's signed textures fetched ONCE off-thread with
  `getMinecraftSessionService().fillProfileProperties(profile, false)` on Util.backgroundExecutor()
  (a bare UUID+name profile always yields the default skin there), then getInsecureSkinInformation
  + registerTexture for skin/cape and the "model" metadata for slim. Compiles + renders (dev shot);
  the real-skin path itself is unproven offline. NOTE: the 1.20 title-screen head chip still builds
  a bare profile, so it shows the default head for real accounts — candidate for the same fix.
- Pause menu: 1.20.1-1.21 createPauseMenu is the GridLayout + alignInRectangle(0.5, 0.25) shape;
  1.21.1's TAIL logic applies verbatim (pause shots: Cosmetics row identical to its neighbours).
- Title: the merge put `setTitlePresentation(cosmeticsNav, "@shirt", false)` into each module's own
  Cosmetics block (1.20/1.20.2/1.20.4 keep their AbstractWidgetAccessor height setter + redirects).

## Fix-round port (2026-10-12) — 1.21.6 / 1.21.8 (deferred GuiRenderState era; agent port-c)
Dev shots `1.21.{6,8}-port-c` + `1.21.{6,8}-world-port-c` (commit 05d23fb) compiled and ran clean on the
first push (no BUILD FAILED, no Mixin apply failures); every design + world PNG matches 1.21.1-final.
- Method: 3-way `git merge-file` per file (base = 1.21.1 @ d0b50d6, ours = module, theirs = 1.21.1 HEAD);
  files 1.21.1 rewrote (Cosmetics, FPS Coach, item grid, shader browser, map) take theirs whole + era
  edits. The two src/ trees were byte-identical, so 1.21.8 was ported and copied to 1.21.6.
- OriginWindow: never draws the title backdrop from render() (double-blur trap); renderBackground calls
  super only out of a world (ScreenBackgroundMixin = the one blur), and in a world render() asks
  MenuBlur.renderBehind. Subclasses drop their own renderBackground overrides.
- No z in the deferred GUI: OriginItemGridScreen.inFront just runs the draws (later + overlapping =
  higher layer); drawItem is translate/scale(k,k) on the Matrix3x2fStack.
- Pipeline blits with the fade in the trailing ARGB (cape art, shader previews):
  `blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, uW, vH, texW, texH, argb)`, u/v before size.
- Cosmetics preview = picture-in-picture. The PiP box is GUI px (the deck's 1/guiScale pose is ignored),
  so the card's inside is converted and rounded inward; the box centre is the entity anchor, so 1.21.1's
  placement rides the translation: offset = (shiftX/scale, bbH/2 + shiftY/scale, 0) with shift = wanted
  centre - box centre in GUI px. In a world: the boxed renderEntityInInventory (pin the *O rotations too).
- MenuPlayerPreview (no world): fills a `new PlayerRenderState()` itself (entityType PLAYER, sentinel id,
  ageInTicks, skin with the Origin cape swapped in, show* from Skin Customization, capeFlap = sway,
  bodyRot = 180 + yaw) and calls `g.submitEntityRenderState(state, size, offset, rotation, camera,
  x0, y0, x1, y1)`; vanilla's PlayerRenderer draws body + CapeLayer. CosmeticHeadLayer recognises the
  sentinel id (MenuPlayerPreview.STATE_ID) and wears MenuPlayerPreview.head() — no entity to look up.
  All those PlayerRenderState fields compile on 1.21.6 and 1.21.8.
- OriginDevWorld: Screenshot.grab is the 3-arg dated-file form here; it reuses OriginDevShots.renameShot
  (now package-private) and waits 2.5 s after the last capture so the async PNG lands before stop().
- Pause menu: 1.21.6/1.21.8 createPauseMenu is still GridLayout + alignInRectangle(0.5, 0.25); 1.21.1's
  TAIL logic applies verbatim (pause shot: Cosmetics row identical width/height/gap, stack lifted).
- Harness note: the first shaders_1920x1080 capture on 1.21.8 caught the window mid-fade (first open
  stalls on the preview fetch); the 1366 shot and 1.21.6's are settled with previews. Timing, not code.

## Fix-round port (2026-10-12) — 1.18.2, 1.19.2, 1.19.3, 1.19.4 (pre-1.20 Gfx era; agent port-e)
- Method: the four modules' copies of every touched file were near-identical (diffs = TextComponent on
  1.18.2, Registry/com.mojang.math on <=1.19.2, the inventory-entity steps on <=1.19.3), so 1.19.4 was
  generated from 1.21.1 HEAD (GuiGraphics -> Gfx, PoseStack render, 3-arg mouseScrolled, asserted edits)
  and the other three DERIVED from that with only those deltas. Light-touch files (OriginDeck, OMMS,
  HudEditorScreen, TitleScreenMixin, OriginDevShots) took 1.21.1's change by 3-way merge onto each
  module's own copy (only comment conflicts). Design + world dev shots matched 1.21.1-final on all four
  first run (label port-e); runClient clean, no Mixin apply failures.
- OriginWindow: render(PoseStack) wraps Gfx; the no-op override is renderBackground(PoseStack). The
  backdrop (renderTitleBackground / MenuBlur.renderBehind) is drawn by render() itself, as on 1.21.1.
- MenuPlayerPreview without PlayerSkin: skin + "slim" metadata + the account's own cape come from
  SkinManager.registerSkins(profile, (type, location, texture) -> ..., true) (default skin until the
  callback lands, same as the account chip); renderToBuffer takes four colour floats; draw with
  mc.renderBuffers().bufferSource() + endBatch() (no GuiGraphics.flush). Profile =
  mc.getUser().getGameProfile(). Pre-JOML (<=1.19.2): Vector3f.ZP.rotation(PI).mul(XP.rotation(-0.12))
  and Vector3f.YP/XP.rotationDegrees instead of Quaternionf / Axis (1.19.3 already has both).
- In-world Cosmetics preview: 1.19.4 replays renderEntityInInventory on the GUI PoseStack, so it follows
  the deck pose like 1.21.1; <=1.19.3 positions through RenderSystem's model-view stack and IGNORES the
  GUI PoseStack -- pass GUI-px coordinates (1.21.1's gx/gy) and the feet point (centre + scale*height/2).
- Item icons in the grid: <=1.19.3 Gfx.renderItem folds the pose into the model-view, so the deck's
  physical-px pose + 16px snapping works unchanged. Z_OVER is 900 here, not 1000: the pre-1.20 GUI depth
  range ends at z 1000 (ortho near 1000 under a -2000 model-view), so 1000 sits on the clip plane.
- Pause menu: 1.19.3's createPauseMenu adds ONE GridWidget holding the buttons (1.19.4's GridLayout hands
  them to the screen via visitWidgets), so the old children()-scan found no buttons and 1.19.3 had NO
  Cosmetics button. CosmeticsPauseMixin now walks ContainerEventHandler children recursively.
  <=1.19.2 has no grid: rows sit at fixed offsets from height/4 (top-anchored), so the row is appended
  without the 1.19.4+ quarter-row lift; Button constructor, public x/y fields.
- MinimapScreen: the dark fill is the first draw in render(PoseStack) (no renderBackground stratum),
  getFrameTime() for partial ticks, no flush; Matrix4f is com.mojang.math on <=1.19.2.
- OriginDevWorld: 1.21.1's file applies verbatim on all four (PauseScreen(true), Screenshot.grab(File,
  String, RenderTarget, Consumer) and I18n button pressing exist back to 1.18.2).

## Fix-round port (2026-10-12) — 1.21.10 / 1.21.11 (1.21.9+ input + submit/extract era; agent port-d)
- Method: 1.21.1 HEAD copies into 1.21.11 + scripted, count-asserted era edits, then 1.21.10 derived from
  the finished 1.21.11 by the back-map (Identifier -> ResourceLocation, net.minecraft.util.Util ->
  net.minecraft.Util, model.player.PlayerModel -> model.PlayerModel, InventoryScreen's 10-arg
  renderEntityInInventory). Design + world dev shots (runs 38284298361 / 38284302120) compiled and matched
  1.21.1-final on the first run; zero mixin failures.
- OriginWindow: deferred GUI -> the backdrop lives in renderBackground (level == null: super, i.e. the
  ScreenBackgroundMixin Origin backdrop; in a world: MenuBlur.renderBehind + renderDeferredSubtitles), the
  OMMS pattern; render() draws none (double-blur trap). Input overrides take MouseButtonEvent / KeyEvent /
  CharacterEvent; every subclass unpacks x/y/button at the top and passes the same event to super.
- OriginDeck see-through: 1.21.1's hunks apply as-is; halo pills are OriginUi.roundRect render states
  submitted before the text, so they layer under it. The deck-corner mask cache is keyed by radius in 1/8 px
  (unbounded but small: halo radii are text-height sized).
- Item grids: no pose z on Matrix3x2fStack. inFront() just runs the draws: a box submitted after items it
  overlaps lands in a higher GuiRenderState layer, which is all 1.21.1's Z_OVER lift did. Items keep the
  16*k physical-px snap via pose.scale(k, k) + renderFakeItem (item atlas, nearest = crisp).
- Blits (cape tiles, shader previews): g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, uW, vH,
  texW, texH, argb) with the fade in the trailing ARGB; the deck clip is on GuiGraphics' scissor stack,
  which the blit state captures.
- Cosmetics preview = picture-in-picture: submitEntityRenderState(state, scale, offset, rotation, camera,
  x0, y0, x1, y1) ignores the pose, so the box is the card's inside in whole GUI px and the offset moves the
  model's middle off the box centre: offset = ((cx - boxCx)/scale, bbH/2 + (cy - boxCy)/scale, 0).
- MenuPlayerPreview (no world): a hand-filled `new AvatarRenderState()` (entityType PLAYER, id = STATE_ID,
  skin from SkinManager.createLookup(profile, false) with the Origin cape swapped in as a
  ClientAsset.ResourceTexture like CosmeticCapeMixin, show* parts from options, bodyRot = 180 + yaw,
  capeFlap for the sway) submitted the same way. EntityRenderDispatcher.getRenderer(state) picks the
  player renderer from state.skin.model(), so vanilla's layers (cape) and Fabric-registered ones run.
  CosmeticHeadLayer reads the head item from MenuPlayerPreview.headFor(state) when state.id == STATE_ID
  (no level entity). Proven by dev shots: skin + cape + halo on the title-screen preview, both modules.
- MinimapScreen: MinimapRenderer takes the GuiGraphics here (no flush / depth toggles); the two deck passes
  layer around the map by submission order.
- OriginDevWorld: GLFW.glfwSetWindowSize(window.handle()) + resizeDisplay(); buttons pressed with
  b.onPress(new KeyEvent(GLFW_KEY_ENTER, 0, 0)); every capture waits for the dated PNG and renames it
  before the next phase (an extra phase keeps mc.stop() until the last rename lands).
- CosmeticsPauseMixin: 1.21.10/1.21.11 createPauseMenu is still the GridLayout + alignInRectangle(0.5, 0.25)
  shape, so 1.21.1's TAIL logic applies verbatim (pause shot: Cosmetics row identical, stack lifted).
- Removed a stray tracked javac.*.args from the 1.21.11 module root.

## Fix-round port (2026-10-12) — 1.17.1 / 1.16.5 (pre-1.20 Gfx era, 1.16.5 fixed function; agent port-f)
- Method: every new 1.21.1 window taken WHOLE from 1.21.1 HEAD and run through one mechanical pass
  (GuiGraphics -> Gfx, Component.literal -> new TextComponent, render(PoseStack) + `Gfx g = new
  Gfx(poseStack)`, 3-arg mouseScrolled) plus a few asserted era edits per file; 1.16.5 is then
  derived from the PORTED 1.17.1 file (Java 8: records -> value classes, switch rules/expressions ->
  classic switch, `var` -> explicit types, List.of/Set.of -> Collections/Arrays). The old modules'
  window files were the pre-redesign generation, so merging hunks would have been slower than this.
  Dev shots design + world matched 1.21.1-final on the first CI run for both modules (1.17.1 runs
  38284056654 / 38284060812; 1.16.5 world 38284374367, design 38284757200 after one Loom
  downloadAssets hiccup); zero mixin failures.
- OriginWindow: no renderBackground override (Screen.render never paints one before 1.20.2;
  render() draws the title backdrop / MenuBlur itself like the mod menu). OriginDeck's see-through
  code is 1.21.1's verbatim apart from each module's own text path (1.17.1 OriginSdfFont, 1.16.5
  OriginTtfFont) and OriginUi.roundRect arity (1.16.5's software path takes no aaPad).
- Cosmetics preview, both previews in GUI px through the era's GLOBAL matrix stack (the deck's
  PoseStack never reaches them): in-world = the module's own renderEntityInInventory(x, feetY,
  intScale, 0, -14.31, player) + CosmeticsPreviewMixin yaw (feet = centre + bbHeight*scale/2).
  MenuPlayerPreview replays renderEntityInInventory + LivingEntityRenderer by hand: 1.17.1
  RenderSystem.getModelViewStack()/applyModelViewMatrix + Lighting.setupForEntityInInventory +
  `new PlayerModel<>(getEntityModels().bakeLayer(ModelLayers.PLAYER[_SLIM]), slim)` +
  Options.isModelPartEnabled; 1.16.5 RenderSystem.pushMatrix/translatef/scalef, NO Lighting call
  (the GUI already runs under setupFor3DItems, as the vanilla inventory preview does),
  `new PlayerModel<>(0f, slim)`, Options.getModelParts().contains(part). Both: renderToBuffer
  8-arg float-colour form, pre-JOML Vector3f.XP/YP/ZP quaternions, and the skin/arm model/cape from
  SkinManager.registerSkins(profile, callback, true) (the title chip's call; for YOUR profile it
  fills the textures from Minecraft.getProfileProperties()), default skin until it arrives.
  Proven by dev shots: default Alex/Steve with Orbit cape + halo on the title preview.
- Cape tiles / shader thumbnails: Gfx's 11-arg blit; tint = RenderSystem.setShaderColor on 1.17.1,
  RenderSystem.color4f on 1.16.5 (fixed function multiplies by the GL colour).
- Item grid: renderFakeItem goes through Gfx (model-view stack on 1.17.1, legacy GL stack on
  1.16.5), so the pose's (k, k, k/gs) scale applies unchanged. Z_OVER is 600, not 1000: the pre-1.20
  GUI projection keeps z only in (-1000, 1000) (ortho 1000..3000 around a -2000 shift), so 1000
  sits on the clip plane. 1.16.5's inFront also switches the depth test off around the lifted draws
  (belt and braces; shots look the same with it). No DataComponents/MaceItem/BuiltInRegistries:
  Registry.ITEM, `instanceof DiggerItem`, `item.isEdible()`.
- Minimap: backdrop filled at the top of render(), mc.getFrameTime(), no g.flush(); 1.16.5 has no
  Entity.getBlockX/Z -> Mth.floor(getX()). Waypoints: 1.16.5 ground search floor is y=0.
- Pause menu: not a GridLayout before 1.19.4 (fixed rows from height/4), but 1.21.1's TAIL logic is
  generic over the buttons it finds, so it ports with public x/y fields (`b.y -= lift`), the plain
  Button constructor, and addButton on 1.16.5. Pause shots: Cosmetics row identical to its
  neighbours, stack lifted a quarter row.
- OriginDevWorld: 1.17.1 = 1.21.1's verbatim (setWindowed, 4-arg Screenshot.grab, "selectWorld.create"
  on both world screens). 1.16.5: GLFW.glfwSetWindowSize + resizeDisplay, the 6-arg grab with the
  framebuffer size, Java 8 loop. Both create a world and capture all five shots.
- 1.17.1 Color Saturation, first in-world check: world_sat/world_bright grade correctly (mean HSV
  saturation 151 -> 197, value 93 -> 103; 1.21.1: 138 -> 177, 117 -> 129), no black world, no white
  vignette, so render/ColorGrade.java is unchanged. 1.16.5's PostChain grade also behaves (174 -> 233).
- Dev-shot timing: a 1.16.5 window caught mid open-slide once (panel 3 px low) under the software
  rasteriser; the rerun was exact. Compare panel edges before calling a layout off by a few px.

## Social port (2026-10-12) — 1.21.4 (covers 1.21.2-1.21.4; agent sp-b)
- Every 1.21.1 file the social change edited merged clean 3-way (base 5b5baeb^ 1.21.1, ours 1.21.4,
  theirs 1.21.1 HEAD) except OriginModMenuScreen (NEW_IDS comment only) and NametagStyleMixin. Dev shots
  (design + world, label sp-b, run 38302062938 / 38302066093) match 1.21.1-social / 1.21.1-world-social
  first try, zero mixin failures. JOIN shows "(1.21.1)" here: the demo friends play 1.21.1 (correct).
- Nametag tint: renderNameTag gets an EntityRenderState, so the tint looks the player up by
  PlayerRenderState.id through mc.level.getEntity (the LevelHeadMixin pattern) before nametagTint(Entity).
- SocialToast on the 1.21.2+ Toast API (FpsCoachToast's shape): update(ToastManager, t) decides
  getWantedVisibility() and drops the toast from the click list; render(g, font, t) only draws and records
  its pose translation. Minecraft.getToastManager(), not getToasts().
- Friend faces: batched GUI, so the window's fade rides PlayerFaceRenderer.draw(..., size, argb)
  (SocialSkins.face overload) instead of setShaderColor. Skins: the background job fetches only the
  profile; SkinManager.getInsecureSkin(profile) (module-evidenced) serves default-then-real, so
  getOrLoad's return type in this line never matters.
- Minimap party dots use the module's GuiMesh.colors() (it flushes the batch itself), not 1.21.1's
  immediate BufferBuilder helpers. Everything else (ConnectScreen.startConnecting 6 args,
  level.disconnect + mc.disconnect(Screen)/(), StringUtil.isAllowedChatCharacter, client command v2,
  WorldRenderEvents.LAST ctx.matrixStack()/camera(), ThickLine + OriginRenderTypes) is 1.21.1's code as is.

## Social port (2026-10-12) — 1.20, 1.20.2, 1.20.4, 1.21 (GuiGraphics, pre-1.21.2; agent sp-a)
- Method: the 1.21.1 social diff (5b5baeb + d037954 + b15a952, 1.21.1 paths) applied with `git apply`
  path-rewritten per module; every hunk applied except NametagStyleMixin on 1.20.x (captured params end
  at `int packedLight` — no partialTick before 1.21), where the tint block is inserted by hand. 1.21 is
  1.21.1's code verbatim. CI-proven (dev shots sp-a / world-sp-a, runs on d0b8bf1): all four compile,
  zero mixin failures, friends / friends_party / menu (FRIENDS tab) / rshift (Friends button) /
  party_world match 1.21.1-social.
- 1.20.x (all three): `SharedConstants.isAllowedChatCharacter` (StringUtil's is 1.20.5+);
  `ConnectScreen.startConnecting(parent, mc, addr, data, false)` 5 args (TransferState 1.20.5+);
  `GenericDirtMessageScreen` for "Saving world" (GenericMessageScreen is 1.20.5+).
- 1.20.2 / 1.20.4: `mc.disconnect(Screen)` / `mc.disconnect()`, `ServerData.Type.OTHER`, `isRealm()`,
  authlib-5 `fetchProfile` + `SkinManager.getOrLoad` -> PlayerSkin all as 1.21.1 (compile-proven).
- 1.20 (1.20.1): `mc.clearLevel(Screen)` / `mc.clearLevel()` (compile-proven: the disconnect rename is
  1.20.2); `new ServerData(name, ip, false)` (3rd arg = lan); realms check is `mc.isConnectedToRealms()`
  (no ServerData.isRealm); 3-arg `mouseScrolled(mx, my, amount)`; authlib-4 `joinServer(GameProfile, ...)`
  (`user.getGameProfile()`). SocialSkins has no PlayerSkin: it caches a skin ResourceLocation, fills the
  bare UUID profile off-thread with `fillProfileProperties(profile, false)` on Util.backgroundExecutor(),
  then `getInsecureSkinInformation` + `registerTexture` (downloads in the background); default is
  `DefaultPlayerSkin.getDefaultSkin(uuid)`; `PlayerFaceRenderer.draw(g, ResourceLocation, ...)`. Renders
  in the dev shots (demo skips lookups); the real-skin lookup itself is unproven offline.

## Social port (2026-10-12) — 1.18.2, 1.19.2, 1.19.3, 1.19.4 (pre-1.20 Gfx era; agent sp-e)
- Method: 1.19.4 generated from 1.21.1 HEAD (asserted replacements), the other three derived from it; the
  edited existing files took 1.21.1's social commit (5b5baeb) by 3-way merge onto each module's copy (all
  clean except NametagStyleMixin / WaypointHud, done by hand: same hunks, era Matrix4f/Vector4f).
- Identity: User.getGameProfile() and joinServer(GameProfile, token, serverId) (authlib before 1.20.2), as
  each module's CosmeticsService already did. No ServerData.isRealm: Minecraft.isConnectedToRealms().
- Skins: no PlayerSkin -> SocialSkins returns the skin ResourceLocation (PlayerInfo.getSkinLocation for
  players on your server; else SkinManager.registerSkins(new GameProfile(uuid, null), cb, true), which
  fetches the textures off-thread itself) and draws through Gfx.face (PlayerFaceRenderer 1.19.x, manual
  face+hat blit 1.18.2).
- Toasts: Toast.render(PoseStack, ...); the slide-in translation lives on RenderSystem's model-view stack
  on this era, so click positions come from Era.guiOrigin(pose) (model-view x pose), time multiplier via
  Era.toastTimeMultiplier.
- Joining: mc.clearLevel(GenericDirtMessageScreen) / clearLevel() (no Minecraft.disconnect yet),
  ServerData(name, ip, false), 4-arg ConnectScreen.startConnecting (no quick play / transfer state).
- World pass: NO separate WorldRenderEvents.LAST hook. Projection capture + ping beams run from
  OriginClientMod's existing LAST hook INSIDE its identity model-view window (1.18.2-1.19.3 fire LAST inside
  vanilla's clouds/weather camera push; a capture outside it would project with the rotation twice).
- Commands: 1.18.2 = fabric-command-api v1 (ClientCommandManager.DISPATCHER.register), TextComponent /
  TranslatableComponent; 1.19.x = v2 ClientCommandRegistrationCallback + Component.literal.
- FriendsScreen: 3-arg mouseScrolled, SharedConstants.isAllowedChatCharacter; arrow in the Party HUD via
  Era.rotZ. SocialDemo uses Social.version() for the demo friends so the shot reads "Join" like 1.21.1.

## Social port (2026-10-12) — 1.17.1 / 1.16.5 (pre-1.20 Gfx era, 1.16.5 fixed function; agent sp-f)
- Method: the 1.21.1 social/ files + gui/FriendsScreen taken WHOLE, era edits asserted by script
  (1.17.1), then 1.16.5 derived from the PORTED 1.17.1 files (records -> value classes by a script,
  switch rules/expressions -> classic switch, `var` / pattern instanceof / List.of / copyOf / toList /
  isBlank / strip -> Java 8 forms). Edits to existing files (mod menu tab, Right Shift group, HUD
  element, minimap / locator dots, nametag tint, keybind, dev shots) are the same 1.21.1 hunks on both.
- Client command: both pinned Fabric APIs (0.46.1+1.17, 0.42.0+1.16) ship fabric-command-api-v1 WITH
  client commands (ClientCommandManager.DISPATCHER, checked in the tagged sources); no chat-intercept
  mixin needed. v1 forwards only unknown/unparsable commands to the server, so the bare /originpc has
  its own executor like on 1.21.1.
- World pass: NOT a WorldRenderEvents.LAST hook of its own. On both versions LAST fires inside
  vanilla's model-view push, which is why OriginClientMod's LAST hook sets an identity model-view
  window (1.17.1 RenderSystem model-view stack, 1.16.5 RenderSystem.pushMatrix/loadIdentity).
  Social.renderWorld (WaypointHud.capture + ping beams) is called from inside that window; a separate
  hook would rotate the beams and the marker projection by the camera twice.
- Skins: no PlayerSkin / PlayerFaceRenderer / getOrLoad. PlayerInfo.getSkinLocation() for players on
  the current server, else MinecraftSessionService.fillProfileProperties(new GameProfile(id, null),
  false) on a background thread -> SkinManager.registerSkins(profile, cb, false) -> ResourceLocation
  cached; the face is face (8,8) + hat (40,8) blitted from the 64x64 skin through Gfx's 11-arg blit.
  The Friends window tints it with setShaderColor (1.17.1) / color4f (1.16.5) for the fade.
- Toast click positions: ToastComponent moves each toast through the model-view, not the PoseStack it
  hands render(). 1.17.1: origin = Vector4f(0,0,0,1) x pose x RenderSystem.getModelViewMatrix();
  1.16.5: the same with GL11.glGetFloatv(GL_MODELVIEW_MATRIX) (translatef on the fixed-function
  stack; column-major, translation at [12],[13]). No notification-time multiplier (1.20.2+): x1.
- Joining a server: 1.17.1 = the pause menu's disconnect (level.disconnect + clearLevel(
  GenericDirtMessageScreen) / clearLevel()), ServerData(name, ip, false), 4-arg
  ConnectScreen.startConnecting(parent, mc, ServerAddress, data). 1.16.5: no resolver.ServerAddress /
  startConnecting -> mc.setScreen(new ConnectScreen(parent, mc, data)); the address check is "no
  spaces" (no isValidAddress). No ServerData.isRealm() on either (1.20.2+).
- Identity: User.getGameProfile() (no getProfileId), joinServer(GameProfile, token, serverId), no
  HexFormat (hand hex), Gson 2.8.0 `new JsonParser().parse`. 1.16.5 transport = HttpURLConnection with
  the error stream read for 4xx bodies (the service's sentences live there).
- Text: TextComponent / TranslatableComponent; FriendsScreen typing check is
  SharedConstants.isAllowedChatCharacter; 3-arg mouseScrolled. Party HUD arrow rotates with the
  pre-JOML Vector3f.ZP quaternion.
- 1.16.5 icons: "@plus" from the mask atlas (linear filter, no mipmaps, no SDF shader) loses its two thin
  strokes at 14 px -- only the centre pixel survived in the dev shots. FriendsScreen draws that one glyph
  as two bars (vertical in halves so a translucent colour isn't doubled) and its "Start a party" button
  goes without the icon (OriginWindow.button draws from the atlas). Other atlas icons read fine.
- Dev shots: SocialDemo's friends play Social.version(), so the demo reads "Join" as on 1.21.1. Design
  + world sets match 1.21.1-social / 1.21.1-world-social on both modules (party_world: tinted markers,
  straight gold ping beam, Party HUD with arrows + health), zero mixin failures.

## Social port (2026-10-12) — 1.21.6 / 1.21.8 (deferred GuiRenderState era; agent sp-c)
Friends / chat / parties from 1.21.1 (5b5baeb, d037954, b15a952). The two src/ trees stayed byte-identical:
ported on 1.21.8, copied to 1.21.6. 3-way `git merge-file` per edited file (base 5b5baeb^, theirs b15a952);
"clean" merges still needed era edits (MinimapRenderer's party dots came in as 1.21.1's BufferBuilder code).
- social/SocialClient, SocialState, FairPlay, SocialDemo, OriginModMenuScreen tabs, HudEditorScreen, Mods,
  HudElements, MinimapHud/Screen, keys, lang: 1.21.1 verbatim (demo friends report Social.version() so the
  JOIN button looks as on 1.21.1).
- Social.version(): SharedConstants.getCurrentVersion().name() (1.21.6+). Joining a friend from a world:
  `PauseScreen.disconnectFromWorld(mc, ClientLevel.DEFAULT_QUIT_MESSAGE)` (vanilla's own Disconnect routine,
  1.21.6+) then the 6-arg ConnectScreen.startConnecting.
- SocialToast: ToastManager API like FpsCoachToast (render(g, font, t) draws; update(manager, t) decides
  visibility; mc.getToastManager()). The toast's place for click hit-tests is g.pose().m20()/m21()
  (Matrix3x2fStack), not m30/m31.
- SocialSkins: the background lookup fetches only the profile (authlib 6 fetchProfile); each frame asks
  getSkinManager().getInsecureSkin(profile) (module-evidenced, returns the default until loaded) instead of
  1.21.1's getOrLoad future (its result type changed in the 1.21.2 line). Faces fade through
  PlayerFaceRenderer.draw(..., argb) — no setShaderColor in the deferred GUI.
- PartyWorld: arrow on the Matrix3x2fStack (pushMatrix/translate(x,y)/rotate(rad)); ping beams use the
  module's ThickLine.vertical + OriginRenderTypes unchanged (same signatures as 1.21.1).
- WaypointHud: capture() public (Social's LAST hook keeps it fresh with Waypoints off); project() rebuilds the
  same view-projection as render() (perspective from GameRendererAccessor fov × conjugated camera rotation —
  no RenderSystem matrices in this era), factored into viewProj(mc). Party gems drawn inside drawBar, so both
  locator-bar modes get them.
- NametagStyleMixin: the redirects also capture renderNameTag's own params (EntityRenderState, Component,
  PoseStack, MultiBufferSource, int); the party member is `mc.level.getEntity(((PlayerRenderState) s).id)`
  (LevelHeadMixin's lookup). Both drawInBatch shapes (V and I) carry the capture.
- OriginDevWorld: party phase added before the 2.5 s async-PNG wait (phases 6 grab party, 7 wait/stop).
- Verified: dev shots `1.21.{6,8}-sp-c` + `1.21.{6,8}-world-sp-c` (commit 9d2c118) compiled and ran clean first push;
  friends / friends_party / rshift / menu match 1.21.1-social, party_world matches 1.21.1-world-social.

## Social port (2026-10-12) — 1.21.10 / 1.21.11 (1.21.9+ input + submit/extract era; agent sp-d)
- Method: 1.21.1's social/ + gui/FriendsScreen copied into 1.21.11 with scripted era edits, edited files
  3-way merged (base 5b5baeb~1, theirs = 1.21.1 head), then 1.21.10 derived by the back-map
  (dimension().identifier() -> location()). Both modules' versions of every shared hook are identical.
- World hook: Fabric 1.21.9+ WorldRenderEvents.END_MAIN (LAST is gone), ctx.matrices(),
  camera = ctx.worldState().cameraRenderState.pos. Social's hook calls WaypointHud.capture so party
  markers project with Waypoints off.
- WaypointHud has no engine matrices here: project() rebuilds the same view-projection render() uses
  (getProjectionMatrix(fov) * conjugate camera orientation, now one viewProj() helper); Camera.getPosition()
  / getYRot() are gone, so PartyWorld reads WaypointHud.camera() / cameraYaw(mc) (captured per frame).
- Nametag tint: NameTagFeatureRenderer draws NameTagSubmit records with no entity or state id, so
  PartyWorld.nametagTint(Component) matches the tag text against party members' names (only members
  whose player is loaded here, never you) -- the same text match NametagStyleMixin already uses for
  "your own tag". Party colour wins over the global custom colour, as on 1.21.1.
- Toasts: the ToastManager contract (update(ToastManager, long) + getWantedVisibility(), render(g, font,
  time) only draws), Minecraft.getToastManager(); the click position is the pose's Matrix3x2f m20/m21.
- Skins: Minecraft.services().sessionService().fetchProfile -> SkinManager.createLookup(profile, false)
  (MenuPlayerPreview's call), cached as the Supplier (default skin until loaded). PlayerSkin is
  world.entity.player.PlayerSkin. Faces fade via PlayerFaceRenderer.draw(..., argb) -- no setShaderColor.
- Leaving a world to join a friend: Minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE) on
  1.21.10+ (1.21.6-1.21.8 had it as PauseScreen.disconnectFromWorld(mc, msg); CI caught that), then the
  6-arg ConnectScreen.startConnecting as on 1.21.1.
- Input: allowMouseClick hands a MouseButtonEvent; FriendsScreen takes MouseButtonEvent / KeyEvent /
  CharacterEvent, Ctrl + paste come off the KeyEvent, pasted text runs through a private typed(char).
- Small ones: SharedConstants.getCurrentVersion().name(); minimap party dots via the module's GuiTris
  (no immediate BufferBuilder); OriginDevWorld's party capture is two extra phases (grab, then wait for
  the dated-PNG rename before mc.stop()); SocialDemo's friends report Social.version() so the demo's
  JOIN reads like 1.21.1's.
