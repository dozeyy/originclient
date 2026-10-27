# Recipe: title screen, menus, screens, buttons, backdrop/blur

Source of truth: 1.21.1's TitleScreenMixin, TitleLayout, OriginScreenRenderer, OriginButtonRenderer,
ScreenBackgroundMixin, ScreenInvoker. "Proven" means CI and review passed (clean-look port 2026-10-19, redesign 2026-10-12).

## Layout rules that hold on every era
- Find rows by **label key**, never by index or count: Singleplayer = `menu.singleplayer`, Multiplayer = `menu.multiplayer`.
  The demo build swaps in `menu.playdemo`. Count the lead and extra rows from the keys too. This was in the review addendum and most ports missed it.
- Title icon buttons are identified by their message's TranslatableContents key, e.g. `narrator.button.language` / `.accessibility`.
  - ≤1.18.2: `((TranslatableComponent) c).getKey()`.
- Vanilla tooltips go on icon buttons only.
- No per-frame `new Gfx`, GameProfile, or list rebuild in render.

## Per-era hooks

| Era | Panorama / backdrop | Buttons and widgets | Traps (proven unless marked) |
|---|---|---|---|
| G9 1.8.9 | GuiMainMenu drawPanorama | GuiButton | drawPanorama leaves depth+cull ON: restore. GuiSlot.overlayBackground cancel needs a scissor around drawSelectionBox + drawListHeader. Never show GuiModList. See legacy-forge-189.md |
| G8 1.16.5 | 6-arg-era redirects like 1.18.2; TitleScreen white fill = `fill` ordinal 0 | `Screen.addButton` (@Invoker "addButton"); isHovered() includes focus | color4f + TextureManager.bind + fillLayer; alpha test drops faint pixels (Gfx sets alphaFunc GREATER 0) |
| G8 1.17.1 / G7 ≤1.18.2 | redirects PanoramaRenderer.render(FF), 10-arg overlay blit, blitOutlineBlack x2 → wordmark, 8-arg edition blit, splash drawCenteredString, version drawString (`gfx_old.title_screen_118` shape) | `new Button(...)`, x/y public fields, isHoveredOrFocused() / isMouseOver(mx,my) | Pre-1.18: no PlainTextButton, so the copyright is `drawString` ordinal 1 |
| G7 1.19.2 / 1.19.3 | as above | ScreenInvoker bound: `Widget` ≤1.19.2, `Renderable` 1.19.3. Bind on the ERASED descriptor: `<T extends GuiEventListener & NarratableEntry>` works on every 1.19.x | 1.19.3 layouts are GridWidget containers: descend into them. No AbstractSelectionList enableScissor on 1.19.2/1.19.3. renderButton (not renderWidget) on ≤1.19.3 |
| G7 1.19.4 | LogoRenderer exists | renderWidget, isHovered(), getX/getY | enableScissor exists again |
| G2 1.20 | TitleScreen has NO renderPanorama: @Redirect PanoramaRenderer.render(FF)V + blit(RL;IIIIFFIIII)V inside render() | ImageButton icons; no setHeight (@Accessor("height")) | No blur API: PanoramaRenderer + MenuBlur half-size TextureTarget pyramid. widgets.png 9-slice. renderBackground 1-arg. mouseScrolled 3-arg |
| G1 1.20.2 / 1.20.4 | same two redirects (compile on 1.20.1+ too) | SpriteIconButton exists 1.20.2; setHeight 1.20.3+ | `blitSprite("widget/button")` needs 1.20.2+. renderBackground 4-arg |
| G1 1.21 / 1.21.1 | renderPanorama / blur API | renderWidget | — |
| G3 1.21.4 | as 1.21.1 + blit rework | per-blit ARGB tint | Never setShaderColor for blits (batched). No font text during the initial reload |
| G4 1.21.6 / 1.21.8 | hook `Screen.renderPanorama` TAIL, gated on `instanceof TitleScreen` | Matrix3x2fStack pose; splash render takes a float | renderBackground runs BEFORE render. Blur once per frame via `nextStratum()`; guard `firstStratumAfterBlur != Integer.MAX_VALUE` ("Can only blur once" throws before any state change). Bake AA masks |
| G5 1.21.10 | as 1.21.6 | MouseButtonEvent input | — |
| G5 1.21.11 | as 1.21.6 | renderWidget→renderContents; **AbstractButton.renderWidget is final**: a button mixin pre-empts every subclass, so whitelist via `originclient$ownsLook` + CycleButtonSpriteAccessor. Blank inventory squares are the tell | ResourceLocation→Identifier |
| G6 26.x | TitleScreen.extractBackground HEAD draws the Origin backdrop and cancels vanilla's. Logo `LogoRenderer.extractRenderState(GGE;IF)V`, splash `SplashRenderer.extractRenderState(GGE;ILFont;F)V`, panorama `gameRenderer.panorama().extractRenderState(g,w,h)` (26.1: `getPanorama()`, 4-arg with spin) | render→extractRenderState, renderWidget→extractWidgetRenderState, renderContents→extractContents (instance overrides only) | 26.2+ title has Mojang's FriendsButton (kept in the dock); 26.1 has none. Header tabs: 26.2 MenuTabBar$MenuTabButton, 26.1 concrete TabButton. Open screens with `mc.gui.setScreen` (26.2+) / `mc.setScreen` (26.1); never `setScreenAndShow` (the disconnect helper) |

