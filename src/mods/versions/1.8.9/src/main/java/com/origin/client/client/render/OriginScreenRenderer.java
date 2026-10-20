package com.origin.client.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.gui.Gfx;
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
// every out-of-world Minecraft menu (MenuHooks' DrawScreenEvent.Pre backdrop),
// the world load/connect screens (mixin/menus + OriginLoadingRenderer) and every
// Origin window (OriginWindow draws renderTitleBackground itself). The clean
// in-game look (ported from 1.21.1, 2026-10-19):
//
//  - MINECRAFT'S MENUS + TITLE: 1.8.9's own title panorama, drawn and blurred by
//    vanilla's renderer (MenuPanorama), under Origin's dim and vignette
//    (renderMenuBackdrop / renderTitleBackdrop). Stock buttons and font.
//  - LOADING: the same panorama under the loading dim, the real Origin logo, the
//    world's name, a thin bar and vanilla's stage line (renderLoadingScene).
//  - STARTUP: the clean splash -- near-black, the logo, "ORIGIN", a full bar and
//    100% -- held over the title while its first frames settle, then dissolving
//    into it (renderStartupLoading / renderLoadingFade, OriginTitleScreen).
//  - ORIGIN'S OWN WINDOWS keep the "living rings" (renderTitleBackground).
//
// 1.8.9: the startup resource load itself is Forge's SplashProgress, themed by
// the launcher (config/splash.properties); it owns the GL context on its own
// thread, so the Origin startup layer takes over the title's first frames
// instead. Fonts are baked by then, so "ORIGIN" draws through the game font
// (1.21.1's boot-font reader exists only because its overlay paints before fonts
// load).
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

	/** The world-loading bar's glide time constant (ms). */
	private static final double FILL_TAU = 140.0;
	// The world-loading scene's shown bar fill, gliding to each new value; < 0 = sweeping.
	private static double sceneFill = -1;
	// Never backward within a world load, never full before the world is up (LoadBar.scene).
	private static final LoadBar SCENE_BAR = new LoadBar();

	// Every fill and blit colour of the startup layer goes through layerAlpha
	// (fade / drawCenterLift / drawVignette), so the whole layer can be drawn
	// translucent (the loading -> title dissolve) without each helper knowing.
	// 1 everywhere else.
	private static float layerAlpha = 1f;

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
	 * replacement), the clean look (1.21.1, 2026-10-19): this version's own
	 * panorama under the loading dim (a solid near-black if it can't draw), the
	 * real Origin logo, the world's name (or the screen's title), a thin bar and
	 * vanilla's own stage line. Drawn as a HEAD-cancel takeover: callers must only
	 * cancel vanilla when this returns true.
	 */
	public static boolean renderLoadingScene(Gfx guiGraphics, IChatComponent title) {
		return renderLoadingScene(guiGraphics, title == null ? null : title.getUnformattedText(), null, -1);
	}

	/** 1.8.9: the same scene from a plain String title (1.8.9 screens keep raw strings). */
	public static boolean renderLoadingScene(Gfx guiGraphics, String title) {
		return renderLoadingScene(guiGraphics, title, null, -1);
	}

	/**
	 * 1.8.9: the scene with vanilla's stage line under the bar ("Building
	 * terrain") and, when 0..100, a DETERMINATE bar -- 1.8.9's world loading
	 * reports a real percentage through IProgressUpdate (LoadingScreenRenderer /
	 * GuiScreenWorking), so the bar tells the truth instead of sweeping. Never a
	 * made-up percentage or stage name.
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
		Minecraft mc = Minecraft.getMinecraft();
		int w = gw(), h = gh();
		cleanBackdrop(guiGraphics, w, h, OriginTheme.CLEAN_DIM_LOADING);

		// The fill glides to each new count and only moves forward within one world
		// load; it sweeps until the load's first measured stage (LoadBar.scene).
		double now = RingField.sceneMs();
		sceneFill = SCENE_BAR.scene(com.origin.client.client.hud.FrameStats.frames(),
				progress < 0 || progress > 100 ? -1 : progress / 100.0, now, FILL_TAU);

		// World loading (a real percentage): the world's name big, as 1.21.1.
		String levelName = null;
		if (progress >= 0 && progress <= 100) {
			try {
				if (mc.getIntegratedServer() != null) {
					levelName = mc.getIntegratedServer().getWorldName();   // the display name (javap 1.8.9)
				}
			} catch (Throwable ignored) {
				levelName = null;
			}
		}
		// Late addendum (2026-10-19): the labels are rebuilt when any input
		// changes, the language code included, compared by value (no allocation:
		// every input is a stored String), so a language change can't leave a
		// stale line on screen.
		String lang = mc.gameSettings == null ? null : mc.gameSettings.language;
		if (!java.util.Objects.equals(title, labelTitle) || !java.util.Objects.equals(stage, labelStage)
				|| !java.util.Objects.equals(levelName, labelLevel) || !java.util.Objects.equals(lang, labelLang)) {
			labelTitle = title;
			labelStage = stage;
			labelLevel = levelName;
			labelLang = lang;
			sceneLabels(title, stage, levelName);
		}
		drawSceneText(guiGraphics, w, h, labelBig, labelSmall, sceneFill);
	}

	// The loading scene's labels and the inputs they were built from.
	private static String labelTitle, labelStage, labelLevel, labelLang;
	private static String labelBig = "", labelSmall = "";

	/** The scene's big line (world name, else the title) and small line (vanilla's stage). */
	private static void sceneLabels(String title, String stage, String levelName) {
		String big = title == null ? "" : title.trim();
		String small = stage == null ? "" : stage.trim();
		if (levelName != null && !levelName.trim().isEmpty()) {
			big = levelName;
		}
		if (small.equals(big) || (title != null && small.equals(title.trim()))) {
			small = "";
		}
		labelBig = big;
		labelSmall = small;
	}

	/** The loading scene's centred logo, title, bar and stage line (fill < 0: sweeping). As 1.21.1. */
	private static void drawSceneText(Gfx guiGraphics, int w, int h, String big, String small, double fill) {
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
	 * Connecting to a server (GuiConnecting keeps its vanilla render: the status
	 * line and Cancel stay, the backdrop comes from MenuHooks' DrawScreenEvent.Pre).
	 * This adds the logo above the status line and the thin sweeping bar under
	 * it, as 1.21.1.
	 */
	public static void renderConnectingBar(Gfx guiGraphics) {
		// TAIL-additive over the vanilla connect screen; skip when broken.
		if (broken) {
			return;
		}
		try {
			ensureLoaded();
			int w = gw(), h = gh();
			// GuiConnecting.drawScreen draws the status line with its top at
			// height / 2 - 50 (integer division, javap 1.8.9).
			double statusMid = h / 2 - 50 + 3.5;
			drawLogo(guiGraphics, w / 2.0, statusMid - 26, 22, 1f);
			drawThinBar(guiGraphics, w / 2.0, statusMid + 11, Math.min(160, w * 0.5), 0f, true);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** One label in Minecraft's own font (with its shadow), centred on (cx, midY). */
	private static void drawCenteredLabel(Gfx g, String s, double cx, double midY, int argb) {
		if (s == null || s.isEmpty()) {
			return;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		g.drawString(font, s, (int) Math.round(cx - font.getStringWidth(s) / 2.0), (int) Math.round(midY - 4), argb, true);
	}

	/**
	 * The thin loading bar (160 x 1.5 GUI px, 1.21.1's mockup): a faint track and
	 * a white fill at physical-pixel precision (fillSmooth), so a 1.5 px bar is a
	 * real 3 px line at GUI scale 2 and the fill end glides instead of stepping.
	 * {@code sweep}: an indeterminate segment ping-pongs along the track.
	 */
	private static void drawThinBar(Gfx g, double cx, double top, double barW, float progress, boolean sweep) {
		double x0 = Math.round(cx - barW / 2.0);
		double bh = 1.5;
		fillSmooth(g, x0, top, x0 + barW, top + bh, fade(OriginTheme.CLEAN_BAR_TRACK));
		if (sweep) {
			double segW = barW * 0.28;
			double t = (RingField.sceneMs() % 1800.0) / 1800.0;
			double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI);
			double sx = x0 + eased * (barW - segW);
			fillSmooth(g, sx, top, sx + segW, top + bh, fade(OriginTheme.CLEAN_BAR_FILL));
		} else {
			double fw = barW * Math.max(0f, Math.min(1f, progress));
			if (fw > 0.01) {
				fillSmooth(g, x0, top, x0 + fw, top + bh, fade(OriginTheme.CLEAN_BAR_FILL));
			}
		}
	}

	/** A colour with layerAlpha (the loading -> title dissolve) multiplied into its alpha. */
	private static int fade(int argb) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, layerAlpha)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static double clampD(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ---- clean backdrop: this version's own panorama ----

	/**
	 * Out-of-world Minecraft menus (MenuHooks' DrawScreenEvent.Pre): 1.8.9's own
	 * title panorama, drawn and blurred by vanilla's renderer (MenuPanorama),
	 * under the menu dim and the vignette. False only once Origin rendering has
	 * failed (the caller keeps vanilla's dirt).
	 */
	public static boolean renderMenuBackdrop(Gfx g) {
		return backdrop(g, OriginTheme.CLEAN_DIM_MENU);
	}

	/** The title: the same panorama under the lighter title dim. */
	public static boolean renderTitleBackdrop(Gfx g) {
		return backdrop(g, OriginTheme.CLEAN_DIM_TITLE);
	}

	private static boolean backdrop(Gfx g, int dim) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			cleanBackdrop(g, gw(), gh(), dim);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** The panorama (or, if it can't draw, a solid near-black), the dim, the vignette. */
	private static void cleanBackdrop(Gfx g, int w, int h, int dim) {
		if (!MenuPanorama.draw(w, h)) {
			g.fill(0, 0, w, h, OriginTheme.CLEAN_BG);
		}
		g.fill(0, 0, w, h, dim);
		drawVignette(g, w, h);
		Gfx.resetColor();
	}

	// ---- the real Origin logo, crisp at any size ----

	// origin_logo.png (256 px, white three-ring mark) downsampled ONCE per
	// physical size with a 4x4 box filter and blitted 1:1 (NEAREST), so its thin
	// strokes stay smooth instead of shimmering under plain minification. As
	// 1.21.1; 1.8.9 bakes into a BufferedImage and uploads through
	// Gfx.registerTexture. Indexed by physical px (an array, so a frame's lookup
	// boxes nothing).
	private static final int LOGO_MAX_PX = 512;
	private static final ResourceLocation[] LOGO_SIZES = new ResourceLocation[LOGO_MAX_PX + 1];
	private static java.awt.image.BufferedImage logoSrc;
	private static boolean logoFailed;

	/** Draws the logo centred on (cx, cy), {@code size} GUI px square, at alpha. */
	private static void drawLogo(Gfx g, double cx, double cy, double size, float alpha) {
		int gs = scale();
		int ps = (int) Math.round(size * gs);
		ResourceLocation id = logoAt(ps);
		if (id == null) {
			OriginUi.logo(g, cx, cy, (int) Math.round(size), alpha);
			return;
		}
		Pose pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		Gfx.defaultBlend();
		GlStateManager.color(1f, 1f, 1f, Math.max(0f, Math.min(1f, alpha)));
		int px = (int) Math.round(cx * gs - ps / 2.0), py = (int) Math.round(cy * gs - ps / 2.0);
		g.blit(id, px, py, 0f, 0f, ps, ps, ps, ps);
		Gfx.resetColor();
		pose.popPose();
	}

	private static ResourceLocation logoAt(int ps) {
		if (logoFailed || ps <= 0 || ps > LOGO_MAX_PX) {
			return null;
		}
		ResourceLocation id = LOGO_SIZES[ps];
		if (id != null) {
			return id;
		}
		try {
			if (logoSrc == null) {
				logoSrc = Gfx.readImage("/assets/originclient/textures/ui/origin_logo.png");
			}
			int sw = logoSrc.getWidth(), sh = logoSrc.getHeight();
			java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(ps, ps, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < ps; y++) {
				for (int x = 0; x < ps; x++) {
					double aSum = 0, cSum = 0;
					for (int sy = 0; sy < 4; sy++) {
						for (int sx = 0; sx < 4; sx++) {
							int tx = Math.min(sw - 1, (int) ((x + (sx + 0.5) / 4.0) * sw / ps));
							int ty = Math.min(sh - 1, (int) ((y + (sy + 0.5) / 4.0) * sh / ps));
							int c = logoSrc.getRGB(tx, ty);   // ARGB
							double a = ((c >>> 24) & 0xFF) / 255.0;
							aSum += a;
							cSum += a * ((c & 0xFF) / 255.0);
						}
					}
					double a = aSum / 16.0;
					int v = a > 0 ? (int) Math.round(Math.min(1.0, cSum / aSum) * 255) : 255;
					dst.setRGB(x, y, ((int) Math.round(a * 255) << 24) | (v << 16) | (v << 8) | v);
				}
			}
			id = new ResourceLocation("originclient", "logo_px_" + ps);
			if (Gfx.registerTexture(id, dst, false) == null) {
				throw new IllegalStateException("logo upload failed");
			}
			LOGO_SIZES[ps] = id;
			return id;
		} catch (Throwable t) {
			logoFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin logo bake failed; using the plain logo blit", t);
			return null;
		}
	}

	// "ORIGIN" one letter per string (built once: the wordmark allocates nothing per frame).
	private static final String WORD = "ORIGIN";
	private static final String[] WORD_LETTERS = {"O", "R", "I", "G", "I", "N"};
	private static final int WORD_TRACKING = 3;

	/**
	 * "ORIGIN" in Minecraft's own letters at 2x, spaced out by 3 font px (1.21.1's
	 * tracked wordmark), with the font's shadow, bottom of the caps on
	 * {@code baseline}. 1.8.9: fonts are baked before anything here draws, so this
	 * is the game font itself (1.21.1 reads ascii.png only because its splash
	 * paints before fonts load -- the same pixels).
	 */
	private static void drawWordmarkText(Gfx g, double cx, double baseline, int argb) {
		// FontRenderer draws a colour whose alpha is under 4 fully opaque.
		if (((argb >>> 24) & 0xFF) < 4) {
			return;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		// getCharWidth is the advance (glyph + 1 px); the ink ends 1 px before the last advance.
		float total = 0;
		for (int i = 0; i < WORD.length(); i++) {
			total += font.getCharWidth(WORD.charAt(i)) + WORD_TRACKING;
		}
		total -= 1 + WORD_TRACKING;
		Pose pose = g.pose();
		pose.pushPose();
		pose.translate(Math.round(cx - total * 2f * 0.5f), Math.round(baseline - 14), 0);
		pose.scale(2f, 2f, 1f);
		float x = 0;
		for (int i = 0; i < WORD_LETTERS.length; i++) {
			g.drawStringF(font, WORD_LETTERS[i], x, 0f, argb, true);
			x += font.getCharWidth(WORD.charAt(i)) + WORD_TRACKING;
		}
		pose.popPose();
	}

	/** A barely-there radial lift behind the centre (the splash's "soft centre lift"). */
	private static void drawCenterLift(Gfx g, int w, int h, float alpha) {
		if (radialGlowId == null) {
			return;
		}
		Gfx.defaultBlend();
		GlStateManager.color(1f, 1f, 1f, alpha * layerAlpha);
		int d = (int) Math.round(Math.max(w, h) * 0.9);
		g.blit(radialGlowId, (w - d) / 2, (int) Math.round(h * 0.44 - d / 2.0), d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
		Gfx.resetColor();
	}

	// ---- GUI size without a ScaledResolution per call ----

	/** ScaledResolution's scale factor (1.8.9's algorithm, javap), allocation-free. */
	public static int scale() {
		Minecraft mc = Minecraft.getMinecraft();
		int dw = Math.max(1, mc.displayWidth), dh = Math.max(1, mc.displayHeight);
		int max = mc.gameSettings.guiScale == 0 ? 1000 : mc.gameSettings.guiScale;
		int s = 1;
		while (s < max && dw / (s + 1) >= 320 && dh / (s + 1) >= 240) {
			s++;
		}
		if (mc.isUnicode() && s % 2 != 0 && s != 1) {
			s--;
		}
		return s;
	}

	private static int gw() {
		return (int) Math.ceil(Math.max(1, Minecraft.getMinecraft().displayWidth) / (double) scale());
	}

	private static int gh() {
		return (int) Math.ceil(Math.max(1, Minecraft.getMinecraft().displayHeight) / (double) scale());
	}

	/**
	 * Origin's own windows' background, "living rings" (as 1.21.1, Will
	 * 2026-10-18; Minecraft's menus get the panorama, renderMenuBackdrop). RingField slows them to a third, lets them drift and
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
		ScaledResolution sr = Gfx.scaled();
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
	 * Main menu: the real Origin logo and the ORIGIN wordmark above the button
	 * stack, still (the startup layer dissolves over it after the game's first
	 * load). TitleLayout drops the logo, then the wordmark, when extra rows need
	 * the room. As 1.21.1.
	 */
	public static boolean renderTitleWordmark(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			TitleLayout L = TitleLayout.of(gw(), gh());
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
	 * The baked "ORIGIN" wordmark centred on (cx, cy). Retired with the baked
	 * asset (one typography voice): always false, so callers take their font
	 * fallback.
	 */
	public static boolean renderWordmarkAt(Gfx guiGraphics, double cx, double cy, double targetInkHeight, float alpha) {
		return false;
	}

	/**
	 * 1.8.9: the startup layer at full strength over whatever is drawn this frame
	 * -- the settle hold, the title rendered for real underneath (OriginTitleScreen;
	 * 1.21.1's renderLoadingHold). False once Origin rendering has failed.
	 */
	public static boolean renderStartupLoading(Gfx guiGraphics) {
		return renderLoadingFade(guiGraphics, 1f, true);
	}

	/**
	 * The startup layer at {@code alpha} over the title (the dissolve). The logo
	 * is still on both, so the layer simply dissolves; {@code toTitle} is kept
	 * for the call sites' shape (1.21.1). False once Origin rendering has failed.
	 */
	public static boolean renderLoadingFade(Gfx guiGraphics, float alpha, boolean toTitle) {
		if (broken) {
			return false;
		}
		try {
			layerAlpha = Math.max(0f, Math.min(1f, alpha));
			// As 1.21.1: nothing the title drew may win a depth test against the layer.
			GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
			renderSplash(guiGraphics);
			return true;
		} catch (Throwable t) {
			return fail(t);
		} finally {
			layerAlpha = 1f;
			Gfx.resetColor();
		}
	}

	/**
	 * The clean splash (1.21.1's renderLoading0, Will's approved mockup): solid
	 * near-black with a soft centre lift, the real logo, the ORIGIN wordmark, a
	 * thin bar and the percent. 1.8.9's startup load itself is Forge's splash
	 * (themed by the launcher), so this layer only ever shows a FINISHED load:
	 * the bar sits full at 100% -- exactly 1.21.1's settle-hold picture.
	 */
	private static void renderSplash(Gfx guiGraphics) {
		ensureLoaded();
		int w = gw(), h = gh();
		guiGraphics.fill(0, 0, w, h, fade(OriginTheme.CLEAN_BG));
		drawCenterLift(guiGraphics, w, h, 0.05f);
		double size = clampD(h * 0.11, 24, 44);
		double markCy = h * 0.44;
		drawLogo(guiGraphics, w / 2.0, markCy, size, layerAlpha);
		double wordBase = markCy + size / 2.0 + 19;
		drawWordmarkText(guiGraphics, w / 2.0, wordBase, fade(0xFFFFFFFF));
		double barW = Math.min(160, w * 0.5);
		double barTop = wordBase + 9;
		drawThinBar(guiGraphics, w / 2.0, barTop, barW, 1f, false);
		int pctArgb = fade(OriginTheme.CLEAN_TEXT_FAINT);
		if (((pctArgb >>> 24) & 0xFF) >= 4) {
			FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
			// Right edge of the ink on the bar's end (getStringWidth counts one trailing px).
			int right = (int) Math.round(w / 2.0 + barW / 2.0);
			guiGraphics.drawString(font, PCT_FULL, right - (font.getStringWidth(PCT_FULL) - 1), (int) Math.round(barTop + 5), pctArgb, true);
		}
	}

	private static final String PCT_FULL = "100%";

	/** The title's player chip draws on vanilla's normal button sprite (widgets.png, v = 66). */
	private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");

	/**
	 * Main menu: the account chip (player head + username) in the top-left, on
	 * a vanilla button, as 1.21.1 (Will, 2026-10-19: "use the same style for the
	 * player in the top left"). Not clickable, so it never highlights. Head from
	 * the skin (default first, the real one swaps in async); the logo if the head
	 * can't be resolved. 1.8.9: the account is mc.getSession() (no User object);
	 * no PlayerFaceRenderer, so the face and hat layers are blitted from the skin.
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
			int x = TitleLayout.CORNER, y = TitleLayout.CORNER, chipH = TitleLayout.CORNER_SIZE;
			int head = 12, pad = 4, gap = 5;
			int textW = font.getStringWidth(name);
			int chipW = pad + head + gap + textW + pad + 2;
			// GuiButton.drawButton's two halves of the 200 px sprite (javap 1.8.9).
			Gfx.defaultBlend();
			GlStateManager.color(1f, 1f, 1f, 1f);
			int left = chipW / 2, right = chipW - left;
			guiGraphics.blit(WIDGETS, x, y, 0, 66, left, chipH);
			guiGraphics.blit(WIDGETS, x + left, y, 200 - right, 66, right, chipH);

			int hx = x + pad, hy = y + (chipH - head) / 2;
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
				// fall back to the logo below
			}
			if (!drewHead) {
				drawLogo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);
			}
			// Vanilla button text: white with the font's shadow.
			guiGraphics.drawString(font, name, hx + head + gap, y + (chipH - 8) / 2, 0xFFFFFFFF, true);
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

	// The session whose profile / default skin the cache below belongs to; a different
	// session object (account switch) re-resolves, nothing else does.
	private static volatile net.minecraft.util.Session chipSession = null;
	private static volatile GameProfile chipProfile = null;
	private static volatile ResourceLocation chipDefaultSkin = null;

	private static ResourceLocation resolveChipSkin(Minecraft mc, net.minecraft.util.Session session) {
		if (session != chipSession) {
			// First call, or the session changed: resolve once and cache. Session.getProfile()
			// builds a new GameProfile on every call, and an offline / skinless account never
			// gets a skin callback, so this must not run per frame.
			chipSkin = null;
			chipSkinRequested = false;
			GameProfile profile = null;
			ResourceLocation def;
			try {
				profile = session.getProfile();
				java.util.UUID id = profile == null ? null : profile.getId();
				def = id != null ? DefaultPlayerSkin.getDefaultSkin(id) : DefaultPlayerSkin.getDefaultSkinLegacy();
			} catch (Throwable t) {
				def = DefaultPlayerSkin.getDefaultSkinLegacy();
			}
			chipProfile = profile;
			chipDefaultSkin = def;
			chipSession = session;
		}
		ResourceLocation cached = chipSkin;
		if (cached != null) {
			return cached;
		}
		GameProfile profile = chipProfile;
		if (!chipSkinRequested && profile != null && profile.getId() != null) {
			chipSkinRequested = true;
			// The callback lands later on the client thread; if the account was switched in
			// between, this session's skin must not become the new account's chip head.
			final net.minecraft.util.Session requestedFor = session;
			try {
				mc.getSkinManager().loadProfileTextures(profile, new SkinManager.SkinAvailableCallback() {
					@Override
					public void skinAvailable(MinecraftProfileTexture.Type type, ResourceLocation location,
											  MinecraftProfileTexture texture) {
						if (type == MinecraftProfileTexture.Type.SKIN && chipSession == requestedFor) {
							chipSkin = location;
						}
					}
				}, true);
			} catch (Throwable ignored) {
				// stay on the default skin
			}
		}
		return chipDefaultSkin;
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
		double gs = scale();
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
