package com.origin.client.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix3x2fStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;

// Shared Origin screen rendering, used by the loading screen
// (LoadingOverlayMixin), the main menu (TitleScreenMixin) and every out-of-world
// menu (ScreenBackgroundMixin). The two surfaces deliberately differ:
//
//  - LOADING (startup, world loading, connecting): the one loading picture
//    (LoadingLook, 1.21.1's DESIGN-SYSTEM 15.3 layout): the real origin_logo.png,
//    a plain capsule progress bar under it and one caption line.
//  - Origin's own screens (Mod Menu, HUD editor, ...): charcoal, one soft glow,
//    grain and the vignette (renderTitleBackground).
// No rings anywhere: the only ring shapes in Origin's UI are inside
// origin_logo.png itself (Will, 2026-10-25: drawn look-alikes read as a fake logo).
//
// 1.21.6-1.21.8 (deferred GUI): every textured draw is a RenderPipelines.GUI_TEXTURED
// blit carrying its tint on the ARGB argument and every fill carries its own
// alpha (no setShaderColor), the pose is a Matrix3x2fStack, and layering follows
// submission order (nextStratum, no depth clear). The menu backdrop spends the
// frame's one blurBeforeThisStratum (see backdrop()).
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
	 * Startup resource-load screen: near-black with a soft centre lift, then the
	 * one loading picture (LoadingLook): the real Origin logo, the plain progress
	 * bar and the "Loading resources NN%" line (renderLoading0).
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
	 * Loading is done by then, so the bar shows full; the whole picture dissolves
	 * over the title. False (nothing to cancel) when Origin rendering is off or
	 * this draw fails.
	 */
	public static boolean renderLoadingFade(GuiGraphics guiGraphics, float alpha, boolean toTitle) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = Math.max(0f, Math.min(1f, alpha));
			// A fresh stratum so the loading layer sits over everything the title
			// beneath just submitted, its labels and icons included.
			// PER-VERSION (1.21.6-1.21.8): the deferred GUI layers by submission
			// order, so this stands in for 1.21.1's depth clear.
			guiGraphics.nextStratum();
			// The title shows the real Origin logo, still, so the loading layer simply
			// dissolves over it.
			renderLoading0(guiGraphics, 1f, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
		}
	}

	// Every colour in this file goes through tint() (blits) or fade() (fills):
	// they multiply in layerAlpha, so a whole scene can be drawn translucent (the
	// loading -> title dissolve) without each helper knowing. 1 everywhere else.
	// PER-VERSION (1.21.6-1.21.8): 1.21.1 does this with setShaderColor; this
	// era's deferred GUI has none, so the alpha rides each draw's own ARGB.
	private static float layerAlpha = 1f;

	/** White at alpha {@code a} times layerAlpha: a blit's per-vertex ARGB. */
	private static int tint(float a) {
		return ARGB.white(Math.max(0f, Math.min(1f, a * layerAlpha)));
	}

	private static void renderLoading0(GuiGraphics guiGraphics, float progress, boolean drive) {
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
		// The one loading picture (LoadingLook, 1.21.1's): its own opaque color.bg backdrop,
		// the real logo, the progress bar and the stage line with the reload's real
		// percentage, in the boot font (no font text during the initial reload).
		LoadingLook.draw(guiGraphics, layerAlpha, LoadingLook.LOGO_AT, loadFill, startupCaption(loadFill), true);
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
	public static boolean renderLoadingHold(GuiGraphics guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = 1f;
			// A fresh stratum so the loading picture covers everything the screen
			// beneath just submitted (PER-VERSION 1.21.6-1.21.8: the deferred GUI
			// layers by submission order; this stands in for 1.21.1's depth clear).
			guiGraphics.nextStratum();
			renderLoading0(guiGraphics, 1f, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
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
		// The scene clock times the bar, so it is read once per frame here.
		double now = SceneClock.sceneMs();
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
	 * connection status as its caption. ConnectScreenMixin draws it in place of
	 * vanilla's renderBackground (PER-VERSION 1.21.6-1.21.8: the backdrop runs before render), and
	 * vanilla's render then draws the screen's own Cancel button on top, its status line dropped. The logo sits at
	 * exactly the spot every other loading screen uses (judge 2026-10-25: it used to be
	 * lifted to clear Cancel and jumped ~11 px between Connecting and the world load);
	 * Cancel is seated below the picture instead ({@link #connectCancelTop}).
	 */
	public static boolean renderConnecting(GuiGraphics guiGraphics, net.minecraft.network.chat.Component status) {
		if (broken) {
			return false;
		}
		try {
			SceneClock.sceneMs();   // advances the scene clock that times the sweep
			if (status != connectStatus) {
				connectStatus = status;
				connectCaption = status == null ? "" : status.getString().trim();
			}
			LoadingLook.draw(guiGraphics, 1f, LoadingLook.LOGO_AT, -1, connectCaption, false);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** A loading screen's Cancel width (GUI px): about the caption's own width, so the button sits with the
	 *  picture instead of spanning past it (judge 2026-10-27: a 200 px slab under a 200 design px bar). */
	public static final int LOADING_CANCEL_W = 120;

	/**
	 * Where a loading screen's Cancel goes (GUI px, its top edge): one picture step (the logo-to-bar gap,
	 * LoadingLook.stepGui) under the caption, so logo, bar, caption and Cancel share one rhythm and the
	 * picture never moves for it; kept 8 GUI px clear of the bottom when the window allows.
	 */
	public static int connectCancelTop(int screenH, int buttonH) {
		double top = LoadingLook.logoCentreGui() + LoadingLook.belowCentreGui() + LoadingLook.stepGui();
		return Math.max(0, Math.min((int) Math.ceil(top), screenH - buttonH - 8));
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

	// ---- clean backdrop: this version's own panorama ----

	// Separate latch from `broken`: a failed panorama/blur call (e.g. the
	// ScreenInvoker mixin didn't apply) only hands the backdrop back to vanilla.
	private static volatile boolean backdropBroken = false;
	private static boolean drawingPanorama = false;

	/** True while backdrop() itself is drawing the panorama (render thread only). */
	public static boolean drawingOwnPanorama() {
		return drawingPanorama;
	}

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
				// PER-VERSION (1.21.6-1.21.8): the title's blur+dim hangs off
				// Screen.renderPanorama's TAIL (ScreenBackgroundMixin); this flag keeps
				// that hook out of Origin's own panorama draws, so nothing doubles.
				drawingPanorama = true;
				try {
					inv.originclient$renderPanorama(g, partialTick);
				} finally {
					drawingPanorama = false;
				}
			}
			// PER-VERSION (1.21.6-1.21.8): the blur is a request on the deferred GUI
			// (blurBeforeThisStratum): it blurs every stratum up to and including the
			// current one, at most once a frame. So the panorama gets it, then a fresh
			// stratum keeps the dim, vignette, chip and widgets sharp (1.21.1's order).
			// A second blur this frame (another mod placed it first) is not a failure.
			// Cheap guard first: GuiRenderState.firstStratumAfterBlur stays MAX_VALUE until a blur is
			// placed, so another mod's blur this frame is skipped without a thrown exception.
			// The catch stays as the backstop (e.g. the accessor not applying).
			if (!(g.guiRenderState instanceof com.origin.client.client.mixin.GuiRenderStateAccessor acc)
					|| acc.originclient$firstStratumAfterBlur() == Integer.MAX_VALUE) {
				try {
					inv.originclient$renderBlurredBackground(g);
				} catch (IllegalStateException alreadyBlurred) {
					// the frame's one blur is already placed
				}
			}
			g.nextStratum();
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
		ResourceLocation id = logoAt(ps);
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
							int c = logoSrc.getPixel(tx, ty);   // ARGB (1.21.2+); the logo is white, so B = R
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
			DynamicTexture tex = new DynamicTexture(() -> "originclient:logo_px", dst);
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

	// The official wordmark (textures/ui/origin_wordmark.png, byte-identical to the
	// launcher's Assets/Brand/wordmark.png) gets the same one-time box-filter bake per
	// physical height as the logo, so it stays crisp at any GUI scale. Never redrawn or re-typed.
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
				id = registerNearest(boxFilter(wordmarkSrc, pw, ph), "wordmark_px_" + ph);
				WORDMARK_SIZES.put(ph, id);
			}
			// 1.21.6-1.21.8: one 1:1 blit at physical resolution, the alpha on the blit's ARGB (no setShaderColor).
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.scale(1f / gs, 1f / gs);
			int px = (int) Math.round(cx * gs - pw / 2.0), py = (int) Math.round(cy * gs - ph / 2.0);
			g.blit(RenderPipelines.GUI_TEXTURED, id, px, py, 0f, 0f, pw, ph, pw, ph, ARGB.white(Math.max(0f, Math.min(1f, alpha * layerAlpha))));
			pose.popMatrix();
			return true;
		} catch (Throwable t) {
			wordmarkFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin wordmark bake failed; the title falls back to the boot-font text", t);
			return false;
		}
	}

	/** Premultiplied 4x4 box-filter downsample of {@code src} to dw x dh (any aspect; ARGB in, ARGB out). */
	private static NativeImage boxFilter(NativeImage src, int dw, int dh) {
		int sw = src.getWidth(), sh = src.getHeight();
		NativeImage dst = new NativeImage(dw, dh, true);
		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				double aSum = 0, c0 = 0, c1 = 0, c2 = 0;
				for (int sy = 0; sy < 4; sy++) {
					int ty = Math.min(sh - 1, (int) ((y + (sy + 0.5) / 4.0) * sh / dh));
					for (int sx = 0; sx < 4; sx++) {
						int tx = Math.min(sw - 1, (int) ((x + (sx + 0.5) / 4.0) * sw / dw));
						int c = src.getPixel(tx, ty);
						double a = ((c >>> 24) & 0xFF) / 255.0;
						aSum += a;
						c0 += a * (c & 0xFF);
						c1 += a * ((c >>> 8) & 0xFF);
						c2 += a * ((c >>> 16) & 0xFF);
					}
				}
				int a = (int) Math.round(aSum / 16.0 * 255);
				int v0 = aSum > 0 ? (int) Math.round(Math.min(255.0, c0 / aSum)) : 0;
				int v1 = aSum > 0 ? (int) Math.round(Math.min(255.0, c1 / aSum)) : 0;
				int v2 = aSum > 0 ? (int) Math.round(Math.min(255.0, c2 / aSum)) : 0;
				dst.setPixel(x, y, (a << 24) | (v2 << 16) | (v1 << 8) | v0);
			}
		}
		return dst;
	}

	/** Registers a baked image as a nearest-sampled texture (blitted 1:1, so one texel is one screen pixel). */
	private static ResourceLocation registerNearest(NativeImage img, String name) {
		DynamicTexture tex = new DynamicTexture(() -> "originclient:" + name, img);
		tex.setFilter(false, false);
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
		int d = (int) Math.round(Math.max(w, h) * 0.9);
		g.blit(RenderPipelines.GUI_TEXTURED, radialGlowId, (w - d) / 2, (int) Math.round(h * 0.44 - d / 2.0), 0f, 0f, d, d,
				RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, tint(alpha));
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
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs));
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
		pose.popMatrix();
	}

	private static void fillEdge(GuiGraphics guiGraphics, int x, int y0, int y1, double cover, int argb) {
		int a = (int) Math.round((argb >>> 24) * Math.min(1.0, cover));
		if (a > 0) {
			guiGraphics.fill(x, y0, x + 1, y1, (a << 24) | (argb & 0xFFFFFF));
		}
	}

	/**
	 * Origin's own screens' background (Mod Menu, HUD editor, Origin windows):
	 * charcoal, one soft glow, the grain tiles and the vignette. No rings: the only
	 * ring shapes in Origin's UI are inside origin_logo.png (Will, 2026-10-25). Returns
	 * true only if the Origin backdrop actually drew; callers that cancel vanilla's own
	 * backdrop must key off this. Cheap on purpose: no cubemap, no blur pass.
	 */
	public static boolean renderTitleBackground(GuiGraphics guiGraphics) {
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
	private static void drawMenuGlow(GuiGraphics guiGraphics, int w, int h) {
		if (radialGlowId == null) {
			return;
		}
		int d = (int) Math.round(w * 1.1);
		// The jade tint rides the per-blit ARGB: 0x12 = 7% alpha (1.21.1's 0.07f), RGB 0x4BC8AE.
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, radialGlowId, (int) Math.round(w * 0.70 - d / 2.0),
				(int) Math.round(h * 0.15 - d / 2.0), 0f, 0f, d, d, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX,
				fade(0x124BC8AE));
	}

	/**
	 * Main menu: the website's two-layer cursor spotlight, drawn over the
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
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.translate((float) (cx - icx), (float) (cy - icy));
		pose.scale(scale, scale);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, texW, texH, texW, texH, tint(alpha));
		pose.popMatrix();
	}

	/**
	 * Main menu: the player (head + username) in the top-left, with no plate behind
	 * it (A-Workbench-3, Will 2026-10-25). Head via the skin manager (default skin shows first,
	 * the real one pops in async); if the head can't be resolved the chip falls
	 * back to the Origin logo, and if the whole chip throws it flips the
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
	 * The chip's player head with rounded corners (DESIGN-SYSTEM 15.2: head 12 GUI px, radius 2), baked at
	 * physical resolution so the corners are anti-aliased; vanilla's square face is the fallback.
	 */
	private static void drawRoundedHead(GuiGraphics g, net.minecraft.client.resources.PlayerSkin skin, int hx, int hy, int head, int radius) {
		if (!drawBakedHead(g, skin, hx, hy, head, radius)) {
			net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, hx, hy, head);
		}
	}

	// ---- the head, baked at physical resolution (anti-aliased corners) ----
	// The face (8x8 base + hat layer, exactly what PlayerFaceRenderer draws) is resampled NEAREST to
	// head*guiScale physical pixels and its alpha multiplied by an exact rounded-box coverage at physical-pixel
	// centres (the smooth-ui-baked-masks recipe), then blitted back 1:1. Re-baked only when the skin texture or
	// the GUI scale changes (the real skin pops in async over the default one).
	// 1.21.6-1.21.8: there is no GL readback in the deferred GUI, so the skin's pixels come from the CPU copy a
	// downloaded skin keeps (SkinTextureDownloader registers a DynamicTexture; getPixels() is non-null) or,
	// for a built-in default skin, straight from its PNG in the resource manager.
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
			int gs = Math.max(1, com.origin.client.client.gui.OriginSurface.scale());
			ResourceLocation src = skin.texture();
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
			g.blit(RenderPipelines.GUI_TEXTURED, HEAD_BAKED, hx * gs, hy * gs, 0f, 0f, size, size, size, size, ARGB.white(layerAlpha));
			pose.popMatrix();
			return true;
		} catch (Throwable t) {
			headBakeFailed = true;   // vanilla's square face takes over for the session
			com.origin.client.OriginClient.LOGGER.warn("Origin baked player head failed; using the square head", t);
			return false;
		}
	}

	private static void bakeHead(Minecraft mc, ResourceLocation skinTex, int size, int r) throws Exception {
		NativeImage skinPx = null;
		boolean owned = false;
		if (mc.getTextureManager().getTexture(skinTex) instanceof DynamicTexture dyn && dyn.getPixels() != null) {
			skinPx = dyn.getPixels();   // the downloaded skin's CPU copy: read only, never closed here
		} else {
			try (InputStream in = mc.getResourceManager().getResource(skinTex).orElseThrow().open()) {
				skinPx = NativeImage.read(in);
				owned = true;
			}
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
					// ARGB ints in this era: alpha in the top byte. Hat over base, then the corner coverage into alpha.
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
			DynamicTexture tex = new DynamicTexture(() -> "originclient:title_head_baked", out);   // takes ownership of `out`
			tex.setFilter(false, false);
			mc.getTextureManager().register(HEAD_BAKED, tex);   // replaces (and releases) the previous bake
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
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs));
		// THE "TV static" fix (1.21.2+): a setShaderColor alpha around these blits
		// is never seen by the batched flush, so the noise would draw at FULL
		// opacity over the whole screen. The tint rides the per-blit ARGB instead.
		int grainTint = tint(0.028f);
		for (int y = 0; y < realH; y += tile) {
			for (int x = 0; x < realW; x += tile) {
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, grainId, x, y, 0f, 0f, tile, tile, tile, tile, grainTint);
			}
		}
		pose.popMatrix();
	}

	/** Full-screen edge vignette (the 1024px texture stretched to the GUI size). */
	private static void drawVignette(GuiGraphics guiGraphics, int w, int h) {
		if (vignetteId == null) {
			return;
		}
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) w / VIGNETTE_TEX, (float) h / VIGNETTE_TEX);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX,
				tint(1f));
		pose.popMatrix();
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
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", "origin_boot_font");
			DynamicTexture texture = new DynamicTexture(() -> "originclient:origin_ui", image);
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
		// Batched-gui era: the tint rides the per-blit ARGB (setShaderColor has no effect here).
		int color = fade(argb);
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale(unit, unit);
		int x = -total / 2;
		int top = Math.round(y / unit);
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch >= 256) {
				continue;
			}
			int w = bootFontWidth[ch];
			if (w > 0) {
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, bootFontId, x, top, (float) ((ch % 16) * bootFontCell), (float) ((ch / 16) * bootFontCell),
						w, bootFontCell, bootFontTexW, bootFontTexH, color);
			}
			x += w + 1;
		}
		pose.popMatrix();
	}

	/**
	 * Boot-font text (Minecraft's own ascii.png glyphs, see BOOT FONT) at GUI
	 * (x, top), {@code scale}x, {@code tracking} extra font px between letters,
	 * with vanilla's drop shadow. {@code anchor}: 0 = x is the left edge, 0.5 =
	 * centre, 1 = right edge. Draws nothing if the sheet couldn't be read.
	 */
	static void bootText(GuiGraphics g, String text, double x, double top, float scale, int tracking,
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
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) (Math.round(x - total * scale * anchor)), (float) (Math.round(top)));
		pose.scale(scale, scale);
		// Vanilla's shadow: the same text one font px down-right at a quarter brightness.
		int shadow = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
		bootGlyphs(g, text, 1, 1, tracking, unit, shadow);
		bootGlyphs(g, text, 0, 0, tracking, unit, argb);
		pose.popMatrix();
	}

	private static void bootGlyphs(GuiGraphics g, String text, int ox, int oy, int tracking, float unit, int argb) {
		// Batched-gui era: the tint rides the per-blit ARGB (setShaderColor has no effect here).
		int color = fade(argb);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) (ox), (float) (oy));
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
						w, bootFontCell, bootFontTexW, bootFontTexH, color);
			}
			x += w + 1 + tracking / unit;
		}
		pose.popMatrix();
	}

	/** Draws the progress bar centered on cx at barTop, with the given width/height. */
	private static void drawBar(GuiGraphics guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;

		// Track brighter than the hairline stroke: at 8% white on the near-black
		// background the unfilled track was invisible in-game, so only the fill
		// showed and the bar read as the wrong (too small) size. ~16% matches how
		// the mockup's track actually reads on screen.
		guiGraphics.fill(bx, by, bx + barW, by + barH, fade(0x29FFFFFF));              // track
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
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
		DynamicTexture texture = new DynamicTexture(() -> "originclient:origin_ui", image);
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
