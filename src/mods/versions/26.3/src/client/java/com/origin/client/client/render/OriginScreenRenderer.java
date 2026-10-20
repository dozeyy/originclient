package com.origin.client.client.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix3x2fStack;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;

// Shared Origin screen rendering, used by the loading screen
// (LoadingOverlayMixin), the main menu (TitleScreenMixin), the world-loading
// screens and every out-of-world menu (ScreenBackgroundMixin). The clean look
// (Will's approved mockup, 2026-10-19; 1.21.1 is the design truth):
//
//  - STARTUP SPLASH: solid near-black with a soft centre lift, the real Origin
//    logo (still), "ORIGIN" in Minecraft's own letters, a thin bar and the real
//    percent. Drawn before fonts are baked, so its text is the boot font.
//  - MENUS: THIS version's own vanilla panorama, blurred by vanilla's blur,
//    dimmed and vignetted. Minecraft's own widgets on top, untouched.
//  - WORLD LOADING / CONNECTING: the same backdrop, the logo, the world's name,
//    a thin bar and vanilla's own stage line.
//
// 26.x era (retained GUI): every textured draw is a RenderPipelines.GUI_TEXTURED
// blit carrying its tint on the ARGB argument (no setShaderColor), the pose is a
// Matrix3x2fStack, and "draw over everything so far" is nextStratum() instead
// of a depth clear. A translucent layer (the dissolve) multiplies layerAlpha
// into each colour through fade(int) rather than a global shader colour.
// The blur is per FRAME here (GuiRenderState.blurBeforeThisStratum throws on a
// second call), and Screen.extractBackground runs in its own stratum BEFORE
// extractRenderState, so the loading scenes draw their backdrop from the
// background pass (renderLoadingBackdrop) and only their text in the render pass.
//
// Textures load via the classloader (not the resource manager), so this is
// safe during the earliest loading overlay while resources are still loading,
// and degrades gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {
	private static final Gson GSON = new Gson();
	private static final int TEX = 768;
	// The menu's one jade highlight under the rings: the radial glow tinted
	// 0x4BC8AE at 7% alpha (0x12), 1.21.1's shade(0x4B, 0xC8, 0xAE, 0.07) as a
	// per-blit ARGB tint (this era has no RenderSystem.setShaderColor).
	private static final int MENU_GLOW_TINT = 0x124BC8AE;

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
	private static Identifier grainId;

	// Edge vignette (transparent core -> soft black corners). Blitted full-screen
	// to darken the frame edges so the centre reads as the focal point -- depth
	// from lightness only, no hue. Null -> skip (no crash).
	private static Identifier vignetteId;
	private static final int VIGNETTE_TEX = 1024;

	// Baked "ORIGIN" wordmark (Michroma). Never loaded any more (one typography
	// voice: Minecraft's font), so renderWordmarkAt reports false and its caller
	// takes its vanilla-font fallback.
	private static Identifier wordmarkId;
	private static int wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH;

	// The startup bar's shown fill (-1 = none yet): it glides to the reload's real
	// progress instead of stepping with it, and never moves backward (LoadBar).
	private static double loadFill = -1;
	private static final LoadBar LOAD_BAR = new LoadBar();
	/** The startup and world-loading bars' glide time constant (ms). */
	private static final double FILL_TAU = 140.0;

	// Ellipse height:width of the ring textures (generate_textures HEIGHT_RATIO).
	private static final double RING_HEIGHT_RATIO = 0.37;

	// Soft radial gradient: the splash's centre lift and the Origin-own screens'
	// jade highlight.
	private static Identifier radialGlowId;
	private static final int RADIAL_TEX = 512;

	private record Ring(Identifier texture, double widthFrac, float opacity,
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
	public static void renderLoading(GuiGraphicsExtractor guiGraphics, float progress) {
		// The game is on screen now (Origin's scene, or vanilla's if ours
		// broke): Origin Launcher can lower its progress screen.
		com.origin.client.client.OriginHandoff.loading();
		// Drawn at TAIL over vanilla's overlay, so skipping when broken simply
		// reveals the vanilla loading screen -- no cancel to unwind.
		if (broken) {
			return;
		}
		// Vanilla mode (Main Menu Style off): Mojang's loading screen stays, but the
		// handoff above and the per-frame texture uploads still run.
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
		if (Minecraft.getInstance().gui.overlay() instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor overlay) {
			net.minecraft.server.packs.resources.ReloadInstance reload = overlay.originclient$reload();
			if (reload != null) {
				return reload.isDone() ? 1f : reload.getActualProgress();
			}
		}
		return fallback;
	}

	/**
	 * The startup loading screen at {@code alpha} over whatever is already drawn
	 * this frame -- the loading -> title dissolve (LoadingOverlayRevealMixin).
	 * Loading is done by then, so the bar shows full. The title shows the real
	 * Origin logo, still, so the loading layer simply dissolves over it
	 * ({@code toTitle} is kept for the caller's signature). False (nothing to
	 * cancel) when Origin rendering is off or this draw fails.
	 */
	public static boolean renderLoadingFade(GuiGraphicsExtractor guiGraphics, float alpha, boolean toTitle) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = Math.max(0f, Math.min(1f, alpha));
			// Everything after this sits above the title recorded so far this frame,
			// its text and icons included (1.21.1 clears the depth buffer here; the
			// retained GUI orders by stratum instead).
			guiGraphics.nextStratum();
			renderLoading0(guiGraphics, 1f, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
		}
	}

	// The loading layer's opacity: 1 except during the loading -> title dissolve.
	// Every colour the loading scene draws goes through fade(), so the whole
	// scene turns translucent without each helper knowing.
	private static float layerAlpha = 1f;

	/** A colour with layerAlpha (the loading -> title dissolve) multiplied into its alpha. */
	private static int fade(int argb) {
		if (layerAlpha >= 1f) {
			return argb;
		}
		int a = Math.round((argb >>> 24) * Math.max(0f, layerAlpha));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static double clampD(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static void renderLoading0(GuiGraphicsExtractor guiGraphics, float progress, boolean drive) {
		ensureLoaded();
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
			// RingField's clock still advances (the bars' glide and sweep run on it).
			field(RingField.Mood.LOADING);
			// Origin's textures were decoded on the reload's workers; upload one
			// loader's worth per frame now, so the title's first frames don't.
			com.origin.client.client.OriginAssets.uploadSlice();
		}
		// Clean splash (Will's approved mockup, 2026-10-19): solid near-black with a
		// soft centre lift, the REAL Origin logo (still), the ORIGIN wordmark, a
		// thin bar and the real percent.
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

	// The title's first-use costs are paid by the real title screen itself,
	// rendered for real under the finished loading picture during the settle hold
	// (LoadingOverlayRevealMixin), so nothing has to be guessed and nothing is missed.

	private static net.minecraft.server.packs.resources.ReloadInstance currentReload() {
		return Minecraft.getInstance().gui.overlay() instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor overlay
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
	 * False (nothing to cancel) when Origin rendering is off or this draw fails.
	 */
	public static boolean renderLoadingHold(GuiGraphicsExtractor guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = 1f;
			// A fresh stratum so the loading picture sits over everything the screen
			// beneath just submitted (this era's stand-in for 1.21.1's depth clear).
			guiGraphics.nextStratum();
			renderLoading0(guiGraphics, 1f, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
		}
	}

	/**
	 * Full-screen loading/progress scene for the world-load screens
	 * (LevelLoadingScreen, ProgressScreen, GenericMessageScreen): the panorama
	 * backdrop, the logo, the screen's title and a thin bar. Drawn as a
	 * HEAD-cancel takeover of the screen's render pass, so it replaces vanilla's
	 * chunk map / progress clutter with one clean Origin scene. Without a real
	 * measure the bar sweeps.
	 */
	public static boolean renderLoadingScene(GuiGraphicsExtractor guiGraphics, net.minecraft.network.chat.Component title) {
		return renderLoadingScene(guiGraphics, title, -1f);
	}

	/**
	 * The world-loading scene with a real fraction done ({@code progress} 0..1,
	 * what LevelLoadingScreen's load tracker reports), or -1 when the screen has
	 * no real measure -- then the bar sweeps. Never a made-up percentage.
	 */
	public static boolean renderLoadingScene(GuiGraphicsExtractor guiGraphics, net.minecraft.network.chat.Component title, float progress) {
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

	// True from the moment the background pass drew a loading scene's backdrop
	// until that scene's render pass consumes it: the backdrop (panorama +
	// blur) is drawn ONCE per frame. Drawing the panorama twice would double its
	// spin speed, and a second blur throws in this era.
	private static boolean loadingBackdropDrawn;

	/**
	 * A loading scene's backdrop, from the screen's background pass
	 * (ScreenBackgroundMixin / LevelLoadingScreenMixin): this version's panorama,
	 * blurred and dimmed, or solid near-black if the panorama can't draw. False
	 * (keep vanilla's) only when Origin rendering is off or this draw fails.
	 */
	public static boolean renderLoadingBackdrop(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, float partialTick) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (!backdrop(screen, g, partialTick, true, OriginTheme.CLEAN_DIM_LOADING)) {
				Minecraft mc = Minecraft.getInstance();
				g.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), OriginTheme.CLEAN_BG);
			}
			loadingBackdropDrawn = true;
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

	private static void renderLoadingScene0(GuiGraphicsExtractor guiGraphics, net.minecraft.network.chat.Component title, float progress) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		// RingField's clock drives the sweep and the glide.
		field(RingField.Mood.LOADING);

		// Clean loading scene (Will's approved mockup, 2026-10-19): this version's
		// own panorama, blurred and dimmed, the real logo, the world's name, a thin
		// bar and vanilla's own stage line. The background pass normally drew the
		// backdrop already this frame; a screen whose background pass Origin
		// doesn't own (GenericMessageScreen draws its whole scene there) draws it here.
		if (loadingBackdropDrawn) {
			loadingBackdropDrawn = false;
		} else if (!backdrop(mc.gui.screen(), guiGraphics, mc.getDeltaTracker().getRealtimeDeltaTicks(), true, OriginTheme.CLEAN_DIM_LOADING)) {
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
		// when the title, the world, the whole percent or the game's language
		// changes (sceneLabels), so a language switch never leaves a stale line.
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
		String lang = mc.options == null ? null : mc.options.languageCode;
		if (title != labelTitle || levelName != labelLevel || pct != labelPct || !Objects.equals(lang, labelLang)) {
			labelTitle = title;
			labelLevel = levelName;
			labelPct = pct;
			labelLang = lang;
			sceneLabels(title, levelName, pct);
		}
		drawSceneText(guiGraphics, w, h, labelBig, labelSmall, sceneFill);
	}

	// The loading scene's labels and what they were built from (compared by
	// reference: the screen keeps one title Component, the server one level name;
	// the language code by value).
	private static net.minecraft.network.chat.Component labelTitle;
	private static String labelLevel;
	private static String labelLang;
	private static int labelPct = Integer.MIN_VALUE;
	private static String labelBig = "", labelSmall = "";

	/** Builds the scene's big and small lines (pct < 0: no world load, title only). */
	private static void sceneLabels(net.minecraft.network.chat.Component title, String levelName, int pct) {
		String big = title == null ? "" : title.getString().trim();
		String small = "";
		if (pct >= 0) {
			if (levelName != null && !levelName.isBlank()) {
				big = levelName;
			} else if (big.isEmpty() && net.minecraft.locale.Language.getInstance().has("menu.loadingLevel")) {   // 26.2+: I18n.exists is gone
				big = net.minecraft.client.resources.language.I18n.get("menu.loadingLevel");   // vanilla "Loading world"
			}
			small = net.minecraft.client.resources.language.I18n.get("menu.preparingSpawn", pct);
		}
		labelBig = big;
		labelSmall = small;
	}

	/** The loading scene's centred logo, title, bar and stage line (fill < 0: sweeping). */
	private static void drawSceneText(GuiGraphicsExtractor guiGraphics, int w, int h, String big, String small, double fill) {
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
	public static void renderConnectingBar(GuiGraphicsExtractor guiGraphics) {
		// TAIL-additive over the vanilla connect screen; skip when broken.
		if (broken) {
			return;
		}
		try {
			ensureLoaded();
			field(RingField.Mood.LOADING);   // the sweep's clock
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
	private static void drawCenteredLabel(GuiGraphicsExtractor g, String s, double cx, double midY, int argb) {
		if (s == null || s.isEmpty()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		g.text(font, s, (int) Math.round(cx - font.width(s) / 2.0), (int) Math.round(midY - 4), argb, true);
	}

	/**
	 * The thin loading bar (160 x 1.5 GUI px in the mockup): a faint track and a
	 * white fill, both at physical-pixel precision so a 1.5px bar is a real
	 * 3px (scale 2) line and the fill end glides instead of stepping.
	 * {@code sweep}: an indeterminate segment ping-pongs along the track.
	 */
	private static void drawThinBar(GuiGraphicsExtractor g, double cx, double top, double barW, float progress, boolean sweep) {
		double x0 = Math.round(cx - barW / 2.0);
		double bh = 1.5;
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

	// ---- clean backdrop: this version's own panorama ----

	// Separate latch from `broken`: a failed panorama/blur call (e.g. the
	// ScreenInvoker mixin didn't apply) only hands the backdrop back to vanilla.
	private static volatile boolean backdropBroken = false;

	/**
	 * Out-of-world menus: THIS version's own Mojang panorama (Screen's PANORAMA,
	 * read from vanilla resources at runtime, so every version module shows its
	 * own update's scene), blurred by vanilla's blur (follows Accessibility > Menu
	 * Background Blurriness), dimmed and vignetted. Same cost as vanilla's own
	 * backdrop (panorama + one blur pass). False: the caller keeps vanilla's.
	 */
	public static boolean renderMenuBackdrop(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, float partialTick) {
		return backdrop(screen, g, partialTick, true, OriginTheme.CLEAN_DIM_MENU);
	}

	/** Title: vanilla already drew the (fading-in) panorama; blur + a lighter dim over it. */
	public static boolean renderTitleBackdropOver(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, float partialTick) {
		return backdrop(screen, g, partialTick, false, OriginTheme.CLEAN_DIM_TITLE);
	}

	private static boolean backdrop(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, float partialTick,
									boolean panorama, int dim) {
		if (backdropBroken || screen == null) {
			return false;
		}
		try {
			ensureLoaded();
			com.origin.client.client.mixin.ScreenInvoker inv = (com.origin.client.client.mixin.ScreenInvoker) screen;
			if (panorama) {
				inv.originclient$extractPanorama(g, partialTick);
			}
			blurOnce(inv, g);
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			g.fill(0, 0, w, h, dim);
			drawVignette(g, w, h);
			return true;
		} catch (Throwable t) {
			backdropBroken = true;
			com.origin.client.OriginClient.LOGGER.error("Origin panorama backdrop failed; vanilla backdrop for this session", t);
			return false;
		}
	}

	/**
	 * Vanilla's blur, at most once per frame: GuiRenderState.blurBeforeThisStratum
	 * throws "Can only blur once per frame" on a second call, so skip when this
	 * frame already blurred (its firstStratumAfterBlur is set; MAX_VALUE = not
	 * yet, read through the two accessors). Without the accessors, the throw is
	 * caught instead: the frame keeps its one blur either way.
	 */
	private static void blurOnce(com.origin.client.client.mixin.ScreenInvoker inv, GuiGraphicsExtractor g) {
		try {
			if ((Object) g instanceof com.origin.client.client.mixin.GuiGraphicsExtractorAccessor ga
					&& (Object) ga.originclient$guiRenderState() instanceof com.origin.client.client.mixin.GuiRenderStateAccessor sa
					&& sa.originclient$firstStratumAfterBlur() != Integer.MAX_VALUE) {
				return;   // already blurred this frame
			}
		} catch (Throwable ignored) {
			// accessors missing: fall through to the guarded call
		}
		try {
			inv.originclient$extractBlurredBackground(g);
		} catch (IllegalStateException alreadyBlurred) {
			// "Can only blur once per frame": the frame is already blurred
		}
	}

	// ---- the real Origin logo, crisp at any size ----

	// origin_logo.png (256 px, white three-ring mark) downsampled ONCE per
	// physical size with a 4x4 box filter and blitted 1:1, so its thin strokes
	// stay smooth instead of shimmering under plain minification.
	private static final java.util.Map<Integer, Identifier> LOGO_SIZES = new java.util.HashMap<>();
	private static NativeImage logoSrc;
	private static boolean logoFailed;

	/** Draws the logo centred on (cx, cy), {@code size} GUI px square, at alpha. */
	private static void drawLogo(GuiGraphicsExtractor g, double cx, double cy, double size, float alpha) {
		int gs = com.origin.client.client.gui.OriginSurface.scale();
		int ps = (int) Math.round(size * gs);
		Identifier id = logoAt(ps);
		if (id == null) {
			OriginUi.logo(g, cx, cy, (int) Math.round(size), alpha);
			return;
		}
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(1f / gs, 1f / gs);
		int px = (int) Math.round(cx * gs - ps / 2.0), py = (int) Math.round(cy * gs - ps / 2.0);
		g.blit(RenderPipelines.GUI_TEXTURED, id, px, py, 0f, 0f, ps, ps, ps, ps, ARGB.white(Math.max(0f, Math.min(1f, alpha))));
		pose.popMatrix();
	}

	private static Identifier logoAt(int ps) {
		if (logoFailed || ps <= 0 || ps > 512) {
			return null;
		}
		Identifier id = LOGO_SIZES.get(ps);
		if (id != null) {
			return id;
		}
		try {
			if (logoSrc == null) {
				try (InputStream in = open("/assets/originclient/textures/ui/origin_logo.png")) {
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
							// getPixel is ARGB in this era (1.21.2+): alpha is the top byte;
							// the mark is grey, so the low byte is its brightness either way.
							int c = logoSrc.getPixel(tx, ty);
							double a = ((c >>> 24) & 0xFF) / 255.0;
							aSum += a;
							cSum += a * ((c & 0xFF) / 255.0);
						}
					}
					double a = aSum / 16.0;
					int v = a > 0 ? (int) Math.round(Math.min(1.0, cSum / aSum) * 255) : 255;
					dst.setPixel(x, y, ((int) Math.round(a * 255) << 24) | (v << 16) | (v << 8) | v);
				}
			}
			// The default DynamicTexture sampler is NEAREST, right for a 1:1 blit.
			String name = "logo_px_" + ps;
			DynamicTexture tex = new DynamicTexture(() -> "originclient:" + name, dst);
			id = Identifier.fromNamespaceAndPath("originclient", name);
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
	private static void drawWordmarkText(GuiGraphicsExtractor g, double cx, double baseline, int argb) {
		bootText(g, "ORIGIN", cx, baseline - 14, 2f, 3, argb, 0.5f);
	}

	/** A barely-there radial lift behind the centre (the splash's "soft centre lift"). */
	private static void drawCenterLift(GuiGraphicsExtractor g, int w, int h, float alpha) {
		if (radialGlowId == null) {
			return;
		}
		int d = (int) Math.round(Math.max(w, h) * 0.9);
		g.blit(RenderPipelines.GUI_TEXTURED, radialGlowId, (w - d) / 2, (int) Math.round(h * 0.44 - d / 2.0),
				0f, 0f, d, d, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, ARGB.white(alpha * layerAlpha));
	}

	/**
	 * Origin's own screens (the mod menu and its panels): the loading scene's
	 * rings on charcoal, with grain, one glow and the vignette. Minecraft's own
	 * menus use renderMenuBackdrop. Returns true only if the backdrop actually
	 * drew; callers that cancel vanilla's own backdrop must key off this.
	 */
	public static boolean renderTitleBackground(GuiGraphicsExtractor guiGraphics) {
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
	private static void drawMenuGlow(GuiGraphicsExtractor guiGraphics, int w, int h) {
		if (radialGlowId == null) {
			return;
		}
		int d = (int) Math.round(w * 1.1);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, radialGlowId,
				(int) Math.round(w * 0.70 - d / 2.0), (int) Math.round(h * 0.15 - d / 2.0),
				0f, 0f, d, d, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, MENU_GLOW_TINT);
	}

	/**
	 * Retired cursor spotlight -- deliberately empty (matches 1.21.1). Kept as a
	 * method: ScreenBackgroundMixin's two mutually exclusive paths both call it.
	 */
	public static void renderTitleCursorGlow(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
	}

	/**
	 * Main menu: the Origin logo and wordmark above the button stack, still
	 * (the loading screen dissolves over it after the first load).
	 */
	public static boolean renderTitleWordmark(GuiGraphicsExtractor guiGraphics) {
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
	public static boolean renderWordmarkAt(GuiGraphicsExtractor guiGraphics, double cx, double cy, double targetInkHeight, float alpha) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (wordmarkId == null || wmInkH <= 0) {
				return false;
			}
			float scale = (float) (targetInkHeight / wmInkH);
			double icx = (wmInkX + wmInkW / 2.0) * scale;
			double icy = (wmInkY + wmInkH / 2.0) * scale;
			Matrix3x2fStack pose = guiGraphics.pose();
			pose.pushMatrix();
			pose.translate((float) (cx - icx), (float) (cy - icy));
			pose.scale(scale, scale);
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, wordmarkId, 0, 0, 0f, 0f, wmTexW, wmTexH, wmTexW, wmTexH, ARGB.white(alpha));
			pose.popMatrix();
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** The title's player chip draws on vanilla's normal button sprite. */
	private static final Identifier CHIP_SPRITE = Identifier.withDefaultNamespace("widget/button");

	// The chip's skin lookup, made once per profile (createLookup builds a new
	// supplier each call; the skin manager caches the texture behind it).
	private static UUID titleSkinProfileId;
	private static Supplier<net.minecraft.world.entity.player.PlayerSkin> titleSkinSupplier;

	/**
	 * Main menu: a small account chip (player head + username) in the top-left,
	 * on a vanilla button. Head via the skin manager (default skin shows first,
	 * the real one pops in async); if the head can't be resolved the chip falls
	 * back to the Origin logo, and if the whole chip throws it flips the
	 * renderer to vanilla like any Origin draw.
	 */
	public static void renderTitleAccountChip(GuiGraphicsExtractor guiGraphics) {
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
			guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, CHIP_SPRITE, x, y, chipW, chipH);
			int hx = x + pad, hy = y + (chipH - head) / 2;
			boolean drewHead = false;
			try {
				// Minecraft's resolved profile carries the signed `textures` property.
				// Rebuilding a GameProfile from only UUID + name discards it, so
				// SkinManager could only ever return the generated default head.
				com.mojang.authlib.GameProfile profile = mc.getGameProfile();
				if (titleSkinSupplier == null || !Objects.equals(titleSkinProfileId, profile.id())) {
					titleSkinProfileId = profile.id();
					titleSkinSupplier = mc.getSkinManager().createLookup(profile, false);
				}
				net.minecraft.world.entity.player.PlayerSkin skin = titleSkinSupplier.get();
				net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(guiGraphics, skin, hx, hy, head);
				drewHead = true;
			} catch (Throwable ignored) {
				// fall back to the logo below
			}
			if (!drewHead) {
				drawLogo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}
			int tx = hx + head + gap;
			guiGraphics.text(font, name, tx, y + (chipH - 8) / 2, 0xFFFFFFFF, true);   // vanilla button text
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
	private static void drawRings(GuiGraphicsExtractor guiGraphics, int w, int h) {
		for (int i = 0; i < rings.size(); i++) {
			Ring ring = rings.get(i);
			drawRingAt(guiGraphics, ring, w / 2.0 + RingField.offsetX(i, w), h / 2.0 + RingField.offsetY(i, w),
					ringAngle(i), ring.widthFrac() * w * 0.99, RING_HEIGHT_RATIO, ring.opacity());
		}
	}

	/** One ring texture as an ellipse of on-screen width {@code major} (GUI units). */
	private static void drawRingAt(GuiGraphicsExtractor guiGraphics, Ring ring, double cx, double cy, double angle,
								   double major, double aspect, float alpha) {
		if (alpha <= 0.002f) {
			return;
		}
		// The ellipse fills 0.9 of its square texture.
		float scale = (float) (major / (0.9 * TEX));
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.translate((float) cx, (float) cy);
		// Wrapped to [0,360) in double before the float cast (RingField does):
		// a float holding a large absolute angle would step visibly.
		pose.rotate((float) Math.toRadians(angle % 360.0));
		pose.scale(scale, (float) (scale * aspect / RING_HEIGHT_RATIO));
		pose.translate(-TEX / 2f, -TEX / 2f);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, ring.texture(), 0, 0, 0f, 0f, TEX, TEX, TEX, TEX, ARGB.white(alpha * layerAlpha));
		pose.popMatrix();
	}

	private static void drawGrain(GuiGraphicsExtractor guiGraphics, int w, int h) {
		if (grainId == null) {
			return;
		}
		// Tile in REAL pixels (pose-scale 1/guiScale): drawn in GUI units the
		// 1px noise texels become guiScale-sized blocks. At 1:1 each noise grain
		// is a single screen pixel, like the website's.
		double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		int realW = (int) Math.ceil(w * gs);
		int realH = (int) Math.ceil(h * gs);
		int tile = 256; // matches grain.png; larger tile -> ~3x fewer blits/frame
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs));
		int grainTint = ARGB.white(0.028f * layerAlpha);
		for (int y = 0; y < realH; y += tile) {
			for (int x = 0; x < realW; x += tile) {
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, grainId, x, y, 0f, 0f, tile, tile, tile, tile, grainTint);
			}
		}
		pose.popMatrix();
	}

	/** Full-screen edge vignette (the 1024px texture stretched to the GUI size). */
	private static void drawVignette(GuiGraphicsExtractor guiGraphics, int w, int h) {
		if (vignetteId == null) {
			return;
		}
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) w / VIGNETTE_TEX, (float) h / VIGNETTE_TEX);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, ARGB.white(layerAlpha));
		pose.popMatrix();
	}

	// ---- BOOT FONT ----
	// The loading overlay first paints DURING the initial resource reload,
	// before Minecraft has baked any font glyphs. Text drawn through mc.font in
	// that window comes out as one missing-glyph box per letter. So the loading
	// mark reads Minecraft's own ascii.png straight off the game jar (plain
	// classpath read: there from the first frame, no resource manager involved)
	// and blits the glyphs itself. Same pixels the vanilla font draws, so the
	// mark looks identical before and after fonts finish loading. If the sheet
	// can't be read, no text is drawn at all -- an empty frame is better than boxes.
	private static Identifier bootFontId;
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
						// getPixel returns ARGB in this era (1.21.2+): alpha is the top byte.
						if ((image.getPixel(cx + x, cy + y) >>> 24) != 0) {
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
			Identifier id = Identifier.fromNamespaceAndPath("originclient", "origin_boot_font");
			// No setFilter in this era -- a DynamicTexture's default sampler is
			// already NEAREST, which is what keeps the pixel letters crisp.
			DynamicTexture texture = new DynamicTexture(() -> "originclient:origin_boot_font", image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			bootFontId = id;
		} catch (Exception e) {
			bootFontId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin boot font (vanilla ascii.png) failed to load; loading screen draws no text", e);
		}
		return bootFontId != null;
	}

	/**
	 * Boot-font text (Minecraft's own ascii.png glyphs, see BOOT FONT) at GUI
	 * (x, top), {@code scale}x, {@code tracking} extra font px between letters,
	 * with vanilla's drop shadow. {@code anchor}: 0 = x is the left edge, 0.5 =
	 * centre, 1 = right edge. Draws nothing if the sheet couldn't be read.
	 */
	private static void bootText(GuiGraphicsExtractor g, String text, double x, double top, float scale, int tracking,
								 int argb, float anchor) {
		if (text == null || text.isEmpty() || !ensureBootFont()) {
			return;
		}
		// Widths in font px: a glyph advances (sheet width + 1) * unit.
		float unit = 8f / bootFontCell;
		float total = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			total += ch < 256 ? (bootFontWidth[ch] + 1) * unit + tracking : 0;
		}
		total -= unit + tracking;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(Math.round(x - total * scale * anchor), Math.round(top));
		pose.scale(scale, scale);
		// Vanilla's shadow: the same text one font px down-right at a quarter brightness.
		int shadow = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
		bootGlyphs(g, text, 1, 1, tracking, unit, shadow);
		bootGlyphs(g, text, 0, 0, tracking, unit, argb);
		pose.popMatrix();
	}

	private static void bootGlyphs(GuiGraphicsExtractor g, String text, int ox, int oy, int tracking, float unit, int argb) {
		// Retained-GUI era: the tint rides the per-blit ARGB (no shader colour).
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(ox, oy);
		pose.scale(unit, unit);
		float x = 0;   // sheet px
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch >= 256) {
				continue;
			}
			int w = bootFontWidth[ch];
			if (w > 0) {
				g.blit(RenderPipelines.GUI_TEXTURED, bootFontId, Math.round(x), 0, (float) ((ch % 16) * bootFontCell), (float) ((ch / 16) * bootFontCell),
						w, bootFontCell, bootFontTexW, bootFontTexH, argb);
			}
			x += w + 1 + tracking / unit;
		}
		pose.popMatrix();
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
				Identifier id = registerTexture(mc, "origin_ring_" + index,
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

		// One typography voice: the title and loading mark use Minecraft's own
		// letters, so the baked wordmark is never loaded (wordmarkId stays null).

		// Radial glow (the splash's centre lift). Optional; skipped on failure.
		try {
			Minecraft mc = Minecraft.getInstance();
			radialGlowId = registerTexture(mc, "origin_radial_glow", "/assets/originclient/textures/ui/radial_glow.png");
		} catch (Exception e) {
			radialGlowId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin radial glow failed to load; skipping centre lift", e);
		}
	}

	private static Identifier registerTexture(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		Identifier id = Identifier.fromNamespaceAndPath("originclient", name);
		// This era has no DynamicTexture.setFilter -- filtering rides the texture's
		// GpuSampler, NEAREST by default. Bind a LINEAR clamp sampler so the rings,
		// vignette and glow stay smooth.
		DynamicTexture texture = new DynamicTexture(() -> "originclient:" + name, image) {
			{
				// 1.21.11 dropped setFilter; bind a LINEAR clamp sampler for smooth icons/font.
				this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
			}
		};
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
