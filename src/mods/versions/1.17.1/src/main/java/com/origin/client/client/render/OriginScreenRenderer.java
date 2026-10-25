package com.origin.client.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import org.lwjgl.opengl.GL11;

// Shared Origin screen rendering, used by the loading screen
// (LoadingOverlayMixin), the main menu (TitleScreenMixin) and every out-of-world
// menu (ScreenBackgroundMixin):
//
//  - LOADING (startup, world load, Connecting): the one loading picture
//    (LoadingLook, ported from 1.21.1): the real Origin logo, a plain capsule bar
//    and one caption. Startup sits on near-black with a soft centre lift; the
//    world load and Connecting keep this version's own blurred panorama.
//  - ORIGIN'S OWN SCREENS (Right Shift mod menu family): charcoal + glow + grain
//    + vignette (renderTitleBackground).
// No drawn rings anywhere: the only ring shapes are inside origin_logo.png itself
// (Will, 2026-10-25: drawn look-alikes read as a fake logo).
//
// Textures load via the classloader (not the resource manager), so this is
// safe during the earliest loading overlay while resources are still loading,
// and degrades gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {

	// Fail-soft master switch: if ANY Origin screen draw throws (e.g. a
	// Minecraft GUI API that renamed/changed shape in a different game
	// version), rendering flips to vanilla permanently for this session
	// instead of crashing. Callers that cancel vanilla drawing must only
	// cancel when the boolean-returning entry points report success, and
	// must gate standalone suppressions on isActive(). This is the runtime
	// half of the multi-version contract (see VERSIONS.md); the mixin
	// configs' required:false is the load-time half.
	private static volatile boolean broken = false;

	private static volatile boolean loaded = false;
	private static ResourceLocation grainId;

	// Edge vignette (transparent core -> soft black corners). Blitted full-screen
	// to darken the frame edges so the centered wordmark reads as the focal
	// point -- depth from lightness only, no hue. Null -> skip (no crash).
	private static ResourceLocation vignetteId;
	private static final int VIGNETTE_TEX = 1024;

	// Baked "ORIGIN" wordmark (Michroma, all-caps + glow bloom). Null -> fall
	// back to vanilla drawString.
	private static ResourceLocation wordmarkId;
	private static int wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH;

	// The startup bar's shown fill (-1 = none yet): it glides to the reload's real
	// progress instead of stepping with it, and never moves backward (LoadBar).
	private static double loadFill = -1;
	private static final LoadBar LOAD_BAR = new LoadBar();
	/** The startup and world-loading bars' glide time constant (ms). */
	private static final double FILL_TAU = 140.0;

	// ---- Main-menu ambient layer ----
	// Cursor-follow glow (the website's two-layer spotlight): one radial
	// gradient texture drawn twice -- a tight core that snaps to the cursor
	// and a soft halo that trails it with lerped physics, both blooming when
	// hovering a clickable. State is static: one cursor, one glow.
	private static ResourceLocation radialGlowId;
	private static final int RADIAL_TEX = 512;

	private OriginScreenRenderer() {
	}

	/** False once any Origin screen draw has failed; vanilla takes over. */
	public static boolean isActive() {
		return !broken;
	}

	private static boolean fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error(
				"Origin screen rendering failed; falling back to vanilla screens for this session", t);
		return false;
	}

	// ---- Public entry points ----

	/**
	 * Startup resource-load screen: near-black with a soft centre lift and the one
	 * loading picture (LoadingLook: the real Origin logo, the plain capsule bar and
	 * the "Loading resources NN%" caption).
	 */
	public static void renderLoading(Gfx guiGraphics, float progress) {
		// The game is on screen now (Origin's scene, or vanilla's if ours
		// broke): Origin Launcher can lower its progress screen.
		com.origin.client.client.OriginHandoff.loading();
		// Drawn at TAIL over vanilla's overlay, so skipping when broken simply
		// reveals the vanilla loading screen -- no cancel to unwind.
		if (broken) {
			return;
		}
		// Vanilla mode (Button Style + Main Menu Style both off): Mojang's loading screen
		// stays, but the handoff above and the per-frame texture uploads still run.
		if (com.origin.client.client.OriginStyle.vanillaMenus()) {
			com.origin.client.client.OriginAssets.uploadSlice();
			return;
		}
		try {
			renderLoading0(guiGraphics, reloadProgress(progress), true);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/**
	 * The startup reload's real fraction done (1 once it has finished), read from
	 * the overlay through LoadingOverlayAccessor; {@code fallback} (vanilla's own
	 * frame-by-frame smoothed value) if that accessor didn't apply. The bar
	 * glides to it in renderLoading0, the same at any frame rate.
	 */
	private static float reloadProgress(float fallback) {
		if (Minecraft.getInstance().getOverlay() instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor overlay) {
			net.minecraft.server.packs.resources.ReloadInstance reload = overlay.originclient$reload();
			if (reload != null) {
				return reload.isDone() ? 1f : reload.getActualProgress();
			}
		}
		return fallback;
	}

	/**
	 * The startup loading screen at {@code alpha} over whatever is already drawn
	 * this frame — the loading -> title dissolve (LoadingOverlayRevealMixin).
	 * Loading is done by then, so the bar shows full. With {@code toTitle} (the
	 * game's first load, landing on the title screen) the mark doesn't dissolve:
	 * it flies, solid, onto the title's logo spot and stays there as the logo
	 * (renderTitleWordmark leaves it alone meanwhile). False (nothing to cancel)
	 * when Origin rendering is off or this draw fails.
	 */
	public static boolean renderLoadingFade(Gfx guiGraphics, float alpha, boolean toTitle) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = Math.max(0f, Math.min(1f, alpha));
			// The title drew its labels and icons a little in front (z > 0); without
			// this they win the depth test against the loading layer and show
			// at full strength while everything else dissolves. GL_DEPTH_BUFFER_BIT,
			// the same clear GameRenderer does before the GUI.
			RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			shade(1f, 1f, 1f, 1f);
			// The title shows the real Origin logo, still, so the loading layer simply
			// dissolves over it.
			renderLoading0(guiGraphics, 1f, false);
			guiGraphics.flush();
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	// Every shader colour in this file goes through shade(): it multiplies in
	// layerAlpha, so a whole scene can be drawn translucent (the loading -> title
	// dissolve) without each helper knowing. GuiGraphics.fill flushes per call
	// outside a managed batch, so fills pick it up too. 1 everywhere else.
	private static float layerAlpha = 1f;

	private static void shade(float r, float g, float b, float a) {
		RenderSystem.setShaderColor(r, g, b, a * layerAlpha);
	}

	private static void renderLoading0(Gfx guiGraphics, float progress, boolean drive) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		double scene = SceneClock.sceneMs();
		// The fill only ever moves forward within a load and is full exactly when
		// the reload is done (LoadBar: the reload's own fraction dips mid-load as
		// work is queued); a load after a finished one starts it over. The
		// dissolve passes 1: the bar finishes its glide while the title appears.
		net.minecraft.server.packs.resources.ReloadInstance barReload = currentReload();
		loadFill = LOAD_BAR.update(barReload, barReload != null ? barReload.isDone() : clamped >= 1f, clamped, scene, FILL_TAU);
		com.origin.client.client.OriginFrameLog.bar(barReload, clamped, loadFill);

		if (drive) {
			// Origin's textures were decoded on the reload's workers; upload one
			// loader's worth per frame now, so the title's first frames don't.
			com.origin.client.client.OriginAssets.uploadSlice();
		}
		// The loading picture (LoadingLook, ported from 1.21.1): near-black with a soft
		// centre lift, the real Origin logo, the plain capsule bar and the stage line
		// with the reload's real percentage.
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		guiGraphics.fill(0, 0, w, h, fade(OriginTheme.CLEAN_BG));
		drawCenterLift(guiGraphics, w, h, 0.05f);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);   // the lift's shade() leaves the dissolve in the colour
		LoadingLook.draw(guiGraphics, layerAlpha, loadFill, startupCaption(loadFill), true);
	}

	// The title's first-use costs (textures, driver shader builds, glyphs) used to
	// be guessed at here, one hand-picked warm-up per loading frame. They are now
	// paid by the real title screen itself, rendered for real under the finished
	// loading picture during the settle hold (LoadingOverlayRevealMixin), so
	// nothing has to be guessed and nothing is missed.

	private static net.minecraft.server.packs.resources.ReloadInstance currentReload() {
		Object overlay = Minecraft.getInstance().getOverlay();
		return overlay instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor
				? ((com.origin.client.client.mixin.loading.LoadingOverlayAccessor) overlay).originclient$reload() : null;
	}

	// The startup caption ("Loading resources NN%") for a fill, rebuilt only when
	// the whole percent changes (no string built per frame while it holds still).
	// ASCII on purpose: it is drawn in the boot font before any translation exists.
	private static int pctShown = -1;
	private static String pctLabel = "Loading resources 0%";

	private static String startupCaption(double fill) {
		int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, fill)) * 100);
		if (pct != pctShown) {
			pctShown = pct;
			pctLabel = "Loading resources " + pct + "%";
		}
		return pctLabel;
	}

	/**
	 * The settle hold (LoadingOverlayRevealMixin): the finished loading picture,
	 * opaque, over the screen the hold has just rendered for real underneath, so
	 * the screen's first-use costs are paid where nobody can see them. Bar full.
	 * False (nothing to cancel) when Origin rendering is off or
	 * this draw fails.
	 */
	public static boolean renderLoadingHold(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = 1f;
			// The screen drew its labels a little in front (z > 0): clear depth so the
			// loading picture covers them, the same clear GameRenderer does before the GUI.
			RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			shade(1f, 1f, 1f, 1f);
			renderLoading0(guiGraphics, 1f, false);
			guiGraphics.flush();
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	/**
	 * Full-screen loading/progress scene for the world-load screens
	 * (LevelLoadingScreen, ReceivingLevelScreen, ProgressScreen): this version's
	 * own panorama behind the one loading picture (LoadingLook), the screen's own
	 * title as the caption. Drawn as a HEAD-cancel takeover, so it replaces
	 * vanilla's chunk map / dirt / progress clutter with one clean Origin scene.
	 */
	public static boolean renderLoadingScene(Gfx guiGraphics, net.minecraft.network.chat.Component title) {
		return renderLoadingScene(guiGraphics, title, -1f);
	}

	/**
	 * The world-loading scene with a real fraction done ({@code progress} 0..1,
	 * the chunk count LevelLoadingScreen reports), or -1 when the screen has no
	 * real measure — then the bar sweeps. Never a made-up percentage.
	 */
	public static boolean renderLoadingScene(Gfx guiGraphics, net.minecraft.network.chat.Component title, float progress) {
		// HEAD-cancel takeover: callers must only ci.cancel() when this
		// returns true, so a failure mid-draw falls back to the vanilla screen.
		if (broken) {
			return false;
		}
		try {
			renderLoadingScene0(guiGraphics, title, progress);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// The world-loading scene's shown bar fill, gliding to each new count.
	// sceneFill < 0 = the bar is sweeping.
	private static double sceneFill = -1;
	// Never backward within a world load, never full before the world is up (LoadBar.scene).
	private static final LoadBar SCENE_BAR = new LoadBar();

	private static void renderLoadingScene0(Gfx guiGraphics, net.minecraft.network.chat.Component title, float progress) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		// This version's own panorama, blurred and dimmed, behind the loading picture.
		// Solid background if the panorama can't draw.
		if (!backdrop(mc.screen, guiGraphics, mc.getDeltaFrameTime(), true, OriginTheme.CLEAN_DIM_LOADING)) {
			guiGraphics.fill(0, 0, w, h, OriginTheme.CLEAN_BG);
		}

		// The fill glides to each new count and only moves forward within one world
		// load; it sweeps until the load's first measured stage (LoadBar.scene).
		double now = SceneClock.sceneMs();
		sceneFill = SCENE_BAR.scene(com.origin.client.client.hud.FrameStats.frames(), progress, now, FILL_TAU);
		if (com.origin.client.client.OriginFrameLog.ENABLED) {   // off: no title string built every frame
			com.origin.client.client.OriginFrameLog.sceneBar(title == null ? "" : title.getString(), progress, sceneFill);
		}
		// The caption: vanilla's own "Preparing spawn area: N%" while the spawn area
		// loads, else the screen's title; never an invented stage name. Rebuilt only
		// when the title, the whole percent or the language changes (compared by
		// reference: the screen keeps one title Component).
		int pct = progress >= 0f ? Math.round(Math.max(0f, Math.min(1f, progress)) * 100f) : -1;
		String lang = mc.options.languageCode;
		if (title != labelTitle || pct != labelPct || !java.util.Objects.equals(lang, labelLang)) {
			labelTitle = title;
			labelPct = pct;
			labelLang = lang;
			labelCaption = sceneCaption(title, pct);
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		LoadingLook.draw(guiGraphics, 1f, sceneFill, labelCaption, false);
	}

	// The loading scene's caption and what it was built from.
	private static net.minecraft.network.chat.Component labelTitle;
	private static String labelLang;
	private static int labelPct = Integer.MIN_VALUE;
	private static String labelCaption = "";

	/** The stage line: vanilla's spawn line while the spawn area loads (pct >= 0), else the screen's title. */
	private static String sceneCaption(net.minecraft.network.chat.Component title, int pct) {
		if (pct >= 0) {
			return net.minecraft.client.resources.language.I18n.get("menu.preparingSpawn", pct);
		}
		return title == null ? "" : title.getString().trim();
	}

	/**
	 * Connecting to a server: the same loading picture as the other screens, the
	 * connection status as its caption, over this version's own menu backdrop
	 * (ScreenBackgroundMixin). ConnectScreenMixin calls this in place of vanilla's
	 * status line; the screen's own Cancel button draws after it, seated below the
	 * picture ({@link #connectCancelTop}). The logo sits at exactly the spot every
	 * other loading screen uses. False when Origin rendering is off or the draw
	 * failed: the caller then draws vanilla's status line.
	 *
	 * @param pose the frame's PoseStack (pre-1.20 GameRenderer makes a new one every
	 *             frame, so one Gfx is re-pointed at it instead of allocated per frame)
	 */
	public static boolean renderConnecting(PoseStack pose, net.minecraft.network.chat.Component status) {
		if (broken) {
			return false;
		}
		try {
			if (status != connectStatus) {
				connectStatus = status;
				connectCaption = status == null ? "" : status.getString().trim();
			}
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			LoadingLook.draw(CONNECT_GFX.rebind(pose), 1f, -1, connectCaption, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	/**
	 * Where Connecting's Cancel button goes (GUI px, its top edge): at 62 % of the
	 * height, and always at least 12 GUI px under the loading picture's caption, so
	 * the picture never has to move for it; kept 8 GUI px clear of the bottom when
	 * the window allows (1.21.1's rule).
	 */
	public static int connectCancelTop(int screenH, int buttonH) {
		double clearOfPicture = LoadingLook.logoCentreGui() + LoadingLook.belowCentreGui() + 12.0;
		int top = (int) Math.max(Math.round(screenH * 0.62) - buttonH / 2, Math.ceil(clearOfPicture));
		return Math.max(0, Math.min(top, screenH - buttonH - 8));
	}

	private static final Gfx CONNECT_GFX = new Gfx(null);
	private static net.minecraft.network.chat.Component connectStatus;
	private static String connectCaption = "";

	/** A colour with layerAlpha (the loading -> title dissolve) multiplied into its alpha. */
	private static int fade(int argb) {
		return fadeA(argb, layerAlpha);
	}

	private static int fadeA(int argb, float k) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, k)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	// ---- clean backdrop: this version's own panorama ----

	// Separate latch from `broken`: a failed panorama/blur call only hands the
	// backdrop back to vanilla.
	private static volatile boolean backdropBroken = false;

	// Pre-1.20.5 there is no shared Screen.PANORAMA: each TitleScreen owns a
	// PanoramaRenderer over the public static TitleScreen.CUBE_MAP. Origin keeps ONE
	// renderer for every menu (TitleScreenMixin routes the title's own draw here
	// too), so the scene keeps turning, unbroken, from the title into Options and
	// back -- the 1.21.1 behaviour. Created on first use, after the game's textures
	// exist; the cube map's six images load through the texture manager on demand.
	private static net.minecraft.client.renderer.PanoramaRenderer panorama;

	/** The one shared panorama (spun by {@code delta}, drawn at {@code alpha}). */
	public static void renderPanorama(float delta, float alpha) {
		if (panorama == null) {
			panorama = new net.minecraft.client.renderer.PanoramaRenderer(net.minecraft.client.gui.screens.TitleScreen.CUBE_MAP);
		}
		panorama.render(delta, alpha);
	}

	/**
	 * True while the panorama backdrop is healthy. Pre-1.20 the backdrop is drawn by
	 * a beforeRender event and vanilla's dirt is cancelled separately
	 * (ScreenBackgroundMixin), so the cancel asks this: a failed backdrop gives the
	 * menus their vanilla background back instead of an undrawn frame.
	 */
	public static boolean menuBackdropActive() {
		return !broken && !backdropBroken;
	}

	/**
	 * The title mixin's own panorama draw threw: latch the Origin backdrop off for the
	 * session (menuBackdropActive() goes false, vanilla's backdrop returns) and log it once.
	 */
	public static void panoramaFailed(Throwable t) {
		if (!backdropBroken) {
			backdropBroken = true;
			com.origin.client.OriginClient.LOGGER.error("Origin panorama failed on the title screen; vanilla backdrop for this session", t);
		}
	}

	/** Vanilla 1.21.1's default Menu Background Blurriness (Accessibility); this era has no such option. */
	private static final int MENU_BLUR_RADIUS = 5;

	/**
	 * Out-of-world menus: THIS version's own Mojang panorama (TitleScreen.CUBE_MAP,
	 * read from vanilla resources at runtime, so every version module shows its
	 * own update's scene), blurred (MenuBlur's post chain at vanilla 1.21.1's
	 * default radius: this era has no vanilla menu blur), dimmed and vignetted.
	 * False: the caller keeps vanilla's.
	 */
	public static boolean renderMenuBackdrop(net.minecraft.client.gui.screens.Screen screen, Gfx g, float partialTick) {
		return backdrop(screen, g, partialTick, true, OriginTheme.CLEAN_DIM_MENU);
	}

	/** Title: the (fading-in) panorama is already drawn (TitleScreenMixin); blur + a lighter dim over it. */
	public static boolean renderTitleBackdropOver(net.minecraft.client.gui.screens.Screen screen, Gfx g, float partialTick) {
		return backdrop(screen, g, partialTick, false, OriginTheme.CLEAN_DIM_TITLE);
	}

	private static boolean backdrop(net.minecraft.client.gui.screens.Screen screen, Gfx g, float partialTick,
									boolean panorama, int dim) {
		if (backdropBroken || screen == null) {
			return false;
		}
		try {
			ensureLoaded();
			if (panorama) {
				renderPanorama(partialTick, 1f);
			}
			// A failed blur latches itself off inside MenuBlur; the panorama stays, unblurred.
			com.origin.client.client.mods.MenuBlur.blurBackdrop(MENU_BLUR_RADIUS, partialTick);
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			g.fill(0, 0, w, h, dim);
			drawVignette(g, w, h);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			return true;
		} catch (Throwable t) {
			backdropBroken = true;
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			com.origin.client.OriginClient.LOGGER.error("Origin panorama backdrop failed; vanilla backdrop for this session", t);
			return false;
		}
	}

	/**
	 * Screens whose loading mixin HEAD-cancels render and draws the whole loading
	 * scene (backdrop included): the pre-1.20 beforeRender backdrop (OriginClientMod)
	 * skips them, so the panorama and blur aren't drawn twice a frame there.
	 */
	public static boolean drawsOwnBackdrop(net.minecraft.client.gui.screens.Screen screen) {
		return screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
				|| screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
				|| screen instanceof net.minecraft.client.gui.screens.ProgressScreen
				|| screen instanceof net.minecraft.client.gui.screens.GenericDirtMessageScreen;
	}

	// ---- the real Origin logo, crisp at any size ----

	// origin_logo.png (256 px, white three-ring mark) downsampled ONCE per
	// physical size with a 4x4 box filter and blitted 1:1, so its thin strokes
	// stay smooth instead of shimmering under plain minification.
	private static final java.util.Map<Integer, ResourceLocation> LOGO_SIZES = new java.util.HashMap<>();
	private static NativeImage logoSrc;
	private static boolean logoFailed;

	/** Draws the logo centred on (cx, cy), {@code size} GUI px square, at alpha. */
	static void drawLogo(Gfx g, double cx, double cy, double size, float alpha) {
		int gs = com.origin.client.client.gui.OriginSurface.scale();
		int ps = (int) Math.round(size * gs);
		ResourceLocation id = logoAt(ps);
		if (id == null) {
			OriginUi.logo(g, cx, cy, (int) Math.round(size), alpha);
			return;
		}
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0f, Math.min(1f, alpha)));
		int px = (int) Math.round(cx * gs - ps / 2.0), py = (int) Math.round(cy * gs - ps / 2.0);
		g.blit(id, px, py, 0f, 0f, ps, ps, ps, ps);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	private static ResourceLocation logoAt(int ps) {
		if (logoFailed || ps <= 0 || ps > 512) {
			return null;
		}
		ResourceLocation id = LOGO_SIZES.get(ps);
		if (id != null) {
			return id;
		}
		try {
			if (logoSrc == null) {
				try (InputStream in = open(com.origin.client.client.gui.OriginUi.LOGO_PATH)) {
					logoSrc = NativeImage.read(in);
				}
			}
			int sw = logoSrc.getWidth(), sh = logoSrc.getHeight();
			NativeImage dst = new NativeImage(ps, ps, true);
			for (int y = 0; y < ps; y++) {
				for (int x = 0; x < ps; x++) {
					double aSum = 0, cSum = 0;
					for (int sy = 0; sy < 4; sy++) {
						for (int sx = 0; sx < 4; sx++) {
							int tx = Math.min(sw - 1, (int) ((x + (sx + 0.5) / 4.0) * sw / ps));
							int ty = Math.min(sh - 1, (int) ((y + (sy + 0.5) / 4.0) * sh / ps));
							int c = logoSrc.getPixelRGBA(tx, ty);
							double a = ((c >>> 24) & 0xFF) / 255.0;
							aSum += a;
							cSum += a * ((c & 0xFF) / 255.0);
						}
					}
					double a = aSum / 16.0;
					int v = a > 0 ? (int) Math.round(Math.min(1.0, cSum / aSum) * 255) : 255;
					dst.setPixelRGBA(x, y, ((int) Math.round(a * 255) << 24) | (v << 16) | (v << 8) | v);
				}
			}
			DynamicTexture tex = new DynamicTexture(dst);
			tex.setFilter(false, false);
			id = new ResourceLocation("originclient", "logo_px_" + ps);
			Minecraft.getInstance().getTextureManager().register(id, tex);
			LOGO_SIZES.put(ps, id);
			return id;
		} catch (Throwable t) {
			logoFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin logo bake failed; using the plain logo blit", t);
			return null;
		}
	}

	/**
	 * "ORIGIN" in Minecraft's own letters at 2x, spaced out (the mockup's tracked
	 * wordmark), bottom of the caps on {@code baseline}. Drawn from the boot font
	 * (vanilla's ascii.png) so the splash, which paints before fonts are baked,
	 * and the title screen show the same pixels.
	 */
	private static void drawWordmarkText(Gfx g, double cx, double baseline, int argb) {
		bootText(g, "ORIGIN", cx, baseline - 14, 2f, 3, argb, 0.5f);
	}

	/** A barely-there radial lift behind the centre (the splash's "soft centre lift"). */
	private static void drawCenterLift(Gfx g, int w, int h, float alpha) {
		if (radialGlowId == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		shade(1f, 1f, 1f, alpha);
		int d = (int) Math.round(Math.max(w, h) * 0.9);
		g.blit(radialGlowId, (w - d) / 2, (int) Math.round(h * 0.44 - d / 2.0), d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
		shade(1f, 1f, 1f, 1f);
	}

	/**
	 * The backdrop behind Origin's own screens (mod menu, HUD editor, Origin
	 * windows): charcoal, one soft jade glow, the grain and the vignette. No drawn
	 * rings (Will, 2026-10-25). Returns true only if the Origin backdrop actually
	 * drew; callers that cancel vanilla's own backdrop must key off this.
	 */
	public static boolean renderTitleBackground(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			guiGraphics.fill(0, 0, w, h, OriginTheme.BG);
			drawMenuGlow(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
			drawVignette(guiGraphics, w, h);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** One restrained jade highlight, top right, under the grain (kept from the Ion Jade grade). */
	private static void drawMenuGlow(Gfx guiGraphics, int w, int h) {
		if (radialGlowId == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		shade(0x4B / 255f, 0xC8 / 255f, 0xAE / 255f, 0.07f);
		int d = (int) Math.round(w * 1.1);
		guiGraphics.blit(radialGlowId, (int) Math.round(w * 0.70 - d / 2.0), (int) Math.round(h * 0.15 - d / 2.0),
				d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
		shade(1f, 1f, 1f, 1f);
	}

	/**
	 * Main menu: the website's two-layer cursor spotlight, drawn over the ring
	 * background but under the widgets/wordmark. The core (small, brighter)
	 * snaps to the cursor every frame; the halo (large, faint) trails it via
	 * the website's exact per-frame lerp (0.12 @60fps, dt-corrected here so it
	 * feels identical at any framerate). Both grow + brighten while hovering a
	 * clickable, mirroring the site's .is-active bloom. Sizes scale with the
	 * GUI width the way the CSS pixel sizes relate to a typical viewport.
	 */
	public static void renderTitleCursorGlow(Gfx guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
		// Retired by the pixel-grid commit -- deliberately empty.
		//
		// This was a two-layer mouse-follow spotlight ported from the website: a
		// soft radial bloom trailing the cursor. It's the one effect that cannot
		// exist on Minecraft's pixel grid at any size, because the blur IS the
		// effect. Kept as an empty method rather than deleted: both call sites are
		// ScreenBackgroundMixin's two mutually-exclusive paths (cancelled-HEAD for
		// out-of-world menus, TAIL for in-world), and that pairing is subtle enough
		// to be worth leaving undisturbed.
	}


	/**
	 * Main menu: the Origin logo and wordmark above the button stack, still
	 * (the loading screen dissolves over it after the first load).
	 */
	public static boolean renderTitleWordmark(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			TitleLayout L = TitleLayout.of(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
			// The REAL Origin logo (origin_logo.png), still, and the spaced ORIGIN
			// wordmark under it (Will 2026-10-19: use the real logo, not a redraw).
			// TitleLayout drops the logo, then the wordmark, when extra rows need the room.
			if (L.markSize > 0) {
				drawLogo(guiGraphics, L.markCenterX, L.markCenterY, L.markSize, 1f);
			}
			if (L.showWordmark) {
				drawWordmarkText(guiGraphics, L.markCenterX, L.wordBaseline, OriginTheme.CLEAN_TEXT);
			}
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/**
	 * Draw the baked "ORIGIN" wordmark centered on (cx, cy) at the given ink
	 * height, anywhere. Used by the HUD editor's Right-Shift header. Returns
	 * false (caller falls back to the vanilla font) if the texture is missing or
	 * Origin rendering has failed for the session.
	 */
	public static boolean renderWordmarkAt(Gfx guiGraphics, double cx, double cy, double targetInkHeight, float alpha) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (wordmarkId == null || wmInkH <= 0) {
				return false;
			}
			drawBakedInk(guiGraphics, wordmarkId, wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH, cx, cy, targetInkHeight, alpha);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** Shared: blit a baked-text texture so its ink box centers on (cx, cy), ink scaled to targetInkHeight. */
	private static void drawBakedInk(Gfx guiGraphics, ResourceLocation tex, int texW, int texH,
									 int inkX, int inkY, int inkW, int inkH, double cx, double cy, double targetInkHeight, float alpha) {
		float scale = (float) (targetInkHeight / inkH);
		double icx = (inkX + inkW / 2.0) * scale;
		double icy = (inkY + inkH / 2.0) * scale;
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(cx - icx, cy - icy, 0);
		pose.scale(scale, scale, 1f);
		RenderSystem.enableBlend();
		shade(1f, 1f, 1f, alpha);
		guiGraphics.blit(tex, 0, 0, 0, 0, texW, texH, texW, texH);
		shade(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	/**
	 * Main menu: a small account chip (player head + username) in the top-left,
	 * on a vanilla button. Head via the skin manager (default skin shows first,
	 * the real one pops in async); if the head can't be resolved the chip falls
	 * back to the Origin logo, and if the whole chip throws it flips the
	 * renderer to vanilla like any Origin draw.
	 */
	public static void renderTitleAccountChip(Gfx guiGraphics) {
		if (broken) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.User user = mc.getUser();
			if (user == null) {
				return;
			}
			String name = user.getName();
			if (name == null || name.isEmpty()) {
				return;
			}
			Font font = mc.font;
			// The player chip in the top-left corner: the player's head and name on
			// Minecraft's own button sprite, so it matches every button on the title
			// (Will, 2026-10-19: "use the same style for the player in the top left").
			// Not clickable (there is no account menu here), so it never highlights.
			int x = TitleLayout.CORNER, y = TitleLayout.CORNER, chipH = TitleLayout.CORNER_SIZE;
			int head = 12, pad = 4, gap = 5;
			int textW = font.width(name);
			int chipW = pad + head + gap + textW + pad + 2;
			RenderSystem.enableBlend();
			RenderSystem.enableDepthTest();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			guiGraphics.vanillaButton(x, y, chipW, chipH, 1);   // widgets.png, normal row
			int hx = x + pad, hy = y + (chipH - head) / 2;
			boolean drewHead = false;
			try {
				// Pre-1.19 there is no PlayerFaceRenderer and no SkinManager
				// .getInsecureSkinLocation — the skin is resolved the era way (default
				// skin at once, the real one swapped in async by registerSkins) and the
				// head is blitted straight from the 64x64 skin: face (8,8) + hat (40,8).
				net.minecraft.resources.ResourceLocation skin = resolveChipSkin(mc, user);
				guiGraphics.blit(skin, hx, hy, head, head, 8f, 8f, 8, 8, 64, 64);
				RenderSystem.enableBlend();
				guiGraphics.blit(skin, hx, hy, head, head, 40f, 8f, 8, 8, 64, 64);
				RenderSystem.disableBlend();
				drewHead = true;
			} catch (Throwable ignored) {
				// fall back to the logo below
			}
			if (!drewHead) {
				drawLogo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}
			int tx = hx + head + gap;
			guiGraphics.drawString(font, name, tx, y + (chipH - 8) / 2, 0xFFFFFFFF, true);   // vanilla button text
		} catch (Throwable t) {
			fail(t);
		}
	}

	// The chip head's skin texture. Defaults immediately (so the chip never
	// waits), then SkinManager.registerSkins swaps in the real skin async — the
	// pre-1.19 equivalent of the later getInsecureSkinLocation. One request per
	// session; every failure path just keeps the default skin. (This module's own
	// boot-verified chip code, kept through the redesign.)
	private static volatile net.minecraft.resources.ResourceLocation chipSkin = null;
	private static volatile boolean chipSkinRequested = false;

	private static net.minecraft.resources.ResourceLocation resolveChipSkin(Minecraft mc, net.minecraft.client.User user) {
		net.minecraft.resources.ResourceLocation cached = chipSkin;
		if (cached != null) {
			return cached;
		}
		com.mojang.authlib.GameProfile profile = user.getGameProfile();
		if (!chipSkinRequested) {
			chipSkinRequested = true;
			try {
				mc.getSkinManager().registerSkins(profile,
						(type, location, texture) -> {
							if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) {
								chipSkin = location;
							}
						}, true);
			} catch (Throwable ignored) {
				// stay on the default skin
			}
		}
		java.util.UUID id = profile.getId();
		return id != null
				? net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(id)
				: net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();
	}

	// ---- Primitives ----

	private static void drawGrain(Gfx guiGraphics, int w, int h) {
		if (grainId == null) {
			return;
		}
		// Tile in REAL pixels (pose-scale 1/guiScale): drawn in GUI units the
		// 1px noise texels become guiScale-sized blocks -- the "low res" grain
		// Will flagged. At 1:1 each noise grain is a single screen pixel,
		// like the website's.
		double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		int realW = (int) Math.ceil(w * gs);
		int realH = (int) Math.ceil(h * gs);
		int tile = 256; // matches grain.png; larger tile -> ~3x fewer blits/frame
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		RenderSystem.enableBlend();
		shade(1f, 1f, 1f, 0.028f);
		for (int y = 0; y < realH; y += tile) {
			for (int x = 0; x < realW; x += tile) {
				guiGraphics.blit(grainId, x, y, 0, 0, tile, tile, tile, tile);
			}
		}
		shade(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	/** Full-screen edge vignette (the 1024px texture stretched to the GUI size). */
	private static void drawVignette(Gfx guiGraphics, int w, int h) {
		if (vignetteId == null) {
			return;
		}
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) w / VIGNETTE_TEX, (float) h / VIGNETTE_TEX, 1f);
		RenderSystem.enableBlend();
		shade(1f, 1f, 1f, 1f);
		guiGraphics.blit(vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX);
		pose.popPose();
	}

	// ---- BOOT FONT ----
	// The loading overlay first paints DURING the initial resource reload,
	// before Minecraft has baked any font glyphs. Text drawn through mc.font in
	// that window comes out as one missing-glyph box per letter -- the six
	// boxes Will saw in place of "ORIGIN" while the game loaded, on every
	// version. So the loading mark reads Minecraft's own ascii.png straight off
	// the game jar (plain classpath read: there from the first frame, no
	// resource manager involved) and blits the glyphs itself. Same pixels the
	// vanilla font draws, so the mark looks identical before and after fonts
	// finish loading. If the sheet can't be read, no text is drawn at all --
	// an empty frame is better than boxes.
	private static ResourceLocation bootFontId;
	private static int bootFontTexW, bootFontTexH, bootFontCell;
	private static int[] bootFontWidth; // per char code 0-255: glyph width in px, 0 = no glyph
	private static boolean bootFontTried;

	private static synchronized boolean ensureBootFont() {
		if (bootFontTried) {
			return bootFontId != null;
		}
		bootFontTried = true;
		try {
			NativeImage image;
			try (InputStream in = open("/assets/minecraft/textures/font/ascii.png")) {
				image = NativeImage.read(in);
			}
			int cell = image.getWidth() / 16;
			int[] widths = new int[256];
			// A glyph's width is its rightmost column with any opaque pixel --
			// the same rule vanilla's bitmap font provider uses.
			for (int c = 0; c < 256; c++) {
				int cx = (c % 16) * cell;
				int cy = (c / 16) * cell;
				int w = 0;
				for (int x = cell - 1; x >= 0 && w == 0; x--) {
					for (int y = 0; y < cell; y++) {
						if ((image.getPixelRGBA(cx + x, cy + y) >>> 24) != 0) {
							w = x + 1;
							break;
						}
					}
				}
				widths[c] = w;
			}
			bootFontTexW = image.getWidth();
			bootFontTexH = image.getHeight();
			bootFontCell = cell;
			bootFontWidth = widths;
			ResourceLocation id = new ResourceLocation("originclient", "origin_boot_font");
			DynamicTexture texture = new DynamicTexture(image);
			texture.setFilter(false, false); // nearest: pixel letters stay crisp at 4x
			Minecraft.getInstance().getTextureManager().register(id, texture);
			bootFontId = id;
		} catch (Exception e) {
			bootFontId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin boot font (vanilla ascii.png) failed to load; loading screen draws no text", e);
		}
		return bootFontId != null;
	}

	/** Draws ASCII text from the boot font, horizontally centered on x=0, top at y, in the given ARGB color. */
	private static void drawBootText(Gfx guiGraphics, String text, int y, int argb) {
		// Vanilla advances width + 1 per glyph, scaled by 8/cell for HD sheets.
		float unit = 8f / bootFontCell;
		int total = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			total += ch < 256 ? bootFontWidth[ch] + 1 : 0;
		}
		total -= 1;
		RenderSystem.enableBlend();
		shade(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale(unit, unit, 1f);
		int x = -total / 2;
		int top = Math.round(y / unit);
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch >= 256) {
				continue;
			}
			int w = bootFontWidth[ch];
			if (w > 0) {
				guiGraphics.blit(bootFontId, x, top, (float) ((ch % 16) * bootFontCell), (float) ((ch / 16) * bootFontCell),
						w, bootFontCell, bootFontTexW, bootFontTexH);
			}
			x += w + 1;
		}
		pose.popPose();
		shade(1f, 1f, 1f, 1f);
	}

	/**
	 * Boot-font text (Minecraft's own ascii.png glyphs, see BOOT FONT) at GUI
	 * (x, top), {@code scale}x, {@code tracking} extra font px between letters,
	 * with vanilla's drop shadow. {@code anchor}: 0 = x is the left edge, 0.5 =
	 * centre, 1 = right edge. Draws nothing if the sheet couldn't be read.
	 */
	static void bootText(Gfx g, String text, double x, double top, float scale, int tracking,
								 int argb, float anchor) {
		if (text == null || text.isEmpty() || !ensureBootFont()) {
			return;
		}
		// Widths in font px: a glyph advances (sheet width + 1) * unit, as drawBootText does.
		float unit = 8f / bootFontCell;
		float total = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			total += ch < 256 ? (bootFontWidth[ch] + 1) * unit + tracking : 0;
		}
		total -= unit + tracking;
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(Math.round(x - total * scale * anchor), Math.round(top), 0);
		pose.scale(scale, scale, 1f);
		// Vanilla's shadow: the same text one font px down-right at a quarter brightness.
		int shadow = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
		bootGlyphs(g, text, 1, 1, tracking, unit, shadow);
		bootGlyphs(g, text, 0, 0, tracking, unit, argb);
		pose.popPose();
	}

	private static void bootGlyphs(Gfx g, String text, int ox, int oy, int tracking, float unit, int argb) {
		RenderSystem.enableBlend();
		shade(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(ox, oy, 0);
		pose.scale(unit, unit, 1f);
		float x = 0;   // sheet px
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch >= 256) {
				continue;
			}
			int w = bootFontWidth[ch];
			if (w > 0) {
				g.blit(bootFontId, Math.round(x), 0, (float) ((ch % 16) * bootFontCell), (float) ((ch / 16) * bootFontCell),
						w, bootFontCell, bootFontTexW, bootFontTexH);
			}
			x += w + 1 + tracking / unit;
		}
		pose.popPose();
		shade(1f, 1f, 1f, 1f);
	}

	// ---- Loading ----

	// Volatile fast-path so the common (already-loaded) case is a plain field
	// read with no monitor -- ensureLoaded() runs several times per frame.
	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		ensureLoaded0();
	}

	private static synchronized void ensureLoaded0() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Minecraft mc = Minecraft.getInstance();
			grainId = registerTexture(mc, "origin_grain", "/assets/originclient/textures/ui/grain.png");
		} catch (Exception e) {
			grainId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin grain texture failed to load; skipping grain", e);
		}

		// Vignette loads independently of the grain: a failure just skips the
		// edge darkening, it doesn't disable the backdrop.
		try {
			Minecraft mc = Minecraft.getInstance();
			vignetteId = registerTexture(mc, "origin_vignette", "/assets/originclient/textures/ui/vignette.png");
		} catch (Exception e) {
			vignetteId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin vignette failed to load; skipping edge vignette", e);
		}

		// One typography voice: the title and loading mark use Minecraft's native
		// font path, so the baked wordmark is never drawn (wordmarkId stays null and
		// every wordmark draw takes its fallback). It used to be decoded and uploaded
		// here anyway, on the render thread, and then thrown away: no longer loaded.

		// Cursor-follow glow texture. Optional; skipped entirely on failure.
		try {
			Minecraft mc = Minecraft.getInstance();
			radialGlowId = registerTexture(mc, "origin_radial_glow", "/assets/originclient/textures/ui/radial_glow.png");
		} catch (Exception e) {
			radialGlowId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin radial glow failed to load; skipping cursor glow", e);
		}
	}

	private static ResourceLocation registerTexture(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		ResourceLocation id = new ResourceLocation("originclient", name);
		DynamicTexture texture = new DynamicTexture(image);
		texture.setFilter(true, false); // GL_LINEAR, no mipmap
		mc.getTextureManager().register(id, texture);
		return id;
	}

	private static InputStream open(String classpathResource) throws Exception {
		InputStream in = OriginScreenRenderer.class.getResourceAsStream(classpathResource);
		if (in == null) {
			throw new java.io.FileNotFoundException("Missing Origin asset: " + classpathResource);
		}
		return in;
	}
}
