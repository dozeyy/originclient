package com.origin.client.client.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
	private static final int BG_COLOR = OriginTheme.BG;

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
	// The startup bar's shown fill (-1 = none yet) and the scene time it was set:
	// it glides to the reload's real progress instead of stepping with it.
	private static double loadFill = -1;
	private static double loadFillAt = 0;
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

	// one animated brand ring (Java-8 final class, was a record).
	private static final class Ring {
		private final ResourceLocation texture; private final double widthFrac; private final float opacity;
		private final double angle0; private final double periodSeconds; private final boolean reverse;
		Ring(ResourceLocation texture, double widthFrac, float opacity,
			 double angle0, double periodSeconds, boolean reverse) {
			this.texture = texture; this.widthFrac = widthFrac; this.opacity = opacity;
			this.angle0 = angle0; this.periodSeconds = periodSeconds; this.reverse = reverse;
		}
		ResourceLocation texture() { return texture; }
		double widthFrac() { return widthFrac; }
		float opacity() { return opacity; }
		double angle0() { return angle0; }
		double periodSeconds() { return periodSeconds; }
		boolean reverse() { return reverse; }
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
	public static void renderLoading(Gfx guiGraphics, float progress) {
		// The game is on screen now (Origin's scene, or vanilla's if ours
		// broke): Origin Launcher can lower its progress screen.
		com.origin.client.client.OriginHandoff.loading();
		// Drawn at TAIL over vanilla's overlay, so skipping when broken simply
		// reveals the vanilla loading screen -- no cancel to unwind.
		if (broken) {
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
		Object overlay = Minecraft.getInstance().getOverlay();
		if (overlay instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor) {
			net.minecraft.server.packs.resources.ReloadInstance reload =
					((com.origin.client.client.mixin.loading.LoadingOverlayAccessor) overlay).originclient$reload();
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
			if (toTitle) {
				MarkMotion.startFlight();
			}
			boolean fly = toTitle && MarkMotion.flightStarted();
			// The title underneath already drives the ring field: don't pull it back.
			renderLoading0(guiGraphics, 1f, false, !fly);
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
			RenderSystem.color4f(1f, 1f, 1f, 1f);
		}
	}

	// Every shader colour in this file goes through shade(): it multiplies in
	// layerAlpha, so a whole scene can be drawn translucent (the loading -> title
	// dissolve) without each helper knowing. On 1.16.5 fills ignore the GL
	// colour, so they take it through fillLayer. 1 everywhere else.
	private static float layerAlpha = 1f;

	private static void shade(float r, float g, float b, float a) {
		RenderSystem.color4f(r, g, b, a * layerAlpha);
	}

	// 1.16.5 is fixed-function GL: a fill's colour lives in its vertices, which
	// override the GL colour that shade() sets, so layerAlpha is multiplied into
	// each fill's own alpha instead (a no-op at the usual layerAlpha of 1).
	private static void fillLayer(Gfx g, int x1, int y1, int x2, int y2, int argb) {
		if (layerAlpha < 1f) {
			argb = (Math.round((argb >>> 24) * layerAlpha) << 24) | (argb & 0xFFFFFF);
		}
		g.fill(x1, y1, x2, y2, argb);
	}

	private static void renderLoading0(Gfx guiGraphics, float progress, boolean drive, boolean drawMark) {
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
		// A new reload (resource pack change) starts the bar over; otherwise the
		// fill only ever glides forward. The dissolve passes 1: the bar finishes
		// its glide while the title appears instead of jumping full.
		loadFill = loadFill < 0 || clamped < loadFill - 0.25 ? clamped
				: Math.max(loadFill, clamped + (loadFill - clamped) * Math.exp(-(scene - loadFillAt) / FILL_TAU));
		loadFillAt = scene;

		// No orbit rings here (Will 2026-10-18): the moving mark is the scene.
		fillLayer(guiGraphics, 0, 0, w, h, BG_COLOR);
		drawGrain(guiGraphics, w, h);
		drawVignette(guiGraphics, w, h);

		// Mark above, then the wordmark (building in letter by letter), then the
		// bar, as one group centred a touch above the middle. All-caps "ORIGIN"
		// has no descender, so its ink height IS the cap height.
		double size = Math.min(h * 0.36, w * 0.30);
		double markCy = h * 0.40;
		if (drawMark) {
			MarkMotion.drawLoading(guiGraphics, w / 2.0, markCy, size, scene - loadStartScene, layerAlpha);
		}
		double inkH = fitInkHeight(h * 0.07, w, 0.6);
		double wordCy = markCy + size * 0.5 + h * 0.06 + inkH / 2.0;
		drawWordmarkReveal(guiGraphics, w / 2.0, wordCy, inkH, elapsed);

		double dispW = (wordmarkId != null && wmInkH > 0) ? wmInkW * (inkH / wmInkH) : w * 0.16;
		int barW = (int) Math.round(dispW);
		int barH = Math.max(2, (int) Math.round(h * 0.008));
		int barTop = (int) Math.round(wordCy + inkH * 1.15);
		drawBar(guiGraphics, w / 2.0, barTop, barW, barH, (float) loadFill);

		// No text on this screen: it renders DURING the initial resource reload,
		// before any font glyphs are baked, so font-drawn strings can only ever
		// show missing-glyph boxes here (vanilla's overlay avoids text for the
		// same reason). The animated wordmark + bar carry the scene — which is
		// also the original mock's look. Corner brackets on top of everything.
		drawCornerBrackets(guiGraphics, w, h);
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
	private static double sceneFillAt = 0;

	private static void renderLoadingScene0(Gfx guiGraphics, net.minecraft.network.chat.Component title, float progress) {
		ensureLoaded();
		// From a menu this swings the slowed, leaning rings back to the centred
		// loading orbit (RingField eases it, so it reads as one motion).
		field(RingField.Mood.LOADING);
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		fillLayer(guiGraphics, 0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		drawFrame(guiGraphics, w, h);

		// The fill glides to each new count (chunk counts arrive in steps), and
		// a new scene with no count goes back to the sweep.
		double now = RingField.sceneMs();
		if (progress < 0f) {
			sceneFill = -1;
		} else {
			double target = Math.max(0.0, Math.min(1.0, progress));
			sceneFill = sceneFill < 0 || target < sceneFill ? target
					: target + (sceneFill - target) * Math.exp(-(now - sceneFillAt) / FILL_TAU);
		}
		sceneFillAt = now;
		drawSceneText(guiGraphics, mc.font, w, h, title == null ? "" : title.getString().trim(), sceneFill);
	}

	/** The world-loading scene's centred title + bar (fill < 0: sweeping). */
	private static void drawSceneText(Gfx guiGraphics, Font font, int w, int h, String s, double fill) {
		int barW = Math.max(80, (int) (w * 0.30));
		int barH = Math.max(2, (int) (h * 0.010));
		float titleScale = 1.5f;
		int titleH = (int) Math.ceil(font.lineHeight * titleScale);
		double gap = Math.max(8.0, h * 0.045);

		boolean hasTitle = !s.isEmpty();
		double groupH = (hasTitle ? titleH + gap : 0) + barH;
		double groupTop = h / 2.0 - groupH / 2.0;

		if (hasTitle) {
			PoseStack pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(w / 2.0, groupTop, 0);
			pose.scale(titleScale, titleScale, 1f);
			guiGraphics.drawString(font, s, -font.width(s) / 2, 0, OriginTheme.TEXT, false);
			pose.popPose();
		}
		int barTop = (int) Math.round(groupTop + (hasTitle ? titleH + gap : 0));
		if (fill < 0) {
			drawIndeterminateBar(guiGraphics, w / 2.0, barTop, barW, barH);
		} else {
			drawBar(guiGraphics, w / 2.0, barTop, barW, barH, (float) fill);
		}
	}

	/**
	 * Just the indeterminate bar, placed just below screen center -- for
	 * screens kept on their vanilla render (ConnectScreen: its Cancel button
	 * and status text stay, the Origin menu background comes from
	 * ScreenBackgroundMixin, and this adds the bar on top).
	 */
	public static void renderConnectingBar(Gfx guiGraphics) {
		// TAIL-additive over the vanilla connect screen; skip when broken.
		if (broken) {
			return;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			int barW = Math.max(80, (int) (w * 0.30));
			int barH = Math.max(2, (int) (h * 0.010));
			drawIndeterminateBar(guiGraphics, w / 2.0, (int) (h * 0.60), barW, barH);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** A calm sweeping segment (smooth ping-pong) over the standard track. */
	private static void drawIndeterminateBar(Gfx guiGraphics, double cx, int barTop, int barW, int barH) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;
		fillLayer(guiGraphics, bx, by, bx + barW, by + barH, 0x29FFFFFF); // track
		int segW = Math.max(8, (int) (barW * 0.32));
		int travel = Math.max(1, barW - segW);
		double t = (RingField.sceneMs() % 1800.0) / 1800.0;
		double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI); // smooth 0->1->0
		double segX = bx + eased * travel;
		fillSmooth(guiGraphics, segX - 1, by - 1, segX + segW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW);
		fillSmooth(guiGraphics, segX, by, segX + segW, by + barH, OriginTheme.ACCENT);
	}

	/**
	 * fill() with a horizontal span placed to a fraction of a real pixel: the
	 * whole pixels in one fill, each partly covered edge pixel at its share of
	 * the alpha. A moving bar end then slides smoothly instead of stepping a
	 * whole GUI unit (guiScale screen pixels) at a time. Vertical edges snap to
	 * real pixels. Drawn in real pixels, the same way drawGrain is.
	 */
	private static void fillSmooth(Gfx guiGraphics, double x0, double y0, double x1, double y1, int argb) {
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
				fillLayer(guiGraphics, i0, py0, i1, py1, argb);
			}
			fillEdge(guiGraphics, i0 - 1, py0, py1, i0 - px0, argb);
			fillEdge(guiGraphics, i1, py0, py1, px1 - i1, argb);
		}
		pose.popPose();
	}

	private static void fillEdge(Gfx guiGraphics, int x, int y0, int y1, double cover, int argb) {
		int a = (int) Math.round((argb >>> 24) * Math.min(1.0, cover));
		if (a > 0) {
			fillLayer(guiGraphics, x, y0, x + 1, y1, (a << 24) | (argb & 0xFFFFFF));
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
	public static boolean renderTitleBackground(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			field(RingField.Mood.MENU);
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			fillLayer(guiGraphics, 0, 0, w, h, BG_COLOR);
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


	private static void drawRadial(Gfx guiGraphics, double cx, double cy, double diameter, double alpha) {
		int d = Math.max(2, (int) Math.round(diameter));
		shade(1f, 1f, 1f, (float) alpha);
		guiGraphics.blit(radialGlowId, (int) Math.round(cx - d / 2.0), (int) Math.round(cy - d / 2.0),
				d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
	}

	// False from the startup flight's first frame until the title draws its mark
	// itself: while the loading overlay is still up, the dissolve layer draws the
	// flying mark over the title.
	private static boolean markHandedOff = true;

	/**
	 * Main menu: the Origin mark above the compact action stack. After the game's
	 * first load it is the loading screen's mark finishing its flight here
	 * (MarkMotion); any other time it is simply there.
	 */
	public static boolean renderTitleWordmark(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			TitleLayout L = TitleLayout.of(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
			if (!markHandedOff) {
				if (mc.getOverlay() instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
					return true;   // the dissolve layer draws it, over this
				}
				markHandedOff = true;
			}
			if (MarkMotion.flying()) {
				MarkMotion.drawFlight(guiGraphics, L.markCenterX, L.markCenterY, L.markSize);
			} else {
				MarkMotion.drawFormed(guiGraphics, L.markCenterX, L.markCenterY, L.markSize, 1.0);
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
	 * inside the frame. Identity presence, like premium launchers. Head via the
	 * skin manager (default skin shows first, the real one pops in async); if the
	 * head can't be resolved the chip falls back to the Origin ring mark, and if
	 * the whole chip throws it flips the renderer to vanilla like any Origin draw.
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
			int w = mc.getWindow().getGuiScaledWidth();
			int head = 18;
			int padX = 8, padY = 6, gap = 8;
			int chipH = head + padY * 2;
			int x = Math.max(10, (int) Math.round(w * 0.03));
			int y = x;

			// No background behind the head/username (Will 2026-07-21): the chip's
			// translucent backing is removed so the head sits directly on the menu.
			// The username keeps a text shadow (below) so it stays legible over the
			// rings without a panel.
			int hx = x + padX, hy = y + padY;
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
				// fall back to the ring mark below
			}
			if (!drewHead) {
				OriginUi.logo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}

			com.origin.client.client.gui.OriginText.drawBold(guiGraphics, font, name, hx + head + gap,
					y + (chipH - 8) / 2, OriginTheme.TEXT, true);
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
	private static void drawRings(Gfx guiGraphics, int w, int h) {
		for (int i = 0; i < rings.size(); i++) {
			Ring ring = rings.get(i);
			drawRingAt(guiGraphics, ring, w / 2.0 + RingField.offsetX(i, w), h / 2.0 + RingField.offsetY(i, w),
					ringAngle(i), ring.widthFrac() * w * 0.99, RING_HEIGHT_RATIO, ring.opacity());
		}
		shade(1f, 1f, 1f, 1f);
	}

	/** One ring texture as an ellipse of on-screen width {@code major} (GUI units). */
	private static void drawRingAt(Gfx guiGraphics, Ring ring, double cx, double cy, double angle,
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
		pose.mulPose(com.mojang.math.Vector3f.ZP.rotationDegrees((float) (angle % 360.0)));
		pose.scale(scale, (float) (scale * aspect / RING_HEIGHT_RATIO), 1f);
		pose.translate(-TEX / 2f, -TEX / 2f, 0);
		RenderSystem.enableBlend();
		shade(1f, 1f, 1f, alpha);
		guiGraphics.blit(ring.texture(), 0, 0, 0, 0, TEX, TEX, TEX, TEX);
		pose.popPose();
		shade(1f, 1f, 1f, 1f);
	}

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

	private static double frac(double v) {
		return v - Math.floor(v);
	}

	/**
	 * Sparse drifting background dust: fixed pseudo-random particles (seeded from
	 * the index, no stored state) that slowly drift and twinkle. Monochrome, very
	 * low alpha -- depth and life without pulling focus.
	 */
	private static void drawParticles(Gfx guiGraphics, int w, int h) {
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
			fillLayer(guiGraphics, px, py, px + sz, py + sz, (a << 24) | 0x00FFFFFF);
		}
	}

	/**
	 * Bodies orbiting the rings: each body's parametric point on a ring's ellipse
	 * (a, a*heightRatio) advances on its own period, then is rotated by that
	 * ring's live rotation so it visually rides the tilted ellipse -- a small
	 * glowing "planet on its orbit" that plays the Origin identity. Drawn with the
	 * radial-glow texture (halo + core). Skipped if the rings/glow aren't loaded.
	 */
	private static void drawOrbitingBodies(Gfx guiGraphics, int w, int h) {
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
	private static void drawWordmarkGlow(Gfx guiGraphics, double inkCenterX, double inkCenterY,
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
	private static void drawFrame(Gfx guiGraphics, int w, int h) {
		drawVignette(guiGraphics, w, h);
		drawCornerBrackets(guiGraphics, w, h);
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

	/**
	 * Thin L-brackets inset from each screen corner — an aerospace/instrument
	 * framing cue that matches the Michroma wordmark and adds "designed" edges
	 * without a hue. Drawn in the soft-strong stroke tone so they read as quiet
	 * registration marks, not a hard border.
	 */
	private static void drawCornerBrackets(Gfx guiGraphics, int w, int h) {
		int inset = Math.max(10, (int) Math.round(w * 0.022));
		int len = Math.max(10, (int) Math.round(w * 0.018));
		int th = Math.max(1, (int) Math.round(w * 0.0015));
		int c = OriginTheme.STROKE_STRONG;
		// top-left
		fillLayer(guiGraphics, inset, inset, inset + len, inset + th, c);
		fillLayer(guiGraphics, inset, inset, inset + th, inset + len, c);
		// top-right
		fillLayer(guiGraphics, w - inset - len, inset, w - inset, inset + th, c);
		fillLayer(guiGraphics, w - inset - th, inset, w - inset, inset + len, c);
		// bottom-left
		fillLayer(guiGraphics, inset, h - inset - th, inset + len, h - inset, c);
		fillLayer(guiGraphics, inset, h - inset - len, inset + th, h - inset, c);
		// bottom-right
		fillLayer(guiGraphics, w - inset - len, h - inset - th, w - inset, h - inset, c);
		fillLayer(guiGraphics, w - inset - th, h - inset - len, w - inset, h - inset, c);
	}

	/**
	 * Wordmark with a per-letter staggered reveal: each glyph band (from
	 * wordmark.json) fades in and rises into place, offset by index, so the mark
	 * assembles left-to-right instead of popping in whole. Once every letter has
	 * finished (alpha 1, no offset) the bands tile seamlessly back into the full
	 * wordmark. Falls back to the plain whole-mark draw if the bands are missing.
	 */
	private static void drawWordmarkReveal(Gfx guiGraphics, double inkCenterX, double inkCenterY,
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
	private static int drawWordmark(Gfx guiGraphics, double inkCenterX, double inkCenterY, double targetInkHeight) {
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

	/** Draws the progress bar centered on cx at barTop, with the given width/height. */
	private static void drawBar(Gfx guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;

		// Track brighter than the hairline stroke: at 8% white on the near-black
		// background the unfilled track was invisible in-game, so only the fill
		// showed and the bar read as the wrong (too small) size. ~16% matches how
		// the mockup's track actually reads on screen.
		fillLayer(guiGraphics, bx, by, bx + barW, by + barH, 0x29FFFFFF);              // track
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
				meta = GSON.fromJson(new String(com.origin.client.client.IoCompat.readAllBytes(in), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			com.google.gson.JsonArray arr = meta.getAsJsonArray("rings");
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

		// Wordmark loads separately: a failure here falls back to vanilla-font
		// text without disabling the ring/grain background.
		try {
			Minecraft mc = Minecraft.getInstance();
			JsonObject wm;
			try (InputStream in = open("/assets/originclient/textures/ui/wordmark.json")) {
				wm = GSON.fromJson(new String(com.origin.client.client.IoCompat.readAllBytes(in), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			wmTexW = wm.get("width").getAsInt();
			wmTexH = wm.get("height").getAsInt();
			wmInkX = wm.get("inkX").getAsInt();
			wmInkY = wm.get("inkY").getAsInt();
			wmInkW = wm.get("inkWidth").getAsInt();
			wmInkH = wm.get("inkHeight").getAsInt();
			// Per-letter reveal bands (optional): [[x0,x1],...] texture columns.
			if (wm.has("letters")) {
				com.google.gson.JsonArray la = wm.getAsJsonArray("letters");
				int[] x0 = new int[la.size()];
				int[] x1 = new int[la.size()];
				for (int i = 0; i < la.size(); i++) {
					com.google.gson.JsonArray band = la.get(i).getAsJsonArray();
					x0[i] = band.get(0).getAsInt();
					x1[i] = band.get(1).getAsInt();
				}
				wmLetterX0 = x0;
				wmLetterX1 = x1;
			} else {
				wmLetterX0 = null;
				wmLetterX1 = null;
			}
			wordmarkId = registerTexture(mc, "origin_wordmark", "/assets/originclient/textures/ui/wordmark.png");
		} catch (Exception e) {
			wordmarkId = null;
			wmLetterX0 = null;
			wmLetterX1 = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin wordmark failed to load; using vanilla font", e);
		}
		// One typography voice: the title and loading mark use Minecraft's native
		// font path too. Retain the old asset loader only for binary compatibility.
		wordmarkId = null;
		wmLetterX0 = null;
		wmLetterX1 = null;

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
