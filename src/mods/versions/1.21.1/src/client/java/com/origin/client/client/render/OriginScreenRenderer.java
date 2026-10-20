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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import org.lwjgl.opengl.GL11;

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
	// Per-letter reveal bands: [x0,x1] texture-px columns, one per glyph, from
	// wordmark.json. Null -> the loading screen draws the whole mark at once
	// (no staggered reveal) instead of failing.
	private static int[] wmLetterX0, wmLetterX1;

	// Loading-screen animation clock: RingField.sceneMs() at the first loading
	// frame (-1 = not yet), so the per-letter reveal is time-driven on the same
	// smoothed clock as the rings.
	private static double loadStartScene = -1;
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
	// Wordmark breathing-glow period (ms) on the title screen.
	private static final double BREATH_MS = 3200.0;
	// Orbiting bodies riding the rings: {ringIndex, periodSeconds, phase, coreFrac(of w), alpha}.
	// One bright body on the front ring, one fainter/slower on a back ring.
	private static final double[][] BODIES = {
			{0, 26.0, 0.0, 0.010, 0.85},
			{2, 40.0, 0.5, 0.008, 0.55},
	};
	// Sparse drifting background dust.
	private static final int PARTICLE_COUNT = 28;

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
	 * Startup resource-load screen: near-black + fine grain, the Origin mark as
	 * live morphing strokes (MarkMotion: circles, triangles, squares, the mark,
	 * on a loop), the "ORIGIN" wordmark building in letter by letter beneath it
	 * and a word-width bar under that. No percentage caption.
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
			renderLoading0(guiGraphics, reloadProgress(progress), true, true);
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
			// Clean look (2026-10-19): the title shows the real Origin logo, still, so
			// the loading layer simply dissolves over it -- no mark flight.
			boolean fly = false;
			// The title underneath already drives the ring field: don't pull it back.
			renderLoading0(guiGraphics, 1f, false, !fly);
			guiGraphics.flush();
			if (fly) {
				layerAlpha = 1f;
				markHandedOff = false;
				Minecraft mc = Minecraft.getInstance();
				TitleLayout L = TitleLayout.of(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
				MarkMotion.drawFlight(guiGraphics, L.markCenterX, L.markCenterY, L.markSize);
			}
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

	private static void renderLoading0(GuiGraphics guiGraphics, float progress, boolean drive, boolean drawMark) {
		ensureLoaded();
		if (drive) {
			field(RingField.Mood.LOADING);
		}
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		double scene = RingField.sceneMs();
		if (loadStartScene < 0) {
			loadStartScene = scene;
		}
		long elapsed = (long) (scene - loadStartScene);
		// The fill only ever moves forward within a load and is full exactly when
		// the reload is done (LoadBar: the reload's own fraction dips mid-load as
		// work is queued); a load after a finished one starts it over. The
		// dissolve passes 1: the bar finishes its glide while the title appears.
		net.minecraft.server.packs.resources.ReloadInstance barReload = currentReload();
		loadFill = LOAD_BAR.update(barReload, barReload != null ? barReload.isDone() : clamped >= 1f, clamped, scene, FILL_TAU);
		com.origin.client.client.OriginFrameLog.bar(barReload, clamped, loadFill);

		double markT = scene - loadStartScene;
		if (drive) {
			// Origin's textures were decoded on the reload's workers; upload one
			// loader's worth per frame now, so the title's first frames don't.
			com.origin.client.client.OriginAssets.uploadSlice();
			net.minecraft.server.packs.resources.ReloadInstance reload = currentReload();
			if (reload != markReload) {
				// A new reload (a resource-pack change after startup): the mark loops again.
				markReload = reload;
				markReloadAt = System.nanoTime();
				MarkMotion.unsettle();
			}
			String why = reload == null || MarkMotion.settling() ? null : settleReason(reload);
			if (why != null) {
				MarkMotion.settle(markT);
				markRestLogged = false;
				com.origin.client.client.OriginFrameLog.mark("mark-settle (" + why + ")");
			}
			if (!markRestLogged && MarkMotion.atRest(markT)) {
				markRestLogged = true;
				com.origin.client.client.OriginFrameLog.mark("mark at rest (clean pose)");
			}
		} else {
			MarkMotion.settle(markT);   // the settle hold / dissolve: the load is done
		}
		lastMarkT = markT;
		// Clean splash (Will's approved mockup, 2026-10-19): solid near-black with a
		// soft centre lift, the REAL Origin logo, the ORIGIN wordmark, a thin bar
		// and the real percent. MarkMotion above still runs its clock (the settle
		// hold reads it), it just isn't drawn: the logo is the asset, not a redraw.
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		guiGraphics.fill(0, 0, w, h, fade(OriginTheme.CLEAN_BG));
		drawCenterLift(guiGraphics, w, h, 0.05f);
		double size = clampD(h * 0.11, 24, 44);
		double markCy = h * 0.44;
		if (drawMark) {
			drawLogo(guiGraphics, w / 2.0, markCy, size, layerAlpha);
		}
		double wordBase = markCy + size / 2.0 + 17;
		float textIn = sdfFadeIn();
		if (textIn > 0f) {
			drawWordmarkText(guiGraphics, w / 2.0, wordBase, fadeA(0xFFFFFFFF, textIn));
		}
		double barW = Math.min(160, w * 0.5);
		double barTop = wordBase + 13;
		drawThinBar(guiGraphics, w / 2.0, barTop, barW, (float) loadFill, false);
		if (textIn > 0f) {
			String pct = Math.round(Math.max(0.0, Math.min(1.0, loadFill)) * 100) + "%";
			float pw = com.origin.client.client.gui.MenuText.width(pct, OriginTheme.CLEAN_EM_HINT);
			com.origin.client.client.gui.MenuText.drawMid(guiGraphics, pct, w / 2.0 + barW / 2.0 - pw, barTop + 8,
					OriginTheme.CLEAN_EM_HINT, 0f, fadeA(OriginTheme.CLEAN_TEXT_FAINT, textIn));
		}
	}

	// MSDF text on the splash appears only once the font + shader are live (they
	// load during the startup reload), then fades in over 250 ms instead of popping.
	private static long sdfReadyAt = 0;

	private static float sdfFadeIn() {
		if (!com.origin.client.client.gui.OriginSdfFont.ready()) {
			return 0f;
		}
		long now = System.nanoTime();
		if (sdfReadyAt == 0) {
			sdfReadyAt = now;
		}
		return (float) Math.min(1.0, (now - sdfReadyAt) / 250_000_000.0);
	}

	// The title's first-use costs (textures, driver shader builds, glyphs) used to
	// be guessed at here, one hand-picked warm-up draw per loading frame. They are
	// now paid by the real title screen itself, rendered for real under the
	// finished loading picture during the settle hold (LoadingOverlayRevealMixin),
	// so nothing has to be guessed and nothing is missed.

	// The reload the loading mark's settle belongs to, and the mark's loop time last frame.
	private static net.minecraft.server.packs.resources.ReloadInstance markReload;
	private static long markReloadAt;   // nanoTime the loading screen first drew for markReload
	private static boolean markRestLogged;   // dev frame log: "mark at rest" said for this settle
	private static double lastMarkT = 0;

	private static net.minecraft.server.packs.resources.ReloadInstance currentReload() {
		return Minecraft.getInstance().getOverlay() instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor overlay
				? overlay.originclient$reload() : null;
	}

	// The reload has reached the stretch where its work runs on the render thread,
	// where one step can hold a frame for hundreds of ms: a listener ran its first
	// task there (ReloadTaskTagMixin; some mods do heavy work there while others
	// still prepare), or every listener has prepared (the apply steps), or it is
	// done. Without the mixins: only once done.
	private static String settleReason(net.minecraft.server.packs.resources.ReloadInstance reload) {
		if (reload.isDone()) {
			return "load done";
		}
		if (com.origin.client.client.StartupTransition.reloadRenderWorkSince(markReloadAt)) {
			return "render-thread reload work queued";
		}
		if (reload instanceof com.origin.client.client.mixin.loading.SimpleReloadInstanceAccessor s
				&& s.originclient$allPreparations().isDone()) {
			return "all listeners prepared";
		}
		return null;
	}

	/**
	 * ReloadTaskBudgetMixin: hold the render-thread queue this frame. True while
	 * render-thread reload work is waiting for THIS loading screen and the mark
	 * has not finished its settle glide (MarkMotion.SETTLE_MS) onto its clean
	 * pose, so no long step can freeze it mid-motion. The settle itself starts in
	 * the loading screen's draw the frame the work is queued. The caller caps it.
	 */
	public static boolean holdReloadTasksForMark() {
		if (broken || markReload == null || currentReload() != markReload) {
			return false;
		}
		// Clean splash (2026-10-19): the logo is still, so there is no motion to
		// protect -- never hold the reload's render-thread work for it.
		return false;
	}

	/** The loading mark has landed and stopped (the settle hold may end). The
	 *  clean splash's logo is still, so it is always at rest. */
	public static boolean loadingMarkAtRest() {
		return true;
	}

	/**
	 * The settle hold (LoadingOverlayRevealMixin): the finished loading picture,
	 * opaque, over the screen the hold has just rendered for real underneath, so
	 * the screen's first-use costs are paid where nobody can see them. Bar full,
	 * the mark at rest. False (nothing to cancel) when Origin rendering is off or
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
			renderLoading0(guiGraphics, 1f, false, true);
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
		if (!backdrop(mc.screen, guiGraphics, mc.getTimer().getRealtimeDeltaTicks(), true, OriginTheme.CLEAN_DIM_LOADING)) {
			guiGraphics.fill(0, 0, w, h, OriginTheme.CLEAN_BG);
		}

		// The fill glides to each new count and only moves forward within one world
		// load; it sweeps until the load's first measured stage (LoadBar.scene).
		double now = RingField.sceneMs();
		sceneFill = SCENE_BAR.scene(com.origin.client.client.hud.FrameStats.frames(), progress, now, FILL_TAU);
		if (com.origin.client.client.OriginFrameLog.ENABLED) {   // off: no title string built every frame
			com.origin.client.client.OriginFrameLog.sceneBar(title == null ? "" : title.getString(), progress, sceneFill);
		}
		String screenTitle = title == null ? "" : title.getString().trim();
		String big = screenTitle;
		String small = "";
		if (progress >= 0f) {
			// World loading: the world's name big, vanilla's own "Preparing spawn
			// area: N%" line small. Never an invented stage name.
			String levelName = null;
			try {
				if (mc.getSingleplayerServer() != null) {
					levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
				}
			} catch (Throwable ignored) {
				levelName = null;
			}
			int pct = Math.round(Math.max(0f, Math.min(1f, progress)) * 100f);
			if (levelName != null && !levelName.isBlank()) {
				big = levelName;
			}
			small = net.minecraft.client.resources.language.I18n.get("menu.preparingSpawn", pct);
		}
		drawSceneText(guiGraphics, w, h, big, small, sceneFill);
	}

	/** The loading scene's centred logo, title, bar and stage line (fill < 0: sweeping). */
	private static void drawSceneText(GuiGraphics guiGraphics, int w, int h, String big, String small, double fill) {
		double cy = h * 0.42;
		double logo = 22;
		drawLogo(guiGraphics, w / 2.0, cy, logo, 1f);
		double titleMid = cy + logo / 2.0 + 13;
		drawCenteredLabel(guiGraphics, big, w / 2.0, titleMid, OriginTheme.CLEAN_EM_TITLE, OriginTheme.CLEAN_TEXT);
		double barW = Math.min(160, w * 0.5);
		double barTop = titleMid + 10;
		drawThinBar(guiGraphics, w / 2.0, barTop, barW, (float) fill, fill < 0);
		if (!small.isEmpty()) {
			drawCenteredLabel(guiGraphics, small, w / 2.0, barTop + 9, OriginTheme.CLEAN_EM_HINT, OriginTheme.CLEAN_TEXT_DIM);
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

	/** One label centred on (cx, midY): Inter (MSDF) when it can draw it, else the vanilla font. */
	private static void drawCenteredLabel(GuiGraphics g, String s, double cx, double midY, float em, int argb) {
		if (s == null || s.isEmpty()) {
			return;
		}
		if (com.origin.client.client.gui.MenuText.usable(s)) {
			com.origin.client.client.gui.MenuText.drawCentered(g, s, cx, midY, em, 0f, argb);
		} else {
			com.origin.client.client.gui.MenuText.vanillaCentered(g, s, cx, midY, argb);
		}
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

	// Separate latch from `broken`: a failed panorama/blur call (e.g. the
	// ScreenInvoker mixin didn't apply) only hands the backdrop back to vanilla.
	private static volatile boolean backdropBroken = false;

	/**
	 * Out-of-world menus: THIS version's own Mojang panorama (Screen.PANORAMA,
	 * read from vanilla resources at runtime, so every version module shows its
	 * own update's scene), blurred by vanilla's blur (follows Accessibility > Menu
	 * Background Blurriness), dimmed and vignetted. Same cost as vanilla's own
	 * backdrop (panorama + one blur pass). False: the caller keeps vanilla's.
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
			com.origin.client.client.mixin.ScreenInvoker inv = (com.origin.client.client.mixin.ScreenInvoker) screen;
			if (panorama) {
				inv.originclient$renderPanorama(g, partialTick);
			}
			inv.originclient$renderBlurredBackground(partialTick);
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
			id = ResourceLocation.fromNamespaceAndPath("originclient", "logo_px_" + ps);
			Minecraft.getInstance().getTextureManager().register(id, tex);
			LOGO_SIZES.put(ps, id);
			return id;
		} catch (Throwable t) {
			logoFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin logo bake failed; using the plain logo blit", t);
			return null;
		}
	}

	/** "ORIGIN" in Inter semibold caps, tracked wide (the mockup wordmark), baseline at {@code baseline}. */
	private static void drawWordmarkText(GuiGraphics g, double cx, double baseline, int argb) {
		if (!com.origin.client.client.gui.OriginSdfFont.ready()) {
			return;
		}
		// Caps 7.8 GUI px tall, tracked 0.42 em (the mockup's spaced ORIGIN).
		float capPerEm = com.origin.client.client.gui.OriginSdfFont.capHeight(1f, true);
		float em = capPerEm > 0 ? 7.8f / capPerEm : 10.7f;
		float tracking = em * 0.42f;
		double mid = baseline - 7.8 / 2.0;
		com.origin.client.client.gui.MenuText.drawCentered(g, "ORIGIN", cx + tracking / 2.0, mid, em, tracking, argb);
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


	private static void drawRadial(GuiGraphics guiGraphics, double cx, double cy, double diameter, double alpha) {
		int d = Math.max(2, (int) Math.round(diameter));
		shade(1f, 1f, 1f, (float) alpha);
		guiGraphics.blit(radialGlowId, (int) Math.round(cx - d / 2.0), (int) Math.round(cy - d / 2.0),
				d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
	}

	private static UUID titleSkinProfileId;
	private static Supplier<net.minecraft.client.resources.PlayerSkin> titleSkinSupplier;
	// False from the startup flight's first frame until the title draws its mark
	// itself: while the loading overlay is still up, the dissolve layer draws the
	// flying mark over the title.
	private static boolean markHandedOff = true;

	/**
	 * Main menu: the Origin mark above the compact action stack. After the game's
	 * first load it is the loading screen's mark finishing its flight here
	 * (MarkMotion); any other time it is simply there.
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
			drawLogo(guiGraphics, L.markCenterX, L.markCenterY, L.markSize, 1f);
			drawWordmarkText(guiGraphics, L.markCenterX, L.wordBaseline, OriginTheme.CLEAN_TEXT);
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
	 * inside the frame. Identity presence, like premium launchers. Head via the
	 * skin manager (default skin shows first, the real one pops in async); if the
	 * head can't be resolved the chip falls back to the Origin ring mark, and if
	 * the whole chip throws it flips the renderer to vanilla like any Origin draw.
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
			// The mockup's chip: a 16-GUI-px glass chip in the top-left corner, the
			// player's head and name. No chevron: there is no account menu here.
			int x = TitleLayout.CORNER, y = TitleLayout.CORNER, chipH = TitleLayout.CORNER_SIZE;
			int head = 10, pad = 3, gap = 5;
			boolean sdf = com.origin.client.client.gui.MenuText.usable(name);
			int textW = sdf ? (int) Math.ceil(com.origin.client.client.gui.MenuText.width(name, OriginTheme.CLEAN_EM_LABEL))
					: font.width(name);
			int chipW = pad + head + gap + textW + pad + 3;
			com.origin.client.client.gui.OriginSurface.draw(guiGraphics, x, y, chipW, chipH,
					OriginTheme.CLEAN_GLASS, OriginTheme.CLEAN_LINE, 1f);
			int hx = x + pad, hy = y + (chipH - head) / 2;
			boolean drewHead = false;
			try {
				// Minecraft's resolved profile carries the signed `textures` property.
				// Rebuilding a GameProfile from only UUID + name discards that property,
				// so SkinManager can return only the generated default head forever.
				com.mojang.authlib.GameProfile profile = mc.getGameProfile();
				if (titleSkinSupplier == null || !Objects.equals(titleSkinProfileId, profile.getId())) {
					titleSkinProfileId = profile.getId();
					titleSkinSupplier = mc.getSkinManager().lookupInsecure(profile);
				}
				net.minecraft.client.resources.PlayerSkin skin = titleSkinSupplier.get();
				net.minecraft.client.gui.components.PlayerFaceRenderer.draw(guiGraphics, skin, hx, hy, head);
				drewHead = true;
			} catch (Throwable ignored) {
				// fall back to the logo below
			}
			if (!drewHead) {
				drawLogo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}
			int tx = hx + head + gap;
			if (sdf) {
				com.origin.client.client.gui.MenuText.drawMid(guiGraphics, name, tx, y + chipH / 2.0,
						OriginTheme.CLEAN_EM_LABEL, 0f, OriginTheme.CLEAN_TEXT);
			} else {
				guiGraphics.drawString(font, name, tx, y + (chipH - 8) / 2, OriginTheme.CLEAN_TEXT, true);
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** Clamps a target ink height so the wordmark's displayed width stays within maxWidthFrac of the screen. */
	private static double fitInkHeight(double targetInkHeight, int w, double maxWidthFrac) {
		if (wordmarkId == null || wmInkW <= 0) {
			return targetInkHeight;
		}
		double dispW = wmInkW * (targetInkHeight / wmInkH);
		double maxW = w * maxWidthFrac;
		if (dispW > maxW) {
			return wmInkH * (maxW / wmInkW);
		}
		return targetInkHeight;
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

	private static double frac(double v) {
		return v - Math.floor(v);
	}

	/**
	 * Sparse drifting background dust: fixed pseudo-random particles (seeded from
	 * the index, no stored state) that slowly drift and twinkle. Monochrome, very
	 * low alpha -- depth and life without pulling focus.
	 */
	private static void drawParticles(GuiGraphics guiGraphics, int w, int h) {
		double t = System.currentTimeMillis() / 1000.0;
		for (int i = 0; i < PARTICLE_COUNT; i++) {
			double h1 = frac(Math.sin(i * 12.9898) * 43758.5453);
			double h2 = frac(Math.sin(i * 78.233) * 12345.678);
			double h3 = frac(Math.sin(i * 39.425) * 98765.43);
			double speed = 0.004 + 0.010 * h3;
			double dir = (i % 2 == 0) ? 1.0 : -1.0;
			double x = frac(h1 + t * speed * 0.15 * dir) * w;
			double y = frac(h2 - t * speed * 0.10) * h;
			double twinkle = 0.5 - 0.5 * Math.cos(t * (0.4 + 0.6 * h3) + h1 * 6.2832);
			int a = (int) Math.round((0.04 + 0.09 * h3) * twinkle * 255);
			if (a <= 1) {
				continue;
			}
			int px = (int) Math.round(x), py = (int) Math.round(y);
			int sz = h3 > 0.7 ? 2 : 1;
			guiGraphics.fill(px, py, px + sz, py + sz, (a << 24) | 0x00FFFFFF);
		}
	}

	/**
	 * Bodies orbiting the rings: each body's parametric point on a ring's ellipse
	 * (a, a*heightRatio) advances on its own period, then is rotated by that
	 * ring's live rotation so it visually rides the tilted ellipse -- a small
	 * glowing "planet on its orbit" that plays the Origin identity. Drawn with the
	 * radial-glow texture (halo + core). Skipped if the rings/glow aren't loaded.
	 */
	private static void drawOrbitingBodies(GuiGraphics guiGraphics, int w, int h) {
		if (ringsFailed || rings.isEmpty() || radialGlowId == null) {
			return;
		}
		double cx = w / 2.0, cy = h / 2.0;
		double now = System.currentTimeMillis() / 1000.0;
		RenderSystem.enableBlend();
		for (double[] body : BODIES) {
			int idx = (int) body[0];
			if (idx < 0 || idx >= rings.size()) {
				continue;
			}
			Ring ring = rings.get(idx);
			double a = ring.widthFrac() * w * 0.99 / 2.0;
			double b = a * RING_HEIGHT_RATIO;
			double revs = now / ring.periodSeconds();
			double ringAngle = (ring.angle0() + (ring.reverse() ? -revs : revs) * 360.0) % 360.0;
			double rad = Math.toRadians(ringAngle);
			double phi = (now / body[1] + body[2]) * 2.0 * Math.PI;
			double lx = a * Math.cos(phi), ly = b * Math.sin(phi);
			double px = cx + lx * Math.cos(rad) - ly * Math.sin(rad);
			double py = cy + lx * Math.sin(rad) + ly * Math.cos(rad);
			double core = w * body[3];
			drawRadial(guiGraphics, px, py, core * 3.2, body[4] * 0.28); // halo
			drawRadial(guiGraphics, px, py, core, body[4]);              // core
		}
		shade(1f, 1f, 1f, 1f);
	}

	/** Extra wordmark pass at a scale multiplier and alpha -- used for the breathing glow. */
	private static void drawWordmarkGlow(GuiGraphics guiGraphics, double inkCenterX, double inkCenterY,
										 double targetInkHeight, double scaleMul, float alpha) {
		if (wordmarkId == null || wmInkH <= 0 || alpha <= 0.001f) {
			return;
		}
		float scale = (float) (targetInkHeight / wmInkH * scaleMul);
		double icx = (wmInkX + wmInkW / 2.0) * scale;
		double icy = (wmInkY + wmInkH / 2.0) * scale;
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(inkCenterX - icx, inkCenterY - icy, 0);
		pose.scale(scale, scale, 1f);
		RenderSystem.enableBlend();
		shade(1f, 1f, 1f, alpha);
		guiGraphics.blit(wordmarkId, 0, 0, 0, 0, wmTexW, wmTexH, wmTexW, wmTexH);
		pose.popPose();
		shade(1f, 1f, 1f, 1f);
	}

	/** Shared frame dressing for the ring backdrop: edge vignette + corner brackets. */
	private static void drawFrame(GuiGraphics guiGraphics, int w, int h) {
		drawVignette(guiGraphics, w, h);
		drawCornerBrackets(guiGraphics, w, h);
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

	/**
	 * Thin L-brackets inset from each screen corner — an aerospace/instrument
	 * framing cue that matches the Michroma wordmark and adds "designed" edges
	 * without a hue. Drawn in the soft-strong stroke tone so they read as quiet
	 * registration marks, not a hard border.
	 */
	private static void drawCornerBrackets(GuiGraphics guiGraphics, int w, int h) {
		int inset = Math.max(10, (int) Math.round(w * 0.022));
		int len = Math.max(10, (int) Math.round(w * 0.018));
		int th = Math.max(1, (int) Math.round(w * 0.0015));
		int c = OriginTheme.STROKE_STRONG;
		// top-left
		guiGraphics.fill(inset, inset, inset + len, inset + th, c);
		guiGraphics.fill(inset, inset, inset + th, inset + len, c);
		// top-right
		guiGraphics.fill(w - inset - len, inset, w - inset, inset + th, c);
		guiGraphics.fill(w - inset - th, inset, w - inset, inset + len, c);
		// bottom-left
		guiGraphics.fill(inset, h - inset - th, inset + len, h - inset, c);
		guiGraphics.fill(inset, h - inset - len, inset + th, h - inset, c);
		// bottom-right
		guiGraphics.fill(w - inset - len, h - inset - th, w - inset, h - inset, c);
		guiGraphics.fill(w - inset - th, h - inset - len, w - inset, h - inset, c);
	}

	/**
	 * Wordmark with a per-letter staggered reveal: each glyph band (from
	 * wordmark.json) fades in and rises into place, offset by index, so the mark
	 * assembles left-to-right instead of popping in whole. Once every letter has
	 * finished (alpha 1, no offset) the bands tile seamlessly back into the full
	 * wordmark. Falls back to the plain whole-mark draw if the bands are missing.
	 */
	private static void drawWordmarkReveal(GuiGraphics guiGraphics, double inkCenterX, double inkCenterY,
										   double targetInkHeight, long elapsedMs) {
		if (wordmarkId == null || wmLetterX0 == null || wmInkH <= 0) {
			drawWordmark(guiGraphics, inkCenterX, inkCenterY, targetInkHeight);
			return;
		}
		float scale = (float) (targetInkHeight / wmInkH);
		double icx = (wmInkX + wmInkW / 2.0) * scale;
		double icy = (wmInkY + wmInkH / 2.0) * scale;
		double rise = wmInkH * 0.10;   // texture-px the letters drop in from
		double stagger = 55.0;         // ms between successive letters
		double dur = 300.0;            // ms per-letter fade/rise

		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(inkCenterX - icx, inkCenterY - icy, 0);
		pose.scale(scale, scale, 1f);
		RenderSystem.enableBlend();
		for (int i = 0; i < wmLetterX0.length; i++) {
			double lt = (elapsedMs - i * stagger) / dur;
			lt = Math.max(0.0, Math.min(1.0, lt));
			double eased = OriginTheme.easeOut(lt);
			if (eased <= 0.001) {
				continue;
			}
			int x0 = wmLetterX0[i];
			int bw = wmLetterX1[i] - x0;
			if (bw <= 0) {
				continue;
			}
			int yoff = (int) Math.round((1.0 - eased) * rise);
			shade(1f, 1f, 1f, (float) eased);
			guiGraphics.blit(wordmarkId, x0, yoff, (float) x0, 0f, bw, wmTexH, wmTexW, wmTexH);
		}
		shade(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	/** Draws the wordmark with its ink box centered on (inkCenterX, inkCenterY), ink scaled to targetInkHeight. Returns ink bottom (screen Y). */
	private static int drawWordmark(GuiGraphics guiGraphics, double inkCenterX, double inkCenterY, double targetInkHeight) {
		if (wordmarkId != null) {
			float scale = (float) (targetInkHeight / wmInkH);
			double icx = (wmInkX + wmInkW / 2.0) * scale;
			double icy = (wmInkY + wmInkH / 2.0) * scale;

			PoseStack pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(inkCenterX - icx, inkCenterY - icy, 0);
			pose.scale(scale, scale, 1f);
			RenderSystem.enableBlend();
			shade(1f, 1f, 1f, 1f);
			guiGraphics.blit(wordmarkId, 0, 0, 0, 0, wmTexW, wmTexH, wmTexW, wmTexH);
			pose.popPose();

			return (int) Math.round(inkCenterY + (wmInkH * scale) / 2.0);
		}

		// "ORIGIN" in Minecraft's own letters, centered on the point. Never
		// through mc.font: see BOOT FONT below for why.
		float scale = 4.0f;
		if (ensureBootFont()) {
			PoseStack pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(inkCenterX, inkCenterY, 0);
			pose.scale(scale, scale, 1f);
			drawBootText(guiGraphics, "ORIGIN", -4, OriginTheme.TEXT);
			pose.popPose();
		}
		return (int) Math.round(inkCenterY + 5 * scale);
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
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", "origin_boot_font");
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
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
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
