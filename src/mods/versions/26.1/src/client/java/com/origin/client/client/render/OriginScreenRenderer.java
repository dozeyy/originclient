package com.origin.client.client.render;

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
//  - STARTUP SPLASH: solid near-black with a soft centre lift, then the one
//    loading picture (LoadingLook: the real logo at 44 %, the capsule progress
//    bar, the stage caption). Drawn before fonts are baked: boot-font caption.
//  - MENUS: THIS version's own vanilla panorama, blurred by vanilla's blur,
//    dimmed and vignetted. Minecraft's own widgets on top, untouched.
//  - WORLD LOADING / CONNECTING: the same backdrop, then the same LoadingLook
//    picture with the screen's stage line (or the connection status) as caption.
//  - No drawn rings anywhere: the only ring shapes are inside origin_logo.png
//    (Will, 2026-10-25: drawn look-alikes read as a fake logo).
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
	// The menu's one jade highlight: the radial glow tinted
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

	// Soft radial gradient: the splash's centre lift and the Origin-own screens'
	// jade highlight.
	private static Identifier radialGlowId;
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
	 * Startup resource-load screen: near-black with a soft centre lift and the
	 * loading picture (LoadingLook) with "Loading resources NN%" (renderLoading0).
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
		// The splash's own backdrop (solid near-black with a soft centre lift), then
		// the one loading picture (1.21.1's LoadingLook): the real logo, the capsule
		// bar and the stage line with the reload's real percentage, in the boot font.
		guiGraphics.fill(0, 0, w, h, fade(OriginTheme.CLEAN_BG));
		drawCenterLift(guiGraphics, w, h, 0.05f);
		LoadingLook.draw(guiGraphics, layerAlpha, loadFill, startupCaption(loadFill), true);
	}

	// The title's first-use costs are paid by the real title screen itself,
	// rendered for real under the finished loading picture during the settle hold
	// (LoadingOverlayRevealMixin), so nothing has to be guessed and nothing is missed.

	private static net.minecraft.server.packs.resources.ReloadInstance currentReload() {
		return Minecraft.getInstance().getOverlay() instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor overlay
				? overlay.originclient$reload() : null;
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

		// This version's own panorama, blurred and dimmed (the background pass
		// normally drew it already this frame; a screen whose background pass Origin
		// doesn't own -- GenericMessageScreen draws its whole scene there -- draws it
		// here), then the one loading picture (LoadingLook) with the stage caption.
		if (loadingBackdropDrawn) {
			loadingBackdropDrawn = false;
		} else if (!backdrop(mc.screen, guiGraphics, mc.getDeltaTracker().getRealtimeDeltaTicks(), true, OriginTheme.CLEAN_DIM_LOADING)) {
			guiGraphics.fill(0, 0, w, h, OriginTheme.CLEAN_BG);
		}

		// The fill glides to each new count and only moves forward within one world
		// load; it sweeps until the load's first measured stage (LoadBar.scene).
		double now = SceneClock.sceneMs();
		sceneFill = SCENE_BAR.scene(com.origin.client.client.hud.FrameStats.frames(), progress, now, FILL_TAU);
		if (com.origin.client.client.OriginFrameLog.ENABLED) {   // off: no title string built every frame
			com.origin.client.client.OriginFrameLog.sceneBar(title == null ? "" : title.getString(), progress, sceneFill);
		}
		// The caption is rebuilt only when the title, the whole percent or the
		// game's language changes (compared by reference: the screen keeps one
		// Component), so a language switch never leaves a stale line.
		int pct = progress >= 0f ? Math.round(Math.max(0f, Math.min(1f, progress)) * 100f) : -1;
		String lang = mc.options == null ? null : mc.options.languageCode;
		if (title != labelTitle || pct != labelPct || !Objects.equals(lang, labelLang)) {
			labelTitle = title;
			labelPct = pct;
			labelLang = lang;
			labelCaption = sceneCaption(title, pct);
		}
		LoadingLook.draw(guiGraphics, 1f, sceneFill, labelCaption, false);
	}

	// The loading scene's caption and what it was built from.
	private static net.minecraft.network.chat.Component labelTitle;
	private static String labelLang;
	private static int labelPct = Integer.MIN_VALUE;
	private static String labelCaption = "";

	/**
	 * The stage line (1.21.1's sceneCaption): a real fraction here is the spawn-area
	 * preparation (LevelLoadingScreen), so it is vanilla's own "Preparing spawn area: N%"
	 * line; without one, the screen's own title. Never an invented stage name.
	 */
	private static String sceneCaption(net.minecraft.network.chat.Component title, int pct) {
		if (pct >= 0) {
			return net.minecraft.client.resources.language.I18n.get("menu.preparingSpawn", pct);
		}
		return title == null ? "" : title.getString().trim();
	}

	/**
	 * Connecting to a server: the same loading picture as the other screens (the
	 * logo at 44 %, the bar sweeping: no real measure), the connection status as its
	 * caption. ConnectScreenMixin hides vanilla's status line and calls this at TAIL,
	 * over the menu backdrop (ScreenBackgroundMixin); the screen's Cancel button is
	 * seated below the picture ({@link #connectCancelTop}). False when Origin
	 * rendering is off or this draw fails (the mixin then keeps vanilla's status line).
	 */
	public static boolean renderConnecting(GuiGraphicsExtractor guiGraphics, net.minecraft.network.chat.Component status) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (status != connectStatus) {
				connectStatus = status;
				connectCaption = status == null ? "" : status.getString().trim();
			}
			LoadingLook.draw(guiGraphics, 1f, -1, connectCaption, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/**
	 * Where Connecting's Cancel button goes (GUI px, its top edge): at 62 % of the height (1.21.1,
	 * A-Workbench-3), and always at least 12 GUI px under the loading picture's caption, so the
	 * picture never has to move for it; kept 8 GUI px clear of the bottom when the window allows.
	 */
	public static int connectCancelTop(int screenH, int buttonH) {
		double clearOfPicture = LoadingLook.logoCentreGui() + LoadingLook.belowCentreGui() + 12.0;
		int top = (int) Math.max(Math.round(screenH * 0.62) - buttonH / 2, Math.ceil(clearOfPicture));
		return Math.max(0, Math.min(top, screenH - buttonH - 8));
	}

	private static net.minecraft.network.chat.Component connectStatus;
	private static String connectCaption = "";

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
		// A screen drawn as ANOTHER screen's background (26.x FriendsOverlayScreen
		// redraws its parent, then blurs itself with vanilla's unguarded call) must
		// not take the frame's one blur, or the overlay's blur throws and crashes.
		if ((Object) inv != Minecraft.getInstance().screen || frameAlreadyBlurred(g)) {
			return;
		}
		try {
			inv.originclient$extractBlurredBackground(g);
		} catch (IllegalStateException alreadyBlurred) {
			// "Can only blur once per frame": the frame is already blurred
		}
	}

	/**
	 * True when this frame's GUI already holds its one blur (firstStratumAfterBlur
	 * set; MAX_VALUE = not yet). False when the accessors are missing, so callers
	 * fall back to their own catch. MenuBlurMixin uses it to skip vanilla's
	 * extractBlurredBackground, which would otherwise throw "Can only blur once
	 * per frame" and crash the game.
	 */
	public static boolean frameAlreadyBlurred(GuiGraphicsExtractor g) {
		try {
			return (Object) g instanceof com.origin.client.client.mixin.GuiGraphicsExtractorAccessor ga
					&& (Object) ga.originclient$guiRenderState() instanceof com.origin.client.client.mixin.GuiRenderStateAccessor sa
					&& sa.originclient$firstStratumAfterBlur() != Integer.MAX_VALUE;
		} catch (Throwable ignored) {
			return false;
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
	static void drawLogo(GuiGraphicsExtractor g, double cx, double cy, double size, float alpha) {
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

	// The official wordmark (textures/ui/origin_wordmark.png, byte-identical to the
	// launcher's Assets/Brand/wordmark.png) gets the same one-time box-filter bake per
	// physical height, so it stays crisp at any GUI scale. Never redrawn or re-typed.
	private static final java.util.Map<Integer, Identifier> WORDMARK_SIZES = new java.util.HashMap<>();
	private static NativeImage wordmarkSrc;
	private static boolean wordmarkFailed;

	/** Draws the official wordmark image centred on (cx, cy), {@code heightGui} GUI px tall. False = not drawn (caller keeps the text fallback). */
	private static boolean drawWordmarkImage(GuiGraphicsExtractor g, double cx, double cy, double heightGui, float alpha) {
		if (wordmarkFailed) {
			return false;
		}
		try {
			int gs = com.origin.client.client.gui.OriginSurface.scale();
			int ph = (int) Math.round(heightGui * gs);
			if (ph <= 0 || ph > 512) {
				return false;
			}
			if (wordmarkSrc == null) {
				try (InputStream in = open(com.origin.client.client.gui.OriginUi.WORDMARK_PATH)) {
					wordmarkSrc = NativeImage.read(in);
				}
			}
			int pw = Math.max(1, (int) Math.round(ph * (double) wordmarkSrc.getWidth() / wordmarkSrc.getHeight()));
			Identifier id = WORDMARK_SIZES.get(ph);
			if (id == null) {
				String name = "wordmark_px_" + ph;
				id = Identifier.fromNamespaceAndPath("originclient", name);
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "originclient:" + name, boxFilter(wordmarkSrc, pw, ph)));
				WORDMARK_SIZES.put(ph, id);
			}
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.scale(1f / gs, 1f / gs);
			int px = (int) Math.round(cx * gs - pw / 2.0), py = (int) Math.round(cy * gs - ph / 2.0);
			g.blit(RenderPipelines.GUI_TEXTURED, id, px, py, 0f, 0f, pw, ph, pw, ph, ARGB.white(Math.max(0f, Math.min(1f, alpha))));
			pose.popMatrix();
			return true;
		} catch (Throwable t) {
			wordmarkFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin wordmark bake failed; the title falls back to the boot-font text", t);
			return false;
		}
	}

	/**
	 * Premultiplied 4x4 box-filter downsample of {@code src} to dw x dh (a new image; CPU only).
	 * getPixel/setPixel are ARGB in this era; the colour channels are averaged the same way
	 * whatever their order, so only the alpha byte position matters.
	 */
	private static NativeImage boxFilter(NativeImage src, int dw, int dh) {
		int sw = src.getWidth(), sh = src.getHeight();
		NativeImage dst = new NativeImage(dw, dh, true);
		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				double aSum = 0, c0 = 0, c1 = 0, c2 = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						int tx = Math.min(sw - 1, (int) ((x + (sx + 0.5) / 4.0) * sw / dw));
						int ty = Math.min(sh - 1, (int) ((y + (sy + 0.5) / 4.0) * sh / dh));
						int c = src.getPixel(tx, ty);
						double a = ((c >>> 24) & 0xFF) / 255.0;
						aSum += a;
						c0 += a * ((c & 0xFF) / 255.0);
						c1 += a * (((c >> 8) & 0xFF) / 255.0);
						c2 += a * (((c >> 16) & 0xFF) / 255.0);
					}
				}
				double a = aSum / 16.0;
				int v0 = a > 0 ? (int) Math.round(Math.min(1.0, c0 / aSum) * 255) : 255;
				int v1 = a > 0 ? (int) Math.round(Math.min(1.0, c1 / aSum) * 255) : 255;
				int v2 = a > 0 ? (int) Math.round(Math.min(1.0, c2 / aSum) * 255) : 255;
				dst.setPixel(x, y, ((int) Math.round(a * 255) << 24) | (v2 << 16) | (v1 << 8) | v0);
			}
		}
		return dst;
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
	 * Origin's own screens (Mod Menu, HUD editor, Origin windows): charcoal with
	 * one soft glow, the grain tiles and the vignette. No rings: the only ring
	 * shapes in Origin's UI are inside origin_logo.png (Will, 2026-10-25).
	 * Minecraft's own menus use renderMenuBackdrop. Returns true only if the
	 * backdrop actually drew; callers that cancel vanilla's own backdrop must key
	 * off this.
	 */
	public static boolean renderTitleBackground(GuiGraphicsExtractor guiGraphics) {
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

	/** One restrained jade highlight, top right (kept from the Ion Jade grade). */
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
			// The REAL Origin logo (origin_logo.png) and the OFFICIAL wordmark image
			// (origin_wordmark.png) under it, both still; never redrawn or typed
			// (Will 2026-10-25). TitleLayout drops the logo, then the wordmark, when extra
			// rows need the room. The boot-font text is only the fail-soft if the image can't draw.
			if (L.markSize > 0) {
				drawLogo(guiGraphics, L.markCenterX, L.markCenterY, L.markSize, 1f);
			}
			if (L.showWordmark && !drawWordmarkImage(guiGraphics, L.markCenterX, L.wordCenterY, TitleLayout.WORD_H, 1f)) {
				drawWordmarkText(guiGraphics, L.markCenterX, L.wordCenterY + 7, OriginTheme.CLEAN_TEXT);
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

	// The chip's skin lookup, made once per profile (createLookup builds a new
	// supplier each call; the skin manager caches the texture behind it).
	private static UUID titleSkinProfileId;
	private static Supplier<net.minecraft.world.entity.player.PlayerSkin> titleSkinSupplier;

	/**
	 * Main menu: the player (head + username) in the top-left, with no plate behind
	 * it (A-Workbench-3, Will 2026-10-25). Head via the skin manager (default skin shows first,
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
			// The player in the top-left corner (A-Workbench-3): the head and name straight
			// on the panorama, no plate behind them (Will, 2026-10-25: "remove the background
			// on the top left player icon"). Not clickable (there is no account menu here).
			// The head's left edge lines up with the footer text (both CORNER in).
			int x = TitleLayout.CORNER, y = TitleLayout.CORNER, chipH = TitleLayout.CORNER_SIZE;
			int head = 12, gap = 5;
			int hx = x, hy = y + (chipH - head) / 2;
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
				if (!drawBakedHead(guiGraphics, skin, hx, hy, head, 2)) {
					net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(guiGraphics, skin, hx, hy, head);
				}
				drewHead = true;
			} catch (Throwable ignored) {
				// fall back to the logo below
			}
			if (!drewHead) {
				drawLogo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}
			int tx = hx + head + gap;
			if (com.origin.client.client.gui.OriginButtonRenderer.titleTextActive()) {
				// Inter 600 in parchment at the button label's em (A-Workbench-3: the name reads like a label).
				com.origin.client.client.gui.OriginButtonRenderer.drawTitleText(guiGraphics, name, tx, y + (chipH - 8) / 2f,
						OriginTheme.TITLE_LABEL_EM, OriginTheme.TITLE_TEXT, true);
			} else {
				guiGraphics.text(font, name, tx, y + (chipH - 8) / 2, OriginTheme.TEXT, true);
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	// ---- the head, baked at physical resolution (anti-aliased corners) ----
	// The face (8x8 base + hat layer, exactly what PlayerFaceExtractor draws) is read from the skin's
	// CPU pixels once, resampled NEAREST to head*guiScale physical pixels, and its alpha is multiplied by
	// an exact rounded-box coverage at physical-pixel centres (the smooth-ui-baked-masks recipe), then
	// blitted 1:1 so one texel is one screen pixel (DESIGN-SYSTEM 15.2: head 12 GUI px, radius 2).
	// 26.x has no synchronous GPU readback, so the pixels come from the CPU side: a downloaded skin is a
	// DynamicTexture that keeps its NativeImage (getPixels), a default skin is a resource-pack PNG read
	// once from the ResourceManager. Re-baked only when the skin's texture, the GUI scale or the size
	// changes (the real skin pops in async over the default one); never per frame.
	private static final Identifier HEAD_BAKED = Identifier.fromNamespaceAndPath("originclient", "title_head_baked");
	private static Identifier headSrc;
	private static int headGs = -1, headSize = -1;
	private static boolean headReady = false, headBakeFailed = false;

	private static boolean drawBakedHead(GuiGraphicsExtractor g, net.minecraft.world.entity.player.PlayerSkin skin, int hx, int hy, int head, int radius) {
		if (headBakeFailed) {
			return false;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			int gs = com.origin.client.client.gui.OriginSurface.scale();
			Identifier src = skin.body().texturePath();
			int size = head * gs;
			if (!headReady || !src.equals(headSrc) || gs != headGs || size != headSize) {
				bakeHead(mc, src, size, radius * gs);
				headSrc = src;
				headGs = gs;
				headSize = size;
				headReady = true;
			}
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.scale(1f / gs, 1f / gs);
			g.blit(RenderPipelines.GUI_TEXTURED, HEAD_BAKED, hx * gs, hy * gs, 0f, 0f, size, size, size, size, 0xFFFFFFFF);
			pose.popMatrix();
			return true;
		} catch (Throwable t) {
			headBakeFailed = true;   // vanilla's square PlayerFaceExtractor head takes over for the session
			com.origin.client.OriginClient.LOGGER.warn("Origin baked player head failed; using the square head", t);
			return false;
		}
	}

	private static void bakeHead(Minecraft mc, Identifier skinTex, int size, int r) throws java.io.IOException {
		NativeImage skinPx;
		boolean owned = false;
		net.minecraft.client.renderer.texture.AbstractTexture t = mc.getTextureManager().getTexture(skinTex);
		if (t instanceof DynamicTexture dt && dt.getPixels() != null) {
			skinPx = dt.getPixels();
		} else {
			try (InputStream in = mc.getResourceManager().open(skinTex)) {
				skinPx = NativeImage.read(in);
			}
			owned = true;
		}
		NativeImage out = null;
		try {
			int tw = skinPx.getWidth(), th = skinPx.getHeight();
			if (tw < 64 || th < 16) {
				throw new IllegalStateException("skin texture " + skinTex + " is " + tw + "x" + th);
			}
			int k = tw / 64;   // 1 for 64 px skins, 2 for HD
			out = new NativeImage(size, size, true);
			double half = size / 2.0, inner = half - r;
			for (int py = 0; py < size; py++) {
				int fy = Math.min(7, py * 8 / size);
				for (int px = 0; px < size; px++) {
					int fx = Math.min(7, px * 8 / size);
					int base = skinPx.getPixel(8 * k + fx * k + k / 2, 8 * k + fy * k + k / 2);
					int hat = skinPx.getPixel(40 * k + fx * k + k / 2, 8 * k + fy * k + k / 2);
					// ARGB ints: alpha in the top byte. Hat over base, then the corner coverage into alpha.
					double ha = ((hat >>> 24) & 0xFF) / 255.0, ba = ((base >>> 24) & 0xFF) / 255.0;
					double oa = ha + ba * (1.0 - ha);
					int rgb = 0;
					for (int shift = 0; shift <= 16; shift += 8) {
						double hc = ((hat >> shift) & 0xFF) / 255.0, bc = ((base >> shift) & 0xFF) / 255.0;
						double c = oa <= 0.0 ? 0.0 : (hc * ha + bc * ba * (1.0 - ha)) / oa;
						rgb |= (int) Math.round(Math.min(1.0, c) * 255.0) << shift;
					}
					double dx = Math.max(Math.abs(px + 0.5 - half) - inner, 0.0), dy = Math.max(Math.abs(py + 0.5 - half) - inner, 0.0);
					double cov = Math.max(0.0, Math.min(1.0, 0.5 - (Math.sqrt(dx * dx + dy * dy) - r)));
					int a = (int) Math.round(oa * cov * 255.0);
					out.setPixel(px, py, (a << 24) | rgb);
				}
			}
			NativeImage baked = out;
			// Takes ownership of `out`; registering under the same id replaces (and closes) the old bake.
			mc.getTextureManager().register(HEAD_BAKED, new DynamicTexture(() -> "originclient:title_head_baked", baked));
			out = null;
		} finally {
			if (owned) {
				skinPx.close();
			}
			if (out != null) {
				out.close();
			}
		}
	}

	// ---- Primitives ----

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
	static void bootText(GuiGraphicsExtractor g, String text, double x, double top, float scale, int tracking,
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
		// GpuSampler, NEAREST by default. Bind a LINEAR clamp sampler so the grain,
		// vignette and glow stay smooth.
		DynamicTexture texture = new DynamicTexture(() -> "originclient:" + name, image) {
			{
				this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
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