## Workbench title look (wordmark, panel buttons, plain dock, Inter footer) — 2026-10-25, all 20 modules
- Dock tooltips hidden per era: `WidgetTooltipHolder.refreshTooltipForNextRenderPass` HEAD cancel (1.21+, 26.x); `AbstractWidget.updateTooltip` (1.19.3/1.19.4/1.20/1.20.2); `Tooltip.refreshTooltipForNextRenderPass` (1.20.4); none before 1.19.3 (no Tooltip API).
- Workbench tooltip skin: 1.21.2+ `renderTooltipBackground` takes an extra ResourceLocation. Not ported before 1.20.
- Pre-1.19.3 footer: redirect `drawString` ordinals 0 and 1, copyrightWidth=0 (no PlainTextButton). 1.21.11 copyright = PlainTextButton `renderContents`.
- Inter labels: OriginSdfFont everywhere except 1.8.9 and 1.16.5 (OriginTtfFont).
- Baked head: 1.16.5 `TextureManager.getTexture` returns null until `bind()`; 26.x bakes on the CPU via `DynamicTexture.getPixels()` (skin id `PlayerSkin.body().texturePath()`; `TextureManager.register` closes the old texture).
- Press state: `MouseHandler.isLeftPressed()` on 1.21.6+ and 26.x.
- 26.3 dev-shots: opening a screen resyncs the cursor to the real Xvfb pointer; put it back after each setScreen or a stray hover lands in the shot.
- Write the footer middle dot as `·` (sources stay ASCII). Normalize CRLF before diff3-merging a donor file.

## Dark title buttons + icon dock (2026-10-27, G1/G2 1.20-1.21; javap-proven)
- `ResourceLocation.fromNamespaceAndPath` / `withDefaultNamespace` are 1.21+; 1.20.x only has `new ResourceLocation(ns, path)`.
- 1.20.2+: the big button is `blitSprite(widget/button[_highlighted|_disabled])` (the module's sprite overrides make it dark);
  Friends = `toast/social_interactions` sprite, 20 x 20.
- 1.20 (no atlas): the big button is AbstractButton.renderWidget's own call, `blitNineSliced(tex, x, y, w, h, 20, 4, 200, 20, 0,
  46 + 20 * state)` (state 0 disabled, 1 normal, 2 hovered/focused), bound through `OriginDarkWidgets.texture(..., WIDGETS, BUTTON)`,
  the same dark sheet DarkButtonMixin uses. Friends = `TutorialToast.Icons.SOCIAL_INTERACTIONS.render(g, x, y)`: vanilla's own
  20 x 20 toasts.png icon (176 + 2*20, 1*20), the art 1.20.2 split into the sprite.

## Proof
- Use `dev-shots set=design`. For the clean look also set `ORIGIN_SHOTS_SET=clean` with a `title_hover` shot on Quit.
- Run `style=menu|buttons|vanilla` to prove that Main Menu Style and Button Style fall back to vanilla.
- Compare against 1.21.1's shots from the same set and label.

Deep notes: PORTING-ERA-NOTES `### Findings from the redesign port`, `## Fix-round port — <modules>`.
