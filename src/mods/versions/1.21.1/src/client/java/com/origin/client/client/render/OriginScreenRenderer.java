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

// Shared Origin screen rendering, used by the loading screens
// (LoadingOverlayMixin and the world-load mixins), the main menu
// (TitleScreenMixin) and every out-of-world menu (ScreenBackgroundMixin).
//
//  - LOADING (clean splash, Will's mockup 2026-10-19): solid near-black with a
//    soft centre lift, the real logo, the ORIGIN wordmark (boot font: it paints
//    before fonts are baked), a thin bar and the real percent. World loading
//    shows this version's own panorama, blurred and dimmed, with the world's name.
//  - MAIN MENU: this version's own Mojang panorama, blurred and dimmed
//    (CLEAN_DIM_TITLE), the real logo (origin_logo.png) and the OFFICIAL wordmark
//    IMAGE (origin_wordmark.png, 11 GUI px tall) under it -- both still, baked
//    once per physical size (bakeBox) -- and the player chip (a soft plate, the
//    head baked at physical resolution with anti-aliased radius-2 corners, the
//    name in Minecraft's font). The buttons, the footer line colour and the
//    tooltip panel are OriginButtonRenderer's title skin, not drawn here.
//  - OTHER MENUS: the same panorama backdrop, no logo.
// The panorama is NOT available during the startup load (the cubemap's
// textures aren't loaded yet), which is why the loading splash is solid.
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

	/**
	 * True when renderLoading will paint the opaque Origin loading picture over the
	 * vanilla loading overlay this frame (the same two checks it makes), so vanilla's
	 * own logo and bar under it need not be drawn (LoadingOverlayCoverMixin, B-30).
	 */
	public static boolean coversLoadingOverlay() {
		return loadingHook && !broken && !com.origin.client.client.OriginStyle.vanillaMenus();
	}

	// Set by LoadingOverlayMixin's HEAD hook: its TAIL draw (renderLoading) is in place.
	// The cover lives in another mixin config, so without this a config that failed
	// to apply could leave the loading screen with neither picture.
	private static boolean loadingHook = false;

	public static void loadingHookLive() {
		loadingHook = true;
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
	// Loading-overlay frames drawn so far (render thread); only "is this the first" matters.
	private static int loadingFrames = 0;

	public static void renderLoading(GuiGraphics guiGraphics, float progress) {
		// The game is on screen now (Origin's scene, or vanilla's if ours broke):
		// Origin Launcher can lower its progress screen. Said from the SECOND loading
		// frame, i.e. once the first has been drawn and swapped (B-30: the process's
		// first GUI frame pays the driver's first-use shader costs, 0.9 s on CI's
		// software GL, and the launcher used to lower its cover at the start of it,
		// over a window not yet painted). OriginHandoff.tick still says it if no
		// second frame ever comes.
		if (loadingFrames++ > 0) {
			com.origin.client.client.OriginHandoff.loading();
		}
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
		long t0 = com.origin.client.client.OriginFrameLog.ENABLED ? System.nanoTime() : 0L;
		try {
			renderLoading0(guiGraphics, reloadProgress(progress), true);
		} catch (Throwable t) {
			fail(t);
		}
		if (t0 != 0L) {
			com.origin.client.client.OriginFrameLog.originLoadingDraw((System.nanoTime() - t0) / 1e6);   // dev only (B-30)
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
	 * Loading is done by then, so the bar shows full. With {@code fly} (the game's
	 * first load landing on an Origin title, {@link #logoFlightReady}) the logo
	 * doesn't dissolve: it moves, solid, from its loading spot onto the title's
	 * mark (DESIGN-SYSTEM 15.3; the title hides its own mark meanwhile,
	 * StartupTransition.titleMarkInFlight) and lands exactly where the title draws
	 * it. Without it the whole picture, logo included, simply fades (the fallback).
	 * False (nothing to cancel) when Origin rendering is off or this draw fails.
	 */
	public static boolean renderLoadingFade(GuiGraphics guiGraphics, float alpha, boolean fly) {
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
			// The title underneath already drives the ring field.
			renderLoading0(guiGraphics, 1f, false, !fly);
			if (fly) {
				// The flight shares the dissolve's curve: alpha = 1 - ease(t / REVEAL_MS).
				drawLogoFlight(guiGraphics, 1.0 - layerAlpha);
			}
			guiGraphics.flush();
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	// ---- the loading -> title hand-off: the logo moves onto the title's mark ----

	// Where the flight ends (the title's mark, TitleLayout) for the GUI size it was
	// worked out for, so TitleLayout isn't rebuilt every flight frame.
	private static int flightGw = -1, flightGh = -1;
	private static int flightToX, flightToY, flightToSize;
	// One linear-filtered copy of the logo, baked once at the larger of the two ends'
	// sizes and drawn scaled in between (baking every in-between size would bake a
	// texture per frame). Baked during the settle hold, before anything moves.
	private static ResourceLocation flightLogoId;
	private static int flightLogoPs;

	/**
	 * True when the hand-off can fly the logo: Origin rendering on, the Origin title
	 * (Main Menu Style), its backdrop working, the logo art loaded and the title
	 * showing a mark at this window size. Anything else keeps the plain fade.
	 */
	public static boolean logoFlightReady() {
		if (broken || backdropBroken || logoFailed || com.origin.client.client.OriginStyle.vanillaTitle()) {
			return false;
		}
		try {
			flightTarget();
			return flightToSize > 0;
		} catch (Throwable t) {
			return false;
		}
	}

	private static void flightTarget() {
		Minecraft mc = Minecraft.getInstance();
		int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
		if (gw != flightGw || gh != flightGh) {
			TitleLayout L = TitleLayout.of(gw, gh);
			flightGw = gw;
			flightGh = gh;
			flightToX = L.markCenterX;
			flightToY = L.markCenterY;
			flightToSize = L.markSize;
		}
	}

	/** Bakes the flight's logo copy ahead (settle hold): nothing is baked once it moves. */
	private static void prepareLogoFlight() {
		if (!logoFlightReady()) {
			return;
		}
		int gs = com.origin.client.client.gui.OriginSurface.scale();
		int ps = Math.max(LoadingLook.logoPhys(), flightToSize * gs);
		if (ps == flightLogoPs && flightLogoId != null) {
			return;
		}
		if (ps <= 0 || ps > 512) {
			return;
		}
		try {
			flightLogoId = registerBaked(LoadingPrep.boxFilter(logoSource(), ps, ps), "logo_flight_" + ps, true);
			flightLogoPs = ps;
		} catch (Throwable t) {
			flightLogoId = null;
			logoFailed = true;   // no art: the hand-off keeps the plain fade
			com.origin.client.OriginClient.LOGGER.warn("Origin logo flight bake failed; the loading screen fades instead", t);
		}
	}

	/**
	 * The logo at {@code p} (0 = its loading spot, 1 = the title's mark) of the way,
	 * solid. Its start is the box LoadingLook just laid out this frame. At p = 1 it is
	 * drawn through drawLogo, the title's own path, so the hand-over is pixel-exact.
	 */
	private static void drawLogoFlight(GuiGraphics g, double p) {
		flightTarget();
		p = clampD(p, 0.0, 1.0);
		double cx = LoadingLook.logoCxGui() + (flightToX - LoadingLook.logoCxGui()) * p;
		double cy = LoadingLook.logoCyGui() + (flightToY - LoadingLook.logoCyGui()) * p;
		double size = LoadingLook.logoSizeGui() + (flightToSize - LoadingLook.logoSizeGui()) * p;
		prepareLogoFlight();   // a no-op once baked (the hold normally did it)
		if (p >= 0.999) {
			drawLogo(g, flightToX, flightToY, flightToSize, 1f);
			return;
		}
		if (flightLogoId == null) {
			// No flight copy (a window too big for one bake): the plain scaled art,
			// never a bake per in-between size.
			OriginUi.logo(g, cx, cy, (int) Math.round(size), 1f);
			return;
		}
		int gs = com.origin.client.client.gui.OriginSurface.scale();
		double sizePs = size * gs;
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		pose.translate(cx * gs - sizePs / 2.0, cy * gs - sizePs / 2.0, 0.0);
		pose.scale((float) (sizePs / flightLogoPs), (float) (sizePs / flightLogoPs), 1f);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		g.blit(flightLogoId, 0, 0, 0f, 0f, flightLogoPs, flightLogoPs, flightLogoPs, flightLogoPs);
		pose.popPose();
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
		renderLoading0(guiGraphics, progress, drive, true);
	}

	private static void renderLoading0(GuiGraphics guiGraphics, float progress, boolean drive, boolean logo) {
		long t0 = com.origin.client.client.OriginFrameLog.partStart();
		// No ensureLoaded() here: the ring/grain/vignette art is for the menus and is
		// decoded and uploaded later, off this first frame (OriginAssets.uploadSlice ->
		// preloadMenuArt). The loading picture needs the logo and, for its caption, the
		// boot font, both a few KB (B-30: the first frame used to decode ~12 MB of
		// ring art here on the render thread).
		if (drive) {
			field(RingField.Mood.LOADING);
		}
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
		com.origin.client.client.OriginFrameLog.part("o:state", t0);
		// The loading picture (DESIGN-SYSTEM 15.3): flat color.bg, the official logo,
		// the accent rail and the stage line with the reload's real percentage.
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		LoadingLook.draw(guiGraphics, layerAlpha, LoadingLook.LOGO_AT, loadFill, startupCaption(loadFill), true, logo);
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

	// The startup caption ("Loading resources NN%") for a fill, rebuilt only when
	// the whole percent changes (no string built per frame while it holds still).
	// ASCII on purpose: it is drawn in the boot font before any translation or
	// Inter exists.
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
			// The hand-off's logo copy is baked here, where a slow frame shows nothing.
			prepareLogoFlight();
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	/**
	 * Full-screen loading/progress scene for the world-load screens
	 * (LevelLoadingScreen, ReceivingLevelScreen, ProgressScreen, GenericMessageScreen):
	 * the one loading picture (LoadingLook, DESIGN-SYSTEM 15.3) with the screen's own
	 * title as the caption. Drawn as a HEAD-cancel takeover, so it replaces vanilla's
	 * chunk map / dirt / progress clutter with one clean Origin scene.
	 */
	public static boolean renderLoadingScene(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title) {
		return renderLoadingScene(guiGraphics, title, -1f);
	}

	/**
	 * The world-loading scene with a real fraction done ({@code progress} 0..1,
	 * the chunk count LevelLoadingScreen reports), or -1 when the screen has no
	 * real measure - then the rail sweeps. Never a made-up percentage. A real
	 * fraction here is the spawn-area preparation, so the caption is vanilla's
	 * own "Preparing spawn area: N%" line.
	 */
	public static boolean renderLoadingScene(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title, float progress) {
		return renderLoadingScene(guiGraphics, title, progress, progress >= 0f);
	}

	/**
	 * As above; {@code spawnLine} false makes a real fraction a plain "title NN%"
	 * caption (ProgressScreen's stages report their own percentage).
	 */
	public static boolean renderLoadingScene(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title, float progress, boolean spawnLine) {
		// HEAD-cancel takeover: callers must only ci.cancel() when this
		// returns true, so a failure mid-draw falls back to the vanilla screen.
		if (broken) {
			return false;
		}
		try {
			renderLoadingScene0(guiGraphics, title, progress, spawnLine);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	// The world-loading scene's shown rail fill, gliding to each new count.
	// sceneFill < 0 = the rail is sweeping.
	private static double sceneFill = -1;
	// The rail's state for one world load (sceneRail): the frame count at the last
	// scene frame, the highest number reported so far (-1 = none yet), and when the
	// fill last moved.
	private static long sceneFrame = -1;
	private static double sceneTarget = -1, sceneAt;

	/**
	 * The world-loading scene's rail: exactly the number its caption shows, never a
	 * made-up one (judge 2026-10-25: "Preparing spawn area: 0%" over a rail at about
	 * 15%, from LoadBar's creep and its 75 % prep share, which suit the startup reload's
	 * lying fraction, not a chunk count the caption prints). The rail sweeps until the
	 * load's first reported number, then starts from empty and glides (FILL_TAU) to the
	 * highest number so far: never backward within one load, held through a stage that
	 * reports none ("Loading terrain"). A new world load is told apart by frames, as
	 * LoadBar.scene does: more than LoadBar.NEW_LOAD_FRAMES frames drawn without the scene.
	 * No allocation; render thread only.
	 */
	private static double sceneRail(long frame, float progress, double now) {
		if (sceneFrame < 0 || frame < sceneFrame || frame - sceneFrame - 1 > LoadBar.NEW_LOAD_FRAMES) {
			sceneTarget = -1;   // a new world load: sweep until it reports a number
			sceneFill = -1;
		}
		sceneFrame = frame;
		if (progress >= 0f) {   // false for NaN too: no number this frame
			double p = Math.min(1.0, progress);
			if (sceneTarget < 0) {
				sceneTarget = p;
				sceneFill = 0;      // the first number: glide up from an empty track
				sceneAt = now;
			} else {
				sceneTarget = Math.max(sceneTarget, p);
			}
		}
		if (sceneTarget < 0) {
			return -1;
		}
		double k = Math.exp(-Math.max(0.0, now - sceneAt) / FILL_TAU);
		sceneAt = now;
		return Math.max(sceneFill, sceneTarget + (sceneFill - sceneTarget) * k);
	}

	private static void renderLoadingScene0(GuiGraphics guiGraphics, net.minecraft.network.chat.Component title, float progress, boolean spawnLine) {
		Minecraft mc = Minecraft.getInstance();
		// The ring field is not drawn by the loading picture any more, but its scene
		// clock still times the rail, so the clock is read once per frame here.
		double now = RingField.sceneMs();
		// The rail shows the caption's own number (sceneRail): sweeping until the first.
		sceneFill = sceneRail(com.origin.client.client.hud.FrameStats.frames(), progress, now);
		if (com.origin.client.client.OriginFrameLog.ENABLED) {   // off: no title string built every frame
			com.origin.client.client.OriginFrameLog.sceneBar(title == null ? "" : title.getString(), progress, sceneFill);
		}
		// The caption is rebuilt only when the title, the whole percent or the
		// language changes (compared by reference: the screen keeps one Component).
		int pct = progress >= 0f ? Math.round(Math.max(0f, Math.min(1f, progress)) * 100f) : -1;
		String lang = mc.getLanguageManager().getSelected();   // a stored field: no allocation
		if (title != labelTitle || pct != labelPct || spawnLine != labelSpawn || !java.util.Objects.equals(lang, labelLang)) {
			labelTitle = title;
			labelPct = pct;
			labelSpawn = spawnLine;
			labelLang = lang;
			labelCaption = sceneCaption(title, pct, spawnLine);
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		LoadingLook.draw(guiGraphics, 1f, LoadingLook.LOGO_AT, sceneFill, labelCaption, false);
	}

	// The loading scene's caption and what it was built from.
	private static net.minecraft.network.chat.Component labelTitle;
	private static String labelLang;
	private static int labelPct = Integer.MIN_VALUE;
	private static boolean labelSpawn;
	private static String labelCaption = "";

	/** The stage line: vanilla's spawn line for the chunk load, else the screen's title (+ " NN%" if it reports one). */
	private static String sceneCaption(net.minecraft.network.chat.Component title, int pct, boolean spawnLine) {
		String text = title == null ? "" : title.getString().trim();
		if (spawnLine && pct >= 0) {
			return net.minecraft.client.resources.language.I18n.get("menu.preparingSpawn", pct);
		}
		if (pct >= 0 && !text.isEmpty()) {
			return text + " " + pct + "%";
		}
		return text;
	}

	/**
	 * Connecting to a server: the same loading picture as the other screens, the
	 * connection status as its caption. ConnectScreenMixin takes the draw over and
	 * calls this, then draws the screen's own Cancel button on top. The logo sits at
	 * exactly the spot every other loading screen uses (judge 2026-10-25: it used to be
	 * lifted to clear Cancel and jumped ~11 px between Connecting and the world load);
	 * Cancel is seated below the picture instead ({@link #connectCancelTop}).
	 *
	 * @param cancelTopGui the Cancel button's top edge in GUI px (unused: kept for the mixin's call)
	 */
	public static boolean renderConnecting(GuiGraphics guiGraphics, net.minecraft.network.chat.Component status, int cancelTopGui) {
		if (broken) {
			return false;
		}
		try {
			RingField.sceneMs();   // advances the scene clock that times the sweep
			if (status != connectStatus) {
				connectStatus = status;
				connectCaption = status == null ? "" : status.getString().trim();
			}
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			LoadingLook.draw(guiGraphics, 1f, LoadingLook.LOGO_AT, -1, connectCaption, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	/**
	 * Where Connecting's Cancel button goes (GUI px, its top edge): at 62 % of the height (A-Workbench-3), and
	 * always at least 12 GUI px under the loading picture's caption, so the picture never has to move for it;
	 * kept 8 GUI px clear of the bottom when the window allows.
	 */
	public static int connectCancelTop(int screenH, int buttonH) {
		double clearOfPicture = LoadingLook.logoCentreGui() + LoadingLook.belowCentreGui() + 12.0;
		int top = (int) Math.max(Math.round(screenH * 0.62) - buttonH / 2, Math.ceil(clearOfPicture));
		return Math.max(0, Math.min(top, screenH - buttonH - 8));
	}

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
	static void drawLogo(GuiGraphics g, double cx, double cy, double size, float alpha) {
		int gs = com.origin.client.client.gui.OriginSurface.scale();
		int ps = (int) Math.round(size * gs);
		long tl = com.origin.client.client.OriginFrameLog.partStart();
		ResourceLocation id = logoAt(ps);
		com.origin.client.client.OriginFrameLog.part("o:logo-tex", tl);
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

	/** The decoded logo master (kept for further sizes): the prep thread's, or read here. */
	private static NativeImage logoSource() throws Exception {
		if (logoSrc == null) {
			logoSrc = LoadingPrep.takeLogoSrc();
			if (logoSrc == null) {
				logoSrc = LoadingPrep.read(com.origin.client.client.gui.OriginUi.LOGO_PATH);
			}
		}
		return logoSrc;
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
			// The loading size was baked ahead on the loading-prep thread (B-30).
			NativeImage baked = LoadingPrep.takeLogoBaked(ps);
			if (com.origin.client.client.OriginFrameLog.ENABLED) {
				com.origin.client.client.OriginFrameLog.mark("logo prep " + (baked != null ? "hit" : "miss") + " at " + ps
						+ " px (prepared " + LoadingPrep.preparedLogoPs() + ")");
			}
			if (baked != null) {
				id = registerBaked(baked, "logo_px_" + ps, false);
				LOGO_SIZES.put(ps, id);
				return id;
			}
			id = bakeBox(logoSource(), ps, ps, "logo_px_" + ps);
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
	private static final java.util.Map<Integer, ResourceLocation> WORDMARK_SIZES = new java.util.HashMap<>();
	private static NativeImage wordmarkSrc;
	private static boolean wordmarkFailed;

	/** Draws the official wordmark image centred on (cx, cy), {@code heightGui} GUI px tall. False = not drawn (caller keeps the text fallback). */
	private static boolean drawWordmarkImage(GuiGraphics g, double cx, double cy, double heightGui, float alpha) {
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
			ResourceLocation id = WORDMARK_SIZES.get(ph);
			if (id == null) {
				id = bakeBox(wordmarkSrc, pw, ph, "wordmark_px_" + ph);
				WORDMARK_SIZES.put(ph, id);
			}
			PoseStack pose = g.pose();
			pose.pushPose();
			pose.scale(1f / gs, 1f / gs, 1f);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0f, Math.min(1f, alpha)));
			int px = (int) Math.round(cx * gs - pw / 2.0), py = (int) Math.round(cy * gs - ph / 2.0);
			g.blit(id, px, py, 0f, 0f, pw, ph, pw, ph);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			pose.popPose();
			return true;
		} catch (Throwable t) {
			wordmarkFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin wordmark bake failed; the title falls back to the boot-font text", t);
			return false;
		}
	}

	/** Premultiplied 4x4 box-filter downsample of {@code src} to dw x dh, registered as a texture. */
	private static ResourceLocation bakeBox(NativeImage src, int dw, int dh, String name) {
		return registerBaked(LoadingPrep.boxFilter(src, dw, dh), name, false);
	}

	/** Registers an already baked image as a texture (nearest, or linear for a scaled draw). */
	private static ResourceLocation registerBaked(NativeImage dst, String name, boolean linear) {
		DynamicTexture tex = new DynamicTexture(dst);
		tex.setFilter(linear, false);
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
		Minecraft.getInstance().getTextureManager().register(id, tex);
		return id;
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


	private static UUID titleSkinProfileId;
	private static Supplier<net.minecraft.client.resources.PlayerSkin> titleSkinSupplier;

	// The title's three orbit hairlines (A-Workbench-3): 0 / 60 / 120 deg, centred at 44.5 % of the
	// height, pills 0.952 x 0.342 of the height (half-extents 0.476 / 0.171), parchment ~10 %. Baked once per screen size.
	private static final OrbitHairlines TITLE_ORBITS = new OrbitHairlines(3, 0.0);

	/** Main menu: the faint orbit hairlines over the dimmed panorama, under the logo and buttons. */
	public static void renderTitleOrbits(GuiGraphics guiGraphics) {
		if (broken) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float h = mc.getWindow().getGuiScaledHeight();
		float w = mc.getWindow().getGuiScaledWidth();
		TITLE_ORBITS.draw(guiGraphics, w / 2f, h * 0.445f, h * 0.476f, h * 0.171f, OriginTheme.TITLE_ORBIT, 1f);
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
			// The REAL Origin logo (origin_logo.png) and the OFFICIAL wordmark image
			// (origin_wordmark.png) under it, both still; never redrawn or typed
			// (Will 2026-10-25). TitleLayout drops the logo, then the wordmark, when extra
			// rows need the room. The boot-font text is only the fail-soft if the image can't draw.
			if (L.markSize > 0 && !com.origin.client.client.StartupTransition.titleMarkInFlight()) {
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
	 * Main menu: the player (head + username) in the top-left, with no plate behind
	 * it (A-Workbench-3, Will 2026-10-25). Head via the skin manager (default skin shows first,
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
			// The player in the top-left corner (A-Workbench-3): the head and name straight
			// on the panorama, no plate behind them (Will, 2026-10-25: "remove the background
			// on the top left player icon"). Not clickable (there is no account menu here).
			// The head's left edge lines up with the footer text (both CORNER in).
			int x = TitleLayout.CORNER, y = TitleLayout.CORNER, chipH = TitleLayout.CORNER_SIZE;
			int head = 12, gap = 5;
			RenderSystem.enableBlend();
			RenderSystem.enableDepthTest();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			int hx = x, hy = y + (chipH - head) / 2;
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
				drawRoundedHead(guiGraphics, skin, hx, hy, head, 2);
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
				guiGraphics.drawString(font, name, tx, y + (chipH - 8) / 2, OriginTheme.TEXT, true);
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/**
	 * The chip's player head with rounded corners (DESIGN-SYSTEM 15.2: head 12 GUI px, radius 2).
	 * The face is vanilla's own PlayerFaceRenderer draw, repeated inside GL scissor bands of whole
	 * PHYSICAL pixels (GuiGraphics' scissor rounds to GUI px, too coarse for a 2 px corner): rows in
	 * the corner zone are inset by the circle's edge, rows between are one full-width band. Bands
	 * with the same inset merge, so it is a handful of immediate blits. Immediate draws only (1.21.1
	 * has no deferred GUI), so the scissor wraps them exactly.
	 */
	private static void drawRoundedHead(GuiGraphics g, net.minecraft.client.resources.PlayerSkin skin, int hx, int hy, int head, int radius) {
		if (drawBakedHead(g, skin, hx, hy, head, radius)) {
			return;
		}
		drawBandedHead(g, skin, hx, hy, head, radius);
	}

	// ---- the head, baked at physical resolution (anti-aliased corners) ----
	// The scissor bands below can only cut whole physical pixels, so a radius-2 corner stair-steps (judge,
	// chip_zoom). Instead the face (8x8 base + hat layer, exactly what PlayerFaceRenderer draws) is read back
	// from the skin's GL texture once, resampled NEAREST to head*guiScale physical pixels, and its alpha is
	// multiplied by an exact rounded-box coverage evaluated at physical-pixel centres (the smooth-ui-baked-masks
	// recipe). It is then blitted back 1:1, so one texel is one screen pixel. Re-baked only when the skin's
	// texture or the GUI scale changes (the real skin pops in async over the default one).
	private static final ResourceLocation HEAD_BAKED = ResourceLocation.fromNamespaceAndPath("originclient", "title_head_baked");
	private static ResourceLocation headSrc;
	private static int headGs = -1, headSize = -1;
	private static boolean headReady = false, headBakeFailed = false;

	private static boolean drawBakedHead(GuiGraphics g, net.minecraft.client.resources.PlayerSkin skin, int hx, int hy, int head, int radius) {
		if (headBakeFailed) {
			return false;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			int gs = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
			ResourceLocation src = skin.texture();
			int size = head * gs;
			if (!headReady || !src.equals(headSrc) || gs != headGs || size != headSize) {
				bakeHead(mc, src, size, radius * gs);
				headSrc = src;
				headGs = gs;
				headSize = size;
				headReady = true;
			}
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			g.blit(HEAD_BAKED, hx, hy, head, head, 0f, 0f, size, size, size, size);
			return true;
		} catch (Throwable t) {
			headBakeFailed = true;   // the banded head below takes over for the session
			com.origin.client.OriginClient.LOGGER.warn("Origin baked player head failed; using the banded head", t);
			return false;
		}
	}

	private static void bakeHead(Minecraft mc, ResourceLocation skinTex, int size, int r) {
		int id = mc.getTextureManager().getTexture(skinTex).getId();
		RenderSystem.bindTexture(id);
		int tw = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
		int th = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
		if (tw < 64 || th < 16) {
			throw new IllegalStateException("skin texture " + skinTex + " is " + tw + "x" + th);
		}
		int k = tw / 64;   // 1 for 64 px skins, 2 for HD
		NativeImage skinPx = new NativeImage(NativeImage.Format.RGBA, tw, th, false);
		NativeImage out = null;
		try {
			skinPx.downloadTexture(0, false);
			out = new NativeImage(NativeImage.Format.RGBA, size, size, false);
			double half = size / 2.0, inner = half - r;
			for (int py = 0; py < size; py++) {
				int fy = Math.min(7, py * 8 / size);
				for (int px = 0; px < size; px++) {
					int fx = Math.min(7, px * 8 / size);
					int base = skinPx.getPixelRGBA(8 * k + fx * k + k / 2, 8 * k + fy * k + k / 2);
					int hat = skinPx.getPixelRGBA(40 * k + fx * k + k / 2, 8 * k + fy * k + k / 2);
					// ABGR ints: alpha in the top byte. Hat over base, then the corner coverage into alpha.
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
					out.setPixelRGBA(px, py, (a << 24) | rgb);
				}
			}
			mc.getTextureManager().register(HEAD_BAKED, new DynamicTexture(out));   // takes ownership of `out`; replaces the old bake
			out = null;
		} finally {
			skinPx.close();
			if (out != null) {
				out.close();
			}
		}
	}

	private static void drawBandedHead(GuiGraphics g, net.minecraft.client.resources.PlayerSkin skin, int hx, int hy, int head, int radius) {
		com.mojang.blaze3d.platform.Window win = Minecraft.getInstance().getWindow();
		int gs = Math.max(1, (int) Math.round(win.getGuiScale()));
		int px = hx * gs, py = hy * gs, size = head * gs, r = Math.min(size / 2, radius * gs);
		int fbH = win.getHeight();
		g.flush();
		try {
			int bandStart = 0, bandInset = insetAt(0, size, r);
			for (int row = 1; row <= size; row++) {
				int inset = row < size ? insetAt(row, size, r) : -1;
				if (inset != bandInset) {
					RenderSystem.enableScissor(px + bandInset, fbH - (py + row), size - 2 * bandInset, row - bandStart);
					net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, hx, hy, head);
					bandStart = row;
					bandInset = inset;
				}
			}
		} finally {
			RenderSystem.disableScissor();
		}
	}

	/** Pixels cut from each side of physical row {@code row} (0 = top) of a size x size square with corner radius r. */
	private static int insetAt(int row, int size, int r) {
		int edge = Math.min(row, size - 1 - row);   // rows from the nearest top/bottom edge
		if (edge >= r) {
			return 0;
		}
		double dy = r - (edge + 0.5);
		return (int) Math.round(r - Math.sqrt(Math.max(0.0, r * (double) r - dy * dy)));
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
			// The shared ring alpha (OriginTheme.ORBIT_RING_ALPHA), not rings.json's opacity: the backdrop's rings
			// read at the title pills' level. The textures' own alpha still fades the outer rings.
			drawRingAt(guiGraphics, ring, w / 2.0 + RingField.offsetX(i, w), h / 2.0 + RingField.offsetY(i, w),
					ringAngle(i), ring.widthFrac() * w * 0.99, RING_HEIGHT_RATIO, OriginTheme.ORBIT_RING_ALPHA / 255f);
		}
		shade(1f, 1f, 1f, 1f);
	}

	/** One ring texture (a pill on 1.21.1: ring-N.png is a module fork) of on-screen width {@code major} (GUI units). */
	private static void drawRingAt(GuiGraphics guiGraphics, Ring ring, double cx, double cy, double angle,
								   double major, double aspect, float alpha) {
		if (alpha <= 0.002f) {
			return;
		}
		// The ring fills 0.9 of its square texture (uniform scale: the pill ends stay round).
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
		shade(0xF1 / 255f, 0xE9 / 255f, 0xD2 / 255f, alpha);   // parchment, like the title's pills
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
	private static int[] bootFontWidth; // per char code 0-255: glyph width in sheet px, -1 = no glyph (LoadingPrep.bootWidths)
	private static boolean bootFontTried;

	private static synchronized boolean ensureBootFont() {
		if (bootFontTried) {
			return bootFontId != null;
		}
		bootFontTried = true;
		try {
			// Decoded and measured ahead on the loading-prep thread (B-30); else here.
			NativeImage image = LoadingPrep.takeSheet();
			int[] widths = LoadingPrep.takeWidths();
			if (com.origin.client.client.OriginFrameLog.ENABLED) {
				com.origin.client.client.OriginFrameLog.mark("boot font prep " + (image != null && widths != null ? "hit" : "miss"));
			}
			if (image == null || widths == null) {
				if (image != null) {
					image.close();
				}
				image = LoadingPrep.read("/assets/minecraft/textures/font/ascii.png");
				widths = LoadingPrep.bootWidths(image);   // -1 = no glyph; the space advances 4 px like vanilla's
			}
			int cell = image.getWidth() / 16;
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
			total += ch < 256 && bootFontWidth[ch] >= 0 ? bootFontWidth[ch] + 1 : 0;
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
			if (w >= 0) {
				x += w + 1;
			}
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
	static void bootText(GuiGraphics g, String text, double x, double top, float scale, int tracking,
								 int argb, float anchor) {
		long tf = com.origin.client.client.OriginFrameLog.partStart();
		boolean font = ensureBootFont();
		com.origin.client.client.OriginFrameLog.part("o:font-tex", tf);
		if (text == null || text.isEmpty() || !font) {
			return;
		}
		// Widths in font px: a glyph advances (sheet width + 1) * unit, as drawBootText does.
		float unit = 8f / bootFontCell;
		float total = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			total += ch < 256 && bootFontWidth[ch] >= 0 ? (bootFontWidth[ch] + 1) * unit + tracking : 0;
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
		// Every glyph of the line in ONE draw (GuiGraphics.blit would be a draw per
		// glyph: ~40 a caption with its shadow). Same quads and UVs as blit's.
		org.joml.Matrix4f m = pose.last().pose();
		com.mojang.blaze3d.vertex.BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
				com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
		float tw = bootFontTexW, th = bootFontTexH, cell = bootFontCell;
		float x = 0;   // sheet px
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch >= 256) {
				continue;
			}
			int w = bootFontWidth[ch];
			if (w > 0) {
				float x0 = Math.round(x), x1 = x0 + w;
				float u0 = (ch % 16) * cell / tw, u1 = ((ch % 16) * cell + w) / tw;
				float v0 = (ch / 16) * cell / th, v1 = ((ch / 16) * cell + cell) / th;
				bb.addVertex(m, x0, 0, 0).setUv(u0, v0);
				bb.addVertex(m, x0, cell, 0).setUv(u0, v1);
				bb.addVertex(m, x1, cell, 0).setUv(u1, v1);
				bb.addVertex(m, x1, 0, 0).setUv(u1, v0);
			}
			if (w >= 0) {   // -1: no glyph in the sheet, takes no room (the space has its own width)
				x += w + 1 + tracking / unit;
			}
		}
		com.mojang.blaze3d.vertex.MeshData mesh = bb.build();
		if (mesh != null) {
			RenderSystem.setShaderTexture(0, bootFontId);
			RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
			com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
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
			// Decoded on the startup reload's workers (OriginAssets), so this only uploads.
			meta = com.origin.client.client.OriginAssets.json("/assets/originclient/textures/ui/rings.json");
			var arr = meta.getAsJsonArray("rings");
			for (int i = 0; i < arr.size(); i++) {
				JsonObject r = arr.get(i).getAsJsonObject();
				int index = r.get("index").getAsInt();
				ResourceLocation id = registerPrefetched(mc, "origin_ring_" + index,
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
			grainId = registerPrefetched(mc, "origin_grain", "/assets/originclient/textures/ui/grain.png");
		} catch (Exception e) {
			ringsFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin screen ring/grain textures failed to load; using plain background", e);
		}

		// Vignette loads independently of the rings: a failure just skips the
		// edge darkening, it doesn't disable the backdrop.
		try {
			Minecraft mc = Minecraft.getInstance();
			vignetteId = registerPrefetched(mc, "origin_vignette", "/assets/originclient/textures/ui/vignette.png");
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

	/**
	 * Uploads the menu art the startup reload already decoded (OriginAssets), decoding
	 * it here only if the prefetch hasn't reached it. The upload is the only work left
	 * on the render thread; it runs from OriginAssets.uploadSlice while the loading
	 * picture is up (preloadMenuArt), never on the first loading frame (B-30).
	 */
	private static ResourceLocation registerPrefetched(Minecraft mc, String name, String path) throws Exception {
		return upload(mc, name, com.origin.client.client.OriginAssets.image(path));
	}

	/**
	 * Loads the rings, grain and vignette now (one OriginAssets upload step), so the
	 * title's first frame doesn't. Idempotent: ensureLoaded() is a no-op afterwards.
	 */
	public static void preloadMenuArt() {
		ensureLoaded();
	}

	private static ResourceLocation registerTexture(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		return upload(mc, name, image);
	}

	private static ResourceLocation upload(Minecraft mc, String name, NativeImage image) {
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
