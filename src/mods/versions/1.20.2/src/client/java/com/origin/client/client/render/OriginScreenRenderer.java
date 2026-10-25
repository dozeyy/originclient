package com.origin.client.client.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;

// Shared Origin screen rendering, used by the loading screen
// (LoadingOverlayMixin), the main menu (TitleScreenMixin) and every out-of-world
// menu (ScreenBackgroundMixin). The two surfaces deliberately differ:
//
//  - LOADING: charcoal + the pre-rendered orbital rings + grain + vignette +
//    "ORIGIN" and the bar, matching the original mock
//    (tools/loading-screen/wordmark_preview.png) and, frame for frame, Origin
//    Launcher's own loading scene (LoadingScene.cs), so the launcher can hand
//    over without a visible change.
//  - MENUS ("living rings", launch experience v2, Will 2026-10-18): the SAME
//    rings, no panorama. They slow, drift and lean towards the mouse
//    (RingField), and on the title the logo forms out of them.
//  - WORLD LOADING: the menu's rings swing back into the loading orbit; once the
//    world is ready it simply appears, like vanilla (Will 2026-10-18: no zoom).
// The panorama is NOT available during the startup load anyway (the cubemap's
// textures aren't loaded yet); the rings are, which keeps the brand mark on the
//    one screen that has nothing else on it.
//
// Textures load via the classloader (not the resource manager), so this is
// safe during the earliest loading overlay while resources are still loading,
// and degrades gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {
	private static final Gson GSON = new Gson();
	private static final int TEX = 768;

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
	private static boolean ringsFailed = false;
	private static final List<Ring> rings = new ArrayList<>();
	// The rings' rotation inputs as arrays, for RingField (built with `rings`).
	private static double[] ringAngle0 = new double[0], ringPeriod = new double[0];
	private static boolean[] ringReverse = new boolean[0];
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
	// Ellipse height:width of the ring textures (generate_textures HEIGHT_RATIO),
	// needed to place a body on a ring's path.
	private static final double RING_HEIGHT_RATIO = 0.37;

	// Cursor-follow glow (the website's two-layer spotlight): one radial
	// gradient texture drawn twice -- a tight core that snaps to the cursor
	// and a soft halo that trails it with lerped physics, both blooming when
	// hovering a clickable. State is static: one cursor, one glow.
	private static ResourceLocation radialGlowId;
	private static final int RADIAL_TEX = 512;

	private record Ring(ResourceLocation texture, double widthFrac, float opacity,
						double angle0, double periodSeconds, boolean reverse) {
	}

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
	 * Startup resource-load screen: near-black with a soft centre lift, the real
	 * Origin logo (still), the "ORIGIN" wordmark beneath it, a thin bar and the
	 * percent (renderLoading0).
	 */
	public static void renderLoading(GuiGraphics guiGraphics, float progress) {
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
	public static boolean renderLoadingFade(GuiGraphics guiGraphics, float alpha, boolean toTitle) {
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
			// dissolves over it. The title underneath already drives the ring field.
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

	private static void renderLoading0(GuiGraphics guiGraphics, float progress, boolean drive) {
		ensureLoaded();
		if (drive) {
			field(RingField.Mood.LOADING);
		}
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		double scene = RingField.sceneMs();
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
		// Clean splash (Will's approved mockup, 2026-10-19): solid near-black with a
		// soft centre lift, the REAL Origin logo (still), the ORIGIN wordmark, a
		// thin bar and the real percent.
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		guiGraphics.fill(0, 0, w, h, fade(OriginTheme.CLEAN_BG));
		drawCenterLift(guiGraphics, w, h, 0.05f);
		double size = clampD(h * 0.11, 24, 44);
		double markCy = h * 0.44;
		drawLogo(guiGraphics, w / 2.0, markCy, size, layerAlpha);
		double wordBase = markCy + size / 2.0 + 19;
		drawWordmarkText(guiGraphics, w / 2.0, wordBase, fade(0xFFFFFFFF));
		double barW = Math.min(160, w * 0.5);
		double barTop = wordBase + 9;
		drawThinBar(guiGraphics, w / 2.0, barTop, barW, (float) loadFill, false);
		bootText(guiGraphics, percentLabel(loadFill), w / 2.0 + barW / 2.0, barTop + 5, 1f, 0, fade(OriginTheme.CLEAN_TEXT_FAINT), 1f);
	}

	// The title's first-use costs (textures, driver shader builds, glyphs) used to
	// be guessed at here, one hand-picked warm-up draw per loading frame. They are
	// now paid by the real title screen itself, rendered for real under the
	// finished loading picture during the settle hold (LoadingOverlayRevealMixin),
	// so nothing has to be guessed and nothing is missed.

	private static net.minecraft.server.packs.resources.ReloadInstance currentReload() {
		return Minecraft.getInstance().getOverlay() instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor overlay
				? overlay.originclient$reload() : null;
	}

	// The splash's "NN%" for a fill, rebuilt only when the whole percent changes
	// (no string built per frame while it holds still).
	private static int pctShown = -1;
	private static String pctLabel = "0%";

	private static String percentLabel(double fill) {
		int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, fill)) * 100);
		if (pct != pctShown) {
			pctShown = pct;
			pctLabel = pct + "%";
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
	public static boolean renderLoadingHold(GuiGraphics guiGraphics) {
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
			// The screen underneath drives the ring field: don't pull it back.
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
	 * (LevelLoadingScreen, ReceivingLevelScreen, ProgressScreen): the same
	 * charcoal + rings + grain background as the menus, the screen's title in
	 * the default font, and a smooth indeterminate bar. Drawn as a HEAD-cancel
	 * takeover, so it replaces vanilla's chunk map / dirt / progress clutter
	 * with one clean Origin scene. Indeterminate (not tied to a real percent):
	 * reading each screen's internal progress field would need an unverifiable
	 * @Shadow, and a calm sweeping bar reads as premium regardless.
	 */
	public static boolean renderLoadingScene(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title) {
		return renderLoadingScene(guiGraphics, title, -1f);
	}

	/**
	 * The world-loading scene with a real fraction done ({@code progress} 0..1,
	 * the chunk count LevelLoadingScreen reports), or -1 when the screen has no
	 * real measure — then the bar sweeps. Never a made-up percentage.
	 */
	public static boolean renderLoadingScene(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title, float progress) {
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

	private static void renderLoadingScene0(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title, float progress) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		// Clean loading scene (Will's approved mockup, 2026-10-19): this version's
		// own panorama, blurred and dimmed, the real logo, the world's name, a thin
		// bar and vanilla's own stage line. Solid background if the panorama can't draw.
		if (!backdrop(mc.screen, guiGraphics, mc.getDeltaFrameTime(), true, OriginTheme.CLEAN_DIM_LOADING)) {
			guiGraphics.fill(0, 0, w, h, OriginTheme.CLEAN_BG);
		}

		// The fill glides to each new count and only moves forward within one world
		// load; it sweeps until the load's first measured stage (LoadBar.scene).
		double now = RingField.sceneMs();
		sceneFill = SCENE_BAR.scene(com.origin.client.client.hud.FrameStats.frames(), progress, now, FILL_TAU);
		if (com.origin.client.client.OriginFrameLog.ENABLED) {   // off: no title string built every frame
			com.origin.client.client.OriginFrameLog.sceneBar(title == null ? "" : title.getString(), progress, sceneFill);
		}
		// World loading: the world's name big, vanilla's own "Preparing spawn area:
		// N%" line small. Never an invented stage name. The strings are rebuilt only
		// when the title, the world or the whole percent changes (sceneLabels).
		String levelName = null;
		if (progress >= 0f) {
			try {
				if (mc.getSingleplayerServer() != null) {
					levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
				}
			} catch (Throwable ignored) {
				levelName = null;
			}
		}
		int pct = progress >= 0f ? Math.round(Math.max(0f, Math.min(1f, progress)) * 100f) : -1;
		String lang = mc.getLanguageManager().getSelected();   // a stored field: no allocation
		if (title != labelTitle || levelName != labelLevel || pct != labelPct || !java.util.Objects.equals(lang, labelLang)) {
			labelTitle = title;
			labelLevel = levelName;
			labelPct = pct;
			labelLang = lang;
			sceneLabels(title, levelName, pct);
		}
		String big = labelBig, small = labelSmall;
		drawSceneText(guiGraphics, w, h, big, small, sceneFill);
	}

	// The loading scene's labels and what they were built from (compared by
	// reference: the screen keeps one title Component, the server one level name;
	// the language code too, so a language change rebuilds the translated lines).
	private static net.minecraft.network.chat.Component labelTitle;
	private static String labelLevel, labelLang;
	private static int labelPct = Integer.MIN_VALUE;
	private static String labelBig = "", labelSmall = "";

	/** Builds the scene's big and small lines (pct < 0: no world load, title only). */
	private static void sceneLabels(net.minecraft.network.chat.Component title, String levelName, int pct) {
		String big = title == null ? "" : title.getString().trim();
		String small = "";
		if (pct >= 0) {
			if (levelName != null && !levelName.isBlank()) {
				big = levelName;
			} else if (big.isEmpty() && net.minecraft.client.resources.language.I18n.exists("menu.loadingLevel")) {
				big = net.minecraft.client.resources.language.I18n.get("menu.loadingLevel");   // vanilla "Loading world"
			}
			small = net.minecraft.client.resources.language.I18n.get("menu.preparingSpawn", pct);
		}
		labelBig = big;
		labelSmall = small;
	}

	/** The loading scene's centred logo, title, bar and stage line (fill < 0: sweeping). */
	private static void drawSceneText(GuiGraphics guiGraphics, int w, int h, String big, String small, double fill) {
		double cy = h * 0.42;
		double logo = 22;
		drawLogo(guiGraphics, w / 2.0, cy, logo, 1f);
		double titleMid = cy + logo / 2.0 + 13;
		drawCenteredLabel(guiGraphics, big, w / 2.0, titleMid, OriginTheme.CLEAN_TEXT);
		double barW = Math.min(160, w * 0.5);
		double barTop = titleMid + 10;
		drawThinBar(guiGraphics, w / 2.0, barTop, barW, (float) fill, fill < 0);
		if (!small.isEmpty()) {
			drawCenteredLabel(guiGraphics, small, w / 2.0, barTop + 10, OriginTheme.CLEAN_TEXT_DIM);
		}
	}

	/**
	 * Connecting to a server (ConnectScreen keeps its vanilla render: the status
	 * line and Cancel stay, the backdrop comes from ScreenBackgroundMixin). This
	 * adds the logo above the status line and the thin sweeping bar under it.
	 */
	public static void renderConnectingBar(GuiGraphics guiGraphics) {
		// TAIL-additive over the vanilla connect screen; skip when broken.
		if (broken) {
			return;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			// Vanilla draws the status line with its top at height/2 - 50.
			double statusMid = h / 2.0 - 50 + 3.5;
			drawLogo(guiGraphics, w / 2.0, statusMid - 26, 22, 1f);
			drawThinBar(guiGraphics, w / 2.0, statusMid + 11, Math.min(160, w * 0.5), 0f, true);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** One label in Minecraft's own font (with its shadow), centred on (cx, midY). */
	private static void drawCenteredLabel(GuiGraphics g, String s, double cx, double midY, int argb) {
		if (s == null || s.isEmpty()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		g.drawString(font, s, (int) Math.round(cx - font.width(s) / 2.0), (int) Math.round(midY - 4), argb, true);
	}

	/**
	 * The thin loading bar (160 x 1.5 GUI px in the mockup): a faint track and a
	 * white fill, both at physical-pixel precision so a 1.5px bar is a real
	 * 3px (scale 2) line and the fill end glides instead of stepping.
	 * {@code sweep}: an indeterminate segment ping-pongs along the track.
	 */
	private static void drawThinBar(GuiGraphics g, double cx, double top, double barW, float progress, boolean sweep) {
		double x0 = Math.round(cx - barW / 2.0);
		double bh = 1.5;
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		com.origin.client.client.gui.OriginSurface.fillPhysical(g, x0, top, x0 + barW, top + bh, fade(OriginTheme.CLEAN_BAR_TRACK));
		if (sweep) {
			double segW = barW * 0.28;
			double t = (RingField.sceneMs() % 1800.0) / 1800.0;
			double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI);
			double sx = x0 + eased * (barW - segW);
			com.origin.client.client.gui.OriginSurface.fillPhysical(g, sx, top, sx + segW, top + bh, fade(OriginTheme.CLEAN_BAR_FILL));
		} else {
			double fw = barW * Math.max(0f, Math.min(1f, progress));
			if (fw > 0.01) {
				com.origin.client.client.gui.OriginSurface.fillPhysical(g, x0, top, x0 + fw, top + bh, fade(OriginTheme.CLEAN_BAR_FILL));
			}
		}
	}

	/** A colour with layerAlpha (the loading -> title dissolve) multiplied into its alpha. */
	private static int fade(int argb) {
		return fadeA(argb, layerAlpha);
	}

	private static int fadeA(int argb, float k) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, k)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static double clampD(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ---- clean backdrop: this version's own panorama ----

	// Separate latch from `broken`: a failed panorama/blur call only hands the
	// backdrop back to vanilla.
	private static volatile boolean backdropBroken = false;

	// 1.20.2 era: Screen has no renderPanorama / renderBlurredBackground (both arrived
	// in 1.20.5) and vanilla has no menu blur. So the backdrop is built from the
	// same parts the 1.21.1 one uses, all vanilla's own:
	//  - the panorama: vanilla's PanoramaRenderer over THIS version's cube map
	//    (TitleScreen.CUBE_MAP), plus vanilla's panorama_overlay vignette texture,
	//    exactly what TitleScreen.render draws. ONE shared renderer, also used by
	//    the title (TitleScreenMixin redirects its panorama call here), so the slow
	//    spin carries on seamlessly from the title into every menu like 1.21.1's
	//    static Screen.PANORAMA does;
	//  - the blur: MenuBlur's chain (vanilla's "blur" program, 1.21.1's three-pass
	//    radius ladder) at MENU_BLUR_RADIUS = 1.21.1's default Menu Background
	//    Blurriness (this version has no such option).
	private static final int MENU_BLUR_RADIUS = 5;
	private static final ResourceLocation PANORAMA_OVERLAY =
			new ResourceLocation("textures/gui/title/background/panorama_overlay.png");
	private static net.minecraft.client.renderer.PanoramaRenderer panoramaRenderer;

	/** The one panorama renderer every Origin backdrop and the title share (lazy). */
	public static net.minecraft.client.renderer.PanoramaRenderer panorama() {
		if (panoramaRenderer == null) {
			panoramaRenderer = new net.minecraft.client.renderer.PanoramaRenderer(net.minecraft.client.gui.screens.TitleScreen.CUBE_MAP);
		}
		return panoramaRenderer;
	}

	/** False once the backdrop has failed: callers keep vanilla's backdrop. */
	public static boolean menuBackdropOk() {
		return !backdropBroken;
	}

	/**
	 * Out-of-world menus: THIS version's own Mojang panorama, blurred, dimmed and
	 * vignetted. Same cost as vanilla's title backdrop plus one blur chain.
	 * False: the caller keeps vanilla's.
	 */
	public static boolean renderMenuBackdrop(net.minecraft.client.gui.screens.Screen screen, GuiGraphics g, float partialTick) {
		return backdrop(screen, g, partialTick, true, OriginTheme.CLEAN_DIM_MENU);
	}

	/** Title: vanilla already drew the (fading-in) panorama; blur + a lighter dim over it. */
	public static boolean renderTitleBackdropOver(net.minecraft.client.gui.screens.Screen screen, GuiGraphics g, float partialTick) {
		return backdrop(screen, g, partialTick, false, OriginTheme.CLEAN_DIM_TITLE);
	}

	private static boolean backdrop(net.minecraft.client.gui.screens.Screen screen, GuiGraphics g, float partialTick,
									boolean panorama, int dim) {
		if (backdropBroken || screen == null) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			if (panorama) {
				// partialTick here is the frame delta vanilla's title hands its own
				// panorama (Screen.render's last argument), so the spin speed matches.
				panorama().render(partialTick, 1f);
				RenderSystem.enableBlend();
				g.blit(PANORAMA_OVERLAY, 0, 0, w, h, 0f, 0f, 16, 128, 16, 128);
			}
			g.flush();
			com.origin.client.client.mods.MenuBlur.blurAt(MENU_BLUR_RADIUS, partialTick);
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

	// ---- the real Origin logo, crisp at any size ----

	// origin_logo.png (256 px, white three-ring mark) downsampled ONCE per
	// physical size with a 4x4 box filter and blitted 1:1, so its thin strokes
	// stay smooth instead of shimmering under plain minification.
	private static final java.util.Map<Integer, ResourceLocation> LOGO_SIZES = new java.util.HashMap<>();
	private static NativeImage logoSrc;
	private static boolean logoFailed;

	/** Draws the logo centred on (cx, cy), {@code size} GUI px square, at alpha. */
	private static void drawLogo(GuiGraphics g, double cx, double cy, double size, float alpha) {
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
	private static void drawWordmarkText(GuiGraphics g, double cx, double baseline, int argb) {
		bootText(g, "ORIGIN", cx, baseline - 14, 2f, 3, argb, 0.5f);
	}

	/** A barely-there radial lift behind the centre (the splash's "soft centre lift"). */
	private static void drawCenterLift(GuiGraphics g, int w, int h, float alpha) {
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
	 * fill() with a horizontal span placed to a fraction of a real pixel: the
	 * whole pixels in one fill, each partly covered edge pixel at its share of
	 * the alpha. A moving bar end then slides smoothly instead of stepping a
	 * whole GUI unit (guiScale screen pixels) at a time. Vertical edges snap to
	 * real pixels. Drawn in real pixels, the same way drawGrain is.
	 */
	private static void fillSmooth(GuiGraphics guiGraphics, double x0, double y0, double x1, double y1, int argb) {
		if (!(x1 > x0) || !(y1 > y0)) {
			return;
		}
		double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		double px0 = x0 * gs, px1 = x1 * gs;
		int py0 = (int) Math.round(y0 * gs), py1 = (int) Math.round(y1 * gs);
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		int i0 = (int) Math.ceil(px0), i1 = (int) Math.floor(px1);
		if (i1 < i0) {
			fillEdge(guiGraphics, i1, py0, py1, px1 - px0, argb);  // the whole span is inside one pixel
		} else {
			if (i1 > i0) {
				guiGraphics.fill(i0, py0, i1, py1, argb);
			}
			fillEdge(guiGraphics, i0 - 1, py0, py1, i0 - px0, argb);
			fillEdge(guiGraphics, i1, py0, py1, px1 - i1, argb);
		}
		pose.popPose();
	}

	private static void fillEdge(GuiGraphics guiGraphics, int x, int y0, int y1, double cover, int argb) {
		int a = (int) Math.round((argb >>> 24) * Math.min(1.0, cover));
		if (a > 0) {
			guiGraphics.fill(x, y0, x + 1, y1, (a << 24) | (argb & 0xFFFFFF));
		}
	}

	/**
	 * Menu background, "living rings" (Will 2026-10-18): the loading scene's own
	 * rings, never a panorama, so the menus read as the same place the game
	 * loaded into. RingField slows them to a third, lets them drift and leans
	 * them towards the mouse. Returns true only if the Origin backdrop actually
	 * drew; callers that cancel vanilla's own backdrop must key off this.
	 *
	 * Cheap on purpose: one fill, four ring blits, the grain tiles, one glow and
	 * the vignette — no cubemap, no blur pass.
	 */
	public static boolean renderTitleBackground(GuiGraphics guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			field(RingField.Mood.MENU);
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			guiGraphics.fill(0, 0, w, h, OriginTheme.BG);
			drawMenuGlow(guiGraphics, w, h);
			if (!ringsFailed) {
				drawRings(guiGraphics, w, h);
				drawGrain(guiGraphics, w, h);
			}
			drawVignette(guiGraphics, w, h);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** One restrained jade highlight, top right, under the rings (kept from the Ion Jade grade). */
	private static void drawMenuGlow(GuiGraphics guiGraphics, int w, int h) {
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
	public static void renderTitleCursorGlow(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
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
	public static boolean renderTitleWordmark(GuiGraphics guiGraphics) {
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
	public static boolean renderWordmarkAt(GuiGraphics guiGraphics, double cx, double cy, double targetInkHeight, float alpha) {
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
	private static void drawBakedInk(GuiGraphics guiGraphics, ResourceLocation tex, int texW, int texH,
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
	 * back to the Origin ring mark, and if the whole chip throws it flips the
	 * renderer to vanilla like any Origin draw.
	 */
	public static void renderTitleAccountChip(GuiGraphics guiGraphics) {
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
			com.origin.client.client.gui.OriginButtonRenderer.vanillaButtonBox(guiGraphics, x, y, chipW, chipH, true, false);
			int hx = x + pad, hy = y + (chipH - head) / 2;
			boolean drewHead = false;
			try {
				// Minecraft's resolved profile carries the signed `textures` property.
				// Rebuilding a GameProfile from only UUID + name discards that property,
				// so SkinManager can return only the generated default head forever.
				// 1.20.2-1.20.4: SkinManager hands back the PlayerSkin directly
				// (getInsecureSkin, the call this module's chip already used) -- the
				// default skin until the lookup completes, then the real one.
				com.mojang.authlib.GameProfile profile = mc.getGameProfile();
				net.minecraft.client.resources.PlayerSkin skin = mc.getSkinManager().getInsecureSkin(profile);
				net.minecraft.client.gui.components.PlayerFaceRenderer.draw(guiGraphics, skin, hx, hy, head);
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

	// ---- Primitives ----

	/** Advance the shared rings (RingField) for this frame's scene. */
	private static void field(RingField.Mood mood) {
		double mx = 0.5, my = 0.5;
		if (mood == RingField.Mood.MENU) {
			Minecraft mc = Minecraft.getInstance();
			mx = mc.mouseHandler.xpos() / Math.max(1, mc.getWindow().getScreenWidth());
			my = mc.mouseHandler.ypos() / Math.max(1, mc.getWindow().getScreenHeight());
		}
		RingField.update(mood, ringAngle0, ringPeriod, ringReverse, mx, my);
	}

	private static double ringAngle(int i) {
		Ring ring = rings.get(i);
		return RingField.angle(i, ring.angle0(), ring.periodSeconds(), ring.reverse());
	}

	/** Every ring where RingField has it. */
	private static void drawRings(GuiGraphics guiGraphics, int w, int h) {
		for (int i = 0; i < rings.size(); i++) {
			Ring ring = rings.get(i);
			drawRingAt(guiGraphics, ring, w / 2.0 + RingField.offsetX(i, w), h / 2.0 + RingField.offsetY(i, w),
					ringAngle(i), ring.widthFrac() * w * 0.99, RING_HEIGHT_RATIO, ring.opacity());
		}
		shade(1f, 1f, 1f, 1f);
	}

	/** One ring texture as an ellipse of on-screen width {@code major} (GUI units). */
	private static void drawRingAt(GuiGraphics guiGraphics, Ring ring, double cx, double cy, double angle,
								   double major, double aspect, float alpha) {
		if (alpha <= 0.002f) {
			return;
		}
		// The ellipse fills 0.9 of its square texture.
		float scale = (float) (major / (0.9 * TEX));
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(cx, cy, 0);
		// Wrapped to [0,360) in double before the float cast (RingField does):
		// a float holding a large absolute angle would step visibly.
		pose.mulPose(Axis.ZP.rotationDegrees((float) (angle % 360.0)));
		pose.scale(scale, (float) (scale * aspect / RING_HEIGHT_RATIO), 1f);
		pose.translate(-TEX / 2f, -TEX / 2f, 0);
		RenderSystem.enableBlend();
		shade(1f, 1f, 1f, alpha);
		guiGraphics.blit(ring.texture(), 0, 0, 0, 0, TEX, TEX, TEX, TEX);
		pose.popPose();
		shade(1f, 1f, 1f, 1f);
	}

	private static void drawGrain(GuiGraphics guiGraphics, int w, int h) {
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
	private static void drawVignette(GuiGraphics guiGraphics, int w, int h) {
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
	private static void drawBootText(GuiGraphics guiGraphics, String text, int y, int argb) {
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
	private static void bootText(GuiGraphics g, String text, double x, double top, float scale, int tracking,
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

	private static void bootGlyphs(GuiGraphics g, String text, int ox, int oy, int tracking, float unit, int argb) {
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

	/** Draws the progress bar centered on cx at barTop, with the given width/height. */
	private static void drawBar(GuiGraphics guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;

		// Track brighter than the hairline stroke: at 8% white on the near-black
		// background the unfilled track was invisible in-game, so only the fill
		// showed and the bar read as the wrong (too small) size. ~16% matches how
		// the mockup's track actually reads on screen.
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF);              // track
		double fillW = barW * Math.max(0f, Math.min(1f, progress));
		if (fillW > 0.05) {
			fillSmooth(guiGraphics, bx - 1, by - 1, bx + fillW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW); // soft glow
			fillSmooth(guiGraphics, bx, by, bx + fillW, by + barH, OriginTheme.ACCENT);  // fill
		}
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
			JsonObject meta;
			try (InputStream in = open("/assets/originclient/textures/ui/rings.json")) {
				meta = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			var arr = meta.getAsJsonArray("rings");
			for (int i = 0; i < arr.size(); i++) {
				JsonObject r = arr.get(i).getAsJsonObject();
				int index = r.get("index").getAsInt();
				ResourceLocation id = registerTexture(mc, "origin_ring_" + index,
						"/assets/originclient/textures/ui/ring-" + index + ".png");
				rings.add(new Ring(id,
						r.get("widthFrac").getAsDouble(),
						r.get("opacity").getAsFloat(),
						r.get("angle0").getAsDouble(),
						r.get("periodSeconds").getAsDouble(),
						r.get("reverse").getAsBoolean()));
			}
			int n = rings.size();
			double[] a0 = new double[n], period = new double[n];
			boolean[] reverse = new boolean[n];
			for (int i = 0; i < n; i++) {
				a0[i] = rings.get(i).angle0();
				period[i] = rings.get(i).periodSeconds();
				reverse[i] = rings.get(i).reverse();
			}
			ringAngle0 = a0;
			ringPeriod = period;
			ringReverse = reverse;
			grainId = registerTexture(mc, "origin_grain", "/assets/originclient/textures/ui/grain.png");
		} catch (Exception e) {
			ringsFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin screen ring/grain textures failed to load; using plain background", e);
		}

		// Vignette loads independently of the rings: a failure just skips the
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
