package com.origin.client.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginText;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.theme.OriginTheme;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Shared Origin screen rendering, used by the title screen (OriginTitleScreen),
// every out-of-world menu (MenuHooks' DrawScreenEvent.Pre backdrop), the world
// load/connect screens (mixin/menus + OriginLoadingRenderer) and every Origin
// window (OriginWindow draws renderTitleBackground itself). Two surfaces:
//
//  - MENUS: "living rings" (launch experience v2, as 1.21.1): the loading
//    scene's own rings, slowed, drifting and leaning towards the mouse
//    (RingField MENU) -- never a panorama, so the menus read as the same place
//    the game loaded into (renderTitleBackground).
//  - LOADING: near-black + the pre-rendered orbital rings + grain + vignette +
//    corner brackets (renderLoadingScene), the screen's title, and a bar.
//  - STARTUP: the Origin loading layer -- near-black + grain + vignette, the
//    MarkMotion loop, "ORIGIN", the bar -- dissolving into the title while the
//    mark flies onto the logo spot (renderStartupLoading / renderLoadingFade).
//
// 1.8.9: the startup resource load itself is Forge's SplashProgress, themed by
// the launcher (config/splash.properties); it owns the GL context on its own
// thread, so the Origin loading layer takes over the title's first frames
// instead (OriginTitleScreen, StartupTransition). Fonts are baked by then, so
// "ORIGIN" draws through the game font (1.21.1's boot-font reader exists only
// because its overlay paints before fonts load). The baked Michroma wordmark
// stays retired ("one typography voice": renderWordmarkAt reports false).
// 1.16.5's dead decorations (particles, orbiting bodies, wordmark glow) are
// not ported either.
//
// Textures load via the classloader (not the resource manager) and degrade
// gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {
	private static final int TEX = 768;

	// 1.8.9: GL_FRAMEBUFFER_BINDING (0x8CA6, same value for the ARB and EXT paths
	// OpenGlHelper may use). Read before any off-screen pass so the frame's own
	// target is put back exactly -- OptiFine's Fast Render can draw the GUI with
	// no Minecraft framebuffer bound, so blindly re-binding mc.getFramebuffer()
	// would send the rest of the frame into a target nobody shows.
	public static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

	// Fail-soft master switch: if ANY Origin screen draw throws, rendering flips
	// to vanilla permanently for this session instead of crashing. Callers that
	// cancel vanilla drawing must only cancel when the boolean-returning entry
	// points report success, and must gate standalone suppressions on isActive().
	private static volatile boolean broken = false;

	private static volatile boolean loaded = false;
	private static boolean ringsFailed = false;
	private static final List<Ring> rings = new ArrayList<>();
	private static ResourceLocation grainId;

	// Edge vignette (transparent core -> soft black corners). Null -> skip.
	private static ResourceLocation vignetteId;
	private static final int VIGNETTE_TEX = 1024;

	// Radial glow: the one restrained jade highlight in the menu grade. Optional.
	private static ResourceLocation radialGlowId;
	private static final int RADIAL_TEX = 512;

	// RingField's per-ring inputs, filled with the rings (ensureLoaded0).
	private static double[] ringAngle0 = new double[0], ringPeriod = new double[0];
	private static boolean[] ringReverse = new boolean[0];

	// The startup layer's clock: RingField.sceneMs() at its first frame (-1 = not yet).
	private static double loadStartScene = -1;
	// The startup bar's shown fill (-1 = none yet) and the scene time it was set.
	private static double loadFill = -1;
	private static double loadFillAt = 0;
	/** The startup and world-loading bars' glide time constant (ms). */
	private static final double FILL_TAU = 140.0;
	// The world-loading scene's shown bar fill, gliding to each new value; < 0 = sweeping.
	private static double sceneFill = -1;
	private static double sceneFillAt = 0;

	// Every fill and blit colour of the loading layer goes through layerAlpha, so
	// the whole layer can be drawn translucent (the loading -> title dissolve)
	// without each helper knowing. 1 everywhere else.
	private static float layerAlpha = 1f;
	// True while the startup layer covers the title: the layer draws the (flying)
	// mark, so the title leaves its own out.
	private static boolean startupLayer = false;

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
	 * Full-screen loading/progress scene for the world-load screens
	 * (GuiDownloadTerrain, GuiScreenWorking, the LoadingScreenRenderer
	 * replacement): the charcoal + rings + grain background, the screen's title
	 * in the default font, and a smooth indeterminate bar. Drawn as a HEAD-cancel
	 * takeover: callers must only cancel vanilla when this returns true.
	 */
	public static boolean renderLoadingScene(Gfx guiGraphics, IChatComponent title) {
		return renderLoadingScene(guiGraphics, title == null ? null : title.getUnformattedText(), null, -1);
	}

	/** 1.8.9: the same scene from a plain String title (1.8.9 screens keep raw strings). */
	public static boolean renderLoadingScene(Gfx guiGraphics, String title) {
		return renderLoadingScene(guiGraphics, title, null, -1);
	}

	/**
	 * 1.8.9: the scene with a stage line under the bar ("Building terrain") and,
	 * when 0..100, a DETERMINATE bar -- 1.8.9's world loading reports a real
	 * percentage through IProgressUpdate (LoadingScreenRenderer /
	 * GuiScreenWorking), so the bar can tell the truth instead of sweeping.
	 */
	public static boolean renderLoadingScene(Gfx guiGraphics, String title, String stage, int progress) {
		if (broken) {
			return false;
		}
		try {
			renderLoadingScene0(guiGraphics, title, stage, progress);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void renderLoadingScene0(Gfx guiGraphics, String title, String stage, int progress) {
		ensureLoaded();
		// From a menu this swings the slowed, leaning rings back to the centred
		// loading orbit (RingField eases it, so it reads as one motion).
		field(RingField.Mood.LOADING);
		Minecraft mc = Minecraft.getMinecraft();
		int w = guiGraphics.guiWidth();
		int h = guiGraphics.guiHeight();

		fill(guiGraphics, 0, 0, w, h, OriginTheme.BG);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		drawFrame(guiGraphics, w, h);

		// The fill glides to each new value (1.8.9 reports whole percents; the
		// integrated server's spawn preparation only once a second), and a scene
		// with no real measure goes back to the sweep.
		double now = RingField.sceneMs();
		if (progress < 0 || progress > 100) {
			sceneFill = -1;
		} else {
			double target = progress / 100.0;
			sceneFill = sceneFill < 0 || target < sceneFill ? target
					: target + (sceneFill - target) * Math.exp(-(now - sceneFillAt) / FILL_TAU);
		}
		sceneFillAt = now;

		FontRenderer font = mc.fontRendererObj;
		int barW = Math.max(80, (int) (w * 0.30));
		int barH = Math.max(2, (int) (h * 0.010));
		float titleScale = 1.5f;
		int titleH = (int) Math.ceil(font.FONT_HEIGHT * titleScale);
		double gap = Math.max(8.0, h * 0.045);

		String s = title == null ? "" : title.trim();
		boolean hasTitle = !s.isEmpty();
		double groupH = (hasTitle ? titleH + gap : 0) + barH;
		double groupTop = h / 2.0 - groupH / 2.0;

		if (hasTitle) {
			Pose pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(w / 2.0, groupTop, 0);
			pose.scale(titleScale, titleScale, 1f);
			guiGraphics.drawString(font, s, -font.getStringWidth(s) / 2, 0, OriginTheme.TEXT, false);
			pose.popPose();
		}
		int barTop = (int) Math.round(groupTop + (hasTitle ? titleH + gap : 0));
		if (sceneFill < 0) {
			drawIndeterminateBar(guiGraphics, w / 2.0, barTop, barW, barH);
		} else {
			drawBar(guiGraphics, w / 2.0, barTop, barW, barH, (float) sceneFill);
		}
		// 1.8.9: the stage line under the bar ("Building terrain") -- 1.8.9's
		// IProgressUpdate reports it separately from the title.
		String line = stage == null ? "" : stage.trim();
		if (!line.isEmpty() && !line.equals(s)) {
			int ly = barTop + barH + (int) Math.max(8, Math.round(h * 0.035));
			guiGraphics.drawString(font, line, (w - font.getStringWidth(line)) / 2, ly, OriginTheme.MUTED, false);
		}
	}

	/**
	 * Just the indeterminate bar, placed just below screen center -- for
	 * screens kept on their vanilla render (GuiConnecting: its Cancel button
	 * and status text stay, the Origin menu background comes from MenuHooks'
	 * DrawScreenEvent.Pre, and this adds the bar on top).
	 */
	public static void renderConnectingBar(Gfx guiGraphics) {
		// TAIL-additive over the vanilla connect screen; skip when broken.
		if (broken) {
			return;
		}
		try {
			ensureLoaded();
			int w = guiGraphics.guiWidth();
			int h = guiGraphics.guiHeight();
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
		fill(guiGraphics, bx, by, bx + barW, by + barH, 0x29FFFFFF); // track
		int segW = Math.max(8, (int) (barW * 0.32));
		int travel = Math.max(1, barW - segW);
		double t = (RingField.sceneMs() % 1800.0) / 1800.0;
		double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI); // smooth 0->1->0
		double segX = bx + eased * travel;
		fillSmooth(guiGraphics, segX - 1, by - 1, segX + segW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW);
		fillSmooth(guiGraphics, segX, by, segX + segW, by + barH, OriginTheme.ACCENT);
	}

	/**
	 * Menu background, "living rings" (as 1.21.1, Will 2026-10-18): the loading
	 * scene's own rings, never a panorama, so the menus read as the same place
	 * the game loaded into. RingField slows them to a third, lets them drift and
	 * leans them towards the mouse. Returns true only if the Origin backdrop
	 * actually drew; callers that cancel vanilla's own backdrop must key off this.
	 *
	 * Cheap on purpose: one fill, four ring blits, the grain tiles, one glow and
	 * the vignette -- no cubemap, no blur pass (the panorama + blur pyramid this
	 * replaced ran every frame).
	 */
	public static boolean renderTitleBackground(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			field(RingField.Mood.MENU);
			int w = guiGraphics.guiWidth();
			int h = guiGraphics.guiHeight();
			fill(guiGraphics, 0, 0, w, h, OriginTheme.BG);
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
		Gfx.defaultBlend();
		GlStateManager.color(0x4B / 255f, 0xC8 / 255f, 0xAE / 255f, 0.07f);
		int d = (int) Math.round(w * 1.1);
		guiGraphics.blit(radialGlowId, (int) Math.round(w * 0.70 - d / 2.0), (int) Math.round(h * 0.15 - d / 2.0),
				d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
		Gfx.resetColor();
	}

	// ---- Blur pyramid helpers (MenuBlur's "Menu Blur" option) ----
	// Fixed-function box-filter pyramid, 1.16.5's method: a frame is halved
	// through LINEAR-filtered targets (each step an exact 2x2 box average) and the
	// small result is stretched back over the screen through bilinear filtering.
	// The title backdrop no longer uses them (living rings need no blur).
	// 1.8.9: the off-screen target is net.minecraft.client.shader.Framebuffer
	// (bindFramebuffer(true) also sets the viewport; bindFramebufferTexture binds
	// its colour texture for reading).

	/** Builds (or resizes) the first {@code levels} targets of a halving pyramid
	 *  for a {@code fullW} x {@code fullH} frame: level i is 1/2^(i+1) size.
	 *  1.8.9: creating a Framebuffer binds framebuffer 0 when it is done, so this
	 *  only runs inside a caller's restoreTarget bracket. Shared with MenuBlur. */
	public static void ensurePyramid(Framebuffer[] pyramid, int levels, int fullW, int fullH) {
		for (int i = 0; i < levels; i++) {
			int pw = Math.max(1, fullW >> (i + 1)), ph = Math.max(1, fullH >> (i + 1));
			Framebuffer t = pyramid[i];
			if (t == null || t.framebufferWidth != pw || t.framebufferHeight != ph) {
				if (t != null) {
					t.deleteFramebuffer();
				}
				t = new Framebuffer(pw, ph, false);
				t.setFramebufferFilter(GL11.GL_LINEAR);
				pyramid[i] = t;
			}
		}
	}

	/** Re-binds the framebuffer that was bound before an off-screen pass, and the
	 *  full-window viewport every GUI draw assumes. Shared with MenuBlur. */
	public static void restoreTarget(int previousFramebuffer) {
		OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);
		Minecraft mc = Minecraft.getMinecraft();
		GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
	}

	/** Draws {@code source}'s colour texture over the whole currently bound target
	 *  as one quad, laid out in GUI space under the GUI's own orthographic
	 *  projection (EntityRenderer.setupOverlayRendering), which maps the GUI
	 *  rectangle onto whatever viewport bindFramebuffer(true) set -- so the same
	 *  quad fills every pyramid level. Fixed-function: texture via
	 *  bindFramebufferTexture(), colour via GlStateManager.color, a GL_QUADS
	 *  POSITION_TEX batch. Framebuffer textures are stored bottom-up, hence v = 1
	 *  at the top edge. Shared with MenuBlur. */
	public static void drawFullscreenTexture(Framebuffer source) {
		GlStateManager.disableBlend();
		GlStateManager.disableDepth();
		GlStateManager.disableCull();
		GlStateManager.disableAlpha();
		GlStateManager.depthMask(false);
		GlStateManager.enableTexture2D();
		source.bindFramebufferTexture();
		GlStateManager.color(1f, 1f, 1f, 1f);
		ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
		// Exactly the extent vanilla's GUI ortho projection spans.
		double gw = sr.getScaledWidth_double();
		double gh = sr.getScaledHeight_double();
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
		wr.pos(0.0, gh, 0.0).tex(0, 0).endVertex();
		wr.pos(gw, gh, 0.0).tex(1, 0).endVertex();
		wr.pos(gw, 0.0, 0.0).tex(1, 1).endVertex();
		wr.pos(0.0, 0.0, 0.0).tex(0, 1).endVertex();
		tess.draw();
		source.unbindFramebufferTexture();
		GlStateManager.depthMask(true);
		GlStateManager.enableAlpha();
		GlStateManager.enableDepth();
		GlStateManager.enableCull();
	}

	/**
	 * Retired by the pixel-grid commit -- deliberately empty. This was a
	 * two-layer mouse-follow spotlight ported from the website; the blur IS the
	 * effect, so it cannot exist on Minecraft's pixel grid. Kept so the call
	 * sites shared with every other version stay identical.
	 */
	public static void renderTitleCursorGlow(Gfx guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
	}

	/**
	 * Main menu: the Origin mark above the compact action stack. After the game's
	 * first load it is the startup layer's mark finishing its flight here
	 * (MarkMotion); any other time it is simply there.
	 */
	public static boolean renderTitleWordmark(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (startupLayer) {
				return true;   // the startup layer draws it, over this
			}
			TitleLayout L = TitleLayout.of(guiGraphics.guiWidth(), guiGraphics.guiHeight());
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
	 * 1.8.9: the startup layer at full strength -- the intro's hold, while the
	 * seed blooms (OriginTitleScreen draws only this; the title waits under it).
	 * False once Origin rendering has failed.
	 */
	public static boolean renderStartupLoading(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			startupLayer = true;
			renderLoading0(guiGraphics, 1f, true, true);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** 1.8.9: true once the startup layer's seed has bloomed into the three circles. */
	public static boolean startupBloomed() {
		return loadStartScene >= 0 && RingField.sceneMs() - loadStartScene >= MarkMotion.BLOOM_MS;
	}

	/**
	 * The startup layer at {@code alpha}, over the title (the dissolve). With
	 * {@code toTitle} the mark flies onto the title's logo spot instead of fading.
	 * False once Origin rendering has failed.
	 */
	public static boolean renderLoadingFade(Gfx guiGraphics, float alpha, boolean toTitle) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = Math.max(0f, Math.min(1f, alpha));
			startupLayer = true;
			// As 1.21.1: nothing the title drew may win a depth test against the layer.
			GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
			if (toTitle) {
				MarkMotion.startFlight();
			}
			boolean fly = toTitle && MarkMotion.flightStarted();
			// The title underneath already drives the ring field: don't pull it back.
			renderLoading0(guiGraphics, 1f, false, !fly);
			if (fly) {
				layerAlpha = 1f;
				TitleLayout L = TitleLayout.of(guiGraphics.guiWidth(), guiGraphics.guiHeight());
				MarkMotion.drawFlight(guiGraphics, L.markCenterX, L.markCenterY, L.markSize);
			}
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
			Gfx.resetColor();
		}
	}

	/** 1.8.9: the dissolve is over; the title draws its own mark from now on. */
	public static void endStartupLayer() {
		startupLayer = false;
	}

	private static void renderLoading0(Gfx guiGraphics, float progress, boolean drive, boolean drawMark) {
		ensureLoaded();
		if (drive) {
			field(RingField.Mood.LOADING);
		}
		int w = guiGraphics.guiWidth();
		int h = guiGraphics.guiHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		double scene = RingField.sceneMs();
		if (loadStartScene < 0) {
			loadStartScene = scene;
		}
		// The fill only ever glides forward (1.8.9's layer only ever shows a
		// finished load, so it simply sits full).
		loadFill = loadFill < 0 || clamped < loadFill - 0.25 ? clamped
				: Math.max(loadFill, clamped + (loadFill - clamped) * Math.exp(-(scene - loadFillAt) / FILL_TAU));
		loadFillAt = scene;

		// No orbit rings here (Will 2026-10-18): the moving mark is the scene.
		fill(guiGraphics, 0, 0, w, h, OriginTheme.BG);
		drawGrain(guiGraphics, w, h);
		drawVignette(guiGraphics, w, h);

		// Mark above, then the wordmark, then the bar, as one group centred a
		// touch above the middle (1.21.1's layout, with its no-texture sizes).
		double size = Math.min(h * 0.36, w * 0.30);
		double markCy = h * 0.40;
		if (drawMark) {
			MarkMotion.drawLoading(guiGraphics, w / 2.0, markCy, size, scene - loadStartScene, layerAlpha);
		}
		double inkH = h * 0.07;
		double wordCy = markCy + size * 0.5 + h * 0.06 + inkH / 2.0;
		drawStartupWordmark(guiGraphics, w / 2.0, wordCy);

		int barW = (int) Math.round(w * 0.16);
		int barH = Math.max(2, (int) Math.round(h * 0.008));
		int barTop = (int) Math.round(wordCy + inkH * 1.15);
		drawBar(guiGraphics, w / 2.0, barTop, barW, barH, (float) loadFill);
		drawCornerBrackets(guiGraphics, w, h);
	}

	/** "ORIGIN" in Minecraft's own letters at 4x, centred on (cx, cy), at the layer's alpha. */
	private static void drawStartupWordmark(Gfx guiGraphics, double cx, double cy) {
		int a = (int) Math.round(layerAlpha * 255.0);
		// 1.8.9: FontRenderer draws a colour whose alpha is under 4 fully opaque.
		if (a < 4) {
			return;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		String word = "ORIGIN";
		Pose pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(cx, cy, 0);
		pose.scale(4f, 4f, 1f);
		// getStringWidth counts the trailing 1px advance; the ink is one narrower.
		guiGraphics.drawStringF(font, word, -(font.getStringWidth(word) - 1) / 2f, -4f,
				(a << 24) | (OriginTheme.TEXT & 0xFFFFFF), false);
		pose.popPose();
	}

	/**
	 * The baked "ORIGIN" wordmark centred on (cx, cy). Retired with the baked
	 * asset (one typography voice, as on 1.16.5): always false, so callers take
	 * their font fallback.
	 */
	public static boolean renderWordmarkAt(Gfx guiGraphics, double cx, double cy, double targetInkHeight, float alpha) {
		return false;
	}

	/**
	 * Main menu: a small account chip (player head + username) in the top-left.
	 * No backing panel (Will 2026-07-21); the username keeps a text shadow. If
	 * the head can't be resolved the chip falls back to the Origin ring mark.
	 * 1.8.9: the account is mc.getSession() (no User object).
	 */
	public static void renderTitleAccountChip(Gfx guiGraphics) {
		if (broken) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getMinecraft();
			net.minecraft.util.Session session = mc.getSession();
			if (session == null) {
				return;
			}
			String name = session.getUsername();
			if (name == null || name.isEmpty()) {
				return;
			}
			FontRenderer font = mc.fontRendererObj;
			int w = guiGraphics.guiWidth();
			int head = 18;
			int padX = 8, padY = 6, gap = 8;
			int chipH = head + padY * 2;
			int x = Math.max(10, (int) Math.round(w * 0.03));
			int y = x;

			int hx = x + padX, hy = y + padY;
			boolean drewHead = false;
			try {
				// The head is blitted straight from the 64x64 skin: face (8,8) + hat
				// layer (40,8). 1.8.9's skin download already converts legacy 64x32
				// skins to 64x64 (ImageBufferDownload), so the UVs hold for both.
				ResourceLocation skin = resolveChipSkin(mc, session);
				guiGraphics.blit(skin, hx, hy, head, head, 8f, 8f, 8, 8, 64, 64);
				Gfx.defaultBlend();
				guiGraphics.blit(skin, hx, hy, head, head, 40f, 8f, 8, 8, 64, 64);
				drewHead = true;
			} catch (Throwable ignored) {
				// fall back to the ring mark below
			}
			if (!drewHead) {
				OriginUi.logo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}

			OriginText.drawBold(guiGraphics, font, name, hx + head + gap,
					y + (chipH - 8) / 2, OriginTheme.TEXT, true);
		} catch (Throwable t) {
			fail(t);
		}
	}

	// The chip head's skin texture. Defaults immediately (so the chip never
	// waits), then SkinManager.loadProfileTextures swaps in the real skin async.
	// 1.8.9's loadProfileTextures fills an own-account profile's textures from
	// the session's profile properties itself, so the bare session profile is
	// enough. One request per session; every failure keeps the default skin.
	private static volatile ResourceLocation chipSkin = null;
	private static volatile boolean chipSkinRequested = false;

	private static ResourceLocation resolveChipSkin(Minecraft mc, net.minecraft.util.Session session) {
		ResourceLocation cached = chipSkin;
		if (cached != null) {
			return cached;
		}
		GameProfile profile = session.getProfile();
		if (!chipSkinRequested && profile != null && profile.getId() != null) {
			chipSkinRequested = true;
			try {
				mc.getSkinManager().loadProfileTextures(profile, new SkinManager.SkinAvailableCallback() {
					@Override
					public void skinAvailable(MinecraftProfileTexture.Type type, ResourceLocation location,
											  MinecraftProfileTexture texture) {
						if (type == MinecraftProfileTexture.Type.SKIN) {
							chipSkin = location;
						}
					}
				}, true);
			} catch (Throwable ignored) {
				// stay on the default skin
			}
		}
		java.util.UUID id = profile == null ? null : profile.getId();
		return id != null ? DefaultPlayerSkin.getDefaultSkin(id) : DefaultPlayerSkin.getDefaultSkinLegacy();
	}

	// ---- Primitives ----

	/** Advance the shared rings (RingField) for this frame's scene. */
	private static void field(RingField.Mood mood) {
		double mx = 0.5, my = 0.5;
		if (mood == RingField.Mood.MENU) {
			// 1.8.9: LWJGL 2's Mouse counts y up from the window's bottom edge.
			mx = Mouse.getX() / (double) Gfx.windowWidth();
			my = (Gfx.windowHeight() - Mouse.getY()) / (double) Gfx.windowHeight();
		}
		RingField.update(mood, ringAngle0, ringPeriod, ringReverse, mx, my);
	}

	/** Every ring where RingField has it. */
	private static void drawRings(Gfx guiGraphics, int w, int h) {
		Pose pose = guiGraphics.pose();
		Gfx.defaultBlend();
		for (int i = 0; i < rings.size(); i++) {
			Ring ring = rings.get(i);
			float alpha = ring.opacity() * layerAlpha;
			if (alpha <= 0.002f) {
				continue;
			}
			double angle = RingField.angle(i, ring.angle0(), ring.periodSeconds(), ring.reverse());
			// The ellipse fills 0.9 of its square texture (1.21.1: major = widthFrac * w * 0.99).
			float scale = (float) (ring.widthFrac() * w * 0.99 / (0.9 * TEX));
			pose.pushPose();
			pose.translate(w / 2.0 + RingField.offsetX(i, w), h / 2.0 + RingField.offsetY(i, w), 0);
			// Wrapped to [0,360) in double before the float cast: a float holding a
			// large absolute angle would step visibly.
			pose.mulPose(Pose.Axis.ZP.rotationDegrees((float) (angle % 360.0)));
			pose.scale(scale, scale, 1f);
			pose.translate(-TEX / 2f, -TEX / 2f, 0);
			GlStateManager.color(1f, 1f, 1f, alpha);
			guiGraphics.blit(ring.texture(), 0, 0, 0f, 0f, TEX, TEX, TEX, TEX);
			pose.popPose();
		}
		Gfx.resetColor();
	}

	private static void drawGrain(Gfx guiGraphics, int w, int h) {
		if (grainId == null) {
			return;
		}
		// Tile in REAL pixels (pose-scale 1/guiScale): at 1:1 each noise grain is
		// a single screen pixel, like the website's.
		double gs = Gfx.guiScale();
		int realW = (int) Math.ceil(w * gs);
		int realH = (int) Math.ceil(h * gs);
		int tile = 256; // matches grain.png
		Pose pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		Gfx.defaultBlend();
		GlStateManager.color(1f, 1f, 1f, 0.028f * layerAlpha);
		for (int y = 0; y < realH; y += tile) {
			for (int x = 0; x < realW; x += tile) {
				guiGraphics.blit(grainId, x, y, 0f, 0f, tile, tile, tile, tile);
			}
		}
		Gfx.resetColor();
		pose.popPose();
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
		Pose pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) w / VIGNETTE_TEX, (float) h / VIGNETTE_TEX, 1f);
		Gfx.defaultBlend();
		GlStateManager.color(1f, 1f, 1f, layerAlpha);
		guiGraphics.blit(vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX);
		Gfx.resetColor();
		pose.popPose();
	}

	/** Thin L-brackets inset from each screen corner, in the soft-strong stroke tone. */
	private static void drawCornerBrackets(Gfx guiGraphics, int w, int h) {
		int inset = Math.max(10, (int) Math.round(w * 0.022));
		int len = Math.max(10, (int) Math.round(w * 0.018));
		int th = Math.max(1, (int) Math.round(w * 0.0015));
		int c = OriginTheme.STROKE_STRONG;
		// top-left
		fill(guiGraphics, inset, inset, inset + len, inset + th, c);
		fill(guiGraphics, inset, inset, inset + th, inset + len, c);
		// top-right
		fill(guiGraphics, w - inset - len, inset, w - inset, inset + th, c);
		fill(guiGraphics, w - inset - th, inset, w - inset, inset + len, c);
		// bottom-left
		fill(guiGraphics, inset, h - inset - th, inset + len, h - inset, c);
		fill(guiGraphics, inset, h - inset - len, inset + th, h - inset, c);
		// bottom-right
		fill(guiGraphics, w - inset - len, h - inset - th, w - inset, h - inset, c);
		fill(guiGraphics, w - inset - th, h - inset - len, w - inset, h - inset, c);
	}

	/** Draws the progress bar centered on cx at barTop, with the given width/height. */
	private static void drawBar(Gfx guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;
		// Track at ~16% white: at 8% the unfilled track vanished on the near-black
		// background and the bar read as the wrong size.
		fill(guiGraphics, bx, by, bx + barW, by + barH, 0x29FFFFFF);              // track
		double fillW = barW * Math.max(0f, Math.min(1f, progress));
		if (fillW > 0.05) {
			fillSmooth(guiGraphics, bx - 1, by - 1, bx + fillW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW); // soft glow
			fillSmooth(guiGraphics, bx, by, bx + fillW, by + barH, OriginTheme.ACCENT);  // fill
		}
	}

	/** Gfx.fill with the colour's alpha scaled by layerAlpha (fills carry their colour per vertex). */
	private static void fill(Gfx guiGraphics, int x0, int y0, int x1, int y1, int argb) {
		if (layerAlpha < 1f) {
			int a = Math.round((argb >>> 24) * layerAlpha);
			if (a <= 0) {
				return;
			}
			argb = (a << 24) | (argb & 0xFFFFFF);
		}
		guiGraphics.fill(x0, y0, x1, y1, argb);
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
		double gs = Gfx.guiScale();
		double px0 = x0 * gs, px1 = x1 * gs;
		int py0 = (int) Math.round(y0 * gs), py1 = (int) Math.round(y1 * gs);
		Pose pose = guiGraphics.pose();
		pose.pushPose();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		int i0 = (int) Math.ceil(px0), i1 = (int) Math.floor(px1);
		if (i1 < i0) {
			fillEdge(guiGraphics, i1, py0, py1, px1 - px0, argb);  // the whole span is inside one pixel
		} else {
			if (i1 > i0) {
				fill(guiGraphics, i0, py0, i1, py1, argb);
			}
			fillEdge(guiGraphics, i0 - 1, py0, py1, i0 - px0, argb);
			fillEdge(guiGraphics, i1, py0, py1, px1 - i1, argb);
		}
		pose.popPose();
	}

	private static void fillEdge(Gfx guiGraphics, int x, int y0, int y1, double cover, int argb) {
		int a = (int) Math.round((argb >>> 24) * Math.min(1.0, cover));
		if (a > 0) {
			fill(guiGraphics, x, y0, x + 1, y1, (a << 24) | (argb & 0xFFFFFF));
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
			JsonObject meta;
			try (InputStream in = open("/assets/originclient/textures/ui/rings.json")) {
				// gson 2.2.4: no JsonObject adapter for fromJson -- parse the tree directly.
				meta = new JsonParser().parse(new String(com.origin.client.client.IoCompat.readAllBytes(in),
						java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
			}
			com.google.gson.JsonArray arr = meta.getAsJsonArray("rings");
			for (int i = 0; i < arr.size(); i++) {
				JsonObject r = arr.get(i).getAsJsonObject();
				int index = r.get("index").getAsInt();
				ResourceLocation id = registerTexture("origin_ring_" + index,
						"/assets/originclient/textures/ui/ring-" + index + ".png");
				rings.add(new Ring(id,
						r.get("widthFrac").getAsDouble(),
						r.get("opacity").getAsFloat(),
						r.get("angle0").getAsDouble(),
						r.get("periodSeconds").getAsDouble(),
						r.get("reverse").getAsBoolean()));
			}
			grainId = registerTexture("origin_grain", "/assets/originclient/textures/ui/grain.png");
			int n = rings.size();
			double[] a0 = new double[n], per = new double[n];
			boolean[] rev = new boolean[n];
			for (int i = 0; i < n; i++) {
				a0[i] = rings.get(i).angle0();
				per[i] = rings.get(i).periodSeconds();
				rev[i] = rings.get(i).reverse();
			}
			ringAngle0 = a0;
			ringPeriod = per;
			ringReverse = rev;
		} catch (Exception e) {
			ringsFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin screen ring/grain textures failed to load; using plain background", e);
		}

		// Vignette loads independently: a failure just skips the edge darkening.
		try {
			vignetteId = registerTexture("origin_vignette", "/assets/originclient/textures/ui/vignette.png");
		} catch (Exception e) {
			vignetteId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin vignette failed to load; skipping edge vignette", e);
		}

		// The grade's jade highlight. Optional; skipped entirely on failure.
		try {
			radialGlowId = registerTexture("origin_radial_glow", "/assets/originclient/textures/ui/radial_glow.png");
		} catch (Exception e) {
			radialGlowId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin radial glow failed to load; skipping the grade highlight", e);
		}
	}

	/** Uploads a classpath PNG as a LINEAR-filtered dynamic texture through the
	 *  foundation's Gfx.registerTexture (1.8.9's DynamicTexture uploads NEAREST and
	 *  has no setFilter; Gfx sets the filter on its GL id). A null result (upload
	 *  failed) throws, so each caller's catch skips just that layer. */
	private static ResourceLocation registerTexture(String name, String path) throws Exception {
		ResourceLocation id = new ResourceLocation("originclient", name);
		if (Gfx.registerTexture(id, Gfx.readImage(path), true) == null) {
			throw new IllegalStateException("Origin texture upload failed: " + path);
		}
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
