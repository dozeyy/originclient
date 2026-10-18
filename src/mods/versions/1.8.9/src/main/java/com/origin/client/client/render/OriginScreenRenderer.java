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
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Shared Origin screen rendering, used by the title screen (OriginTitleScreen),
// every out-of-world menu (MenuHooks' DrawScreenEvent.Pre backdrop), the world
// load/connect screens (mixin/menus + OriginLoadingRenderer) and every Origin
// window (OriginWindow draws renderTitleBackground itself). Two surfaces:
//
//  - MENUS: the vanilla panorama, live-rotating, blurred, graded into Origin's
//    palette (renderTitleBackground) -- the 2026-07-15 pixel-grid design.
//  - LOADING: near-black + the pre-rendered orbital rings + grain + vignette +
//    corner brackets (renderLoadingScene), the screen's title, and a bar.
//
// 1.8.9: the startup resource-load screen is Forge's SplashProgress, themed by
// the launcher (config/splash.properties), so 1.16.5's renderLoading (the
// LoadingOverlay takeover) and its boot font have nothing to draw on here and
// are not ported. The baked Michroma wordmark was already retired on 1.16.5
// ("one typography voice": wordmarkId forced null), so its loader is dropped
// too and renderWordmarkAt reports false (callers fall back to the font).
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

	// 1.8.9: vanilla's six panorama faces (panorama_0..5, the same cube layout
	// every later version's CubeMap reads). Plain ResourceLocations: building
	// them at class-load touches no texture.
	private static final ResourceLocation[] PANORAMA = new ResourceLocation[6];

	static {
		for (int i = 0; i < PANORAMA.length; i++) {
			PANORAMA[i] = new ResourceLocation("textures/gui/title/background/panorama_" + i + ".png");
		}
	}

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
		Minecraft mc = Minecraft.getMinecraft();
		int w = guiGraphics.guiWidth();
		int h = guiGraphics.guiHeight();

		guiGraphics.fill(0, 0, w, h, OriginTheme.BG);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		drawFrame(guiGraphics, w, h);

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
		if (progress >= 0 && progress <= 100) {
			drawBar(guiGraphics, w / 2.0, barTop, barW, barH, progress / 100f);
		} else {
			drawIndeterminateBar(guiGraphics, w / 2.0, barTop, barW, barH);
		}
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
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF); // track
		int segW = Math.max(8, (int) (barW * 0.32));
		int travel = Math.max(1, barW - segW);
		double t = (System.currentTimeMillis() % 1800L) / 1800.0;
		double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI); // smooth 0->1->0
		int segX = bx + (int) Math.round(eased * travel);
		guiGraphics.fill(segX - 1, by - 1, segX + segW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW);
		guiGraphics.fill(segX, by, segX + segW, by + barH, OriginTheme.ACCENT);
	}

	/**
	 * Menu background: the vanilla panorama, live-rotating, blurred, then graded
	 * into Origin's palette. Returns true only if the Origin backdrop actually
	 * drew; callers that cancel vanilla's own backdrop must key off this.
	 *
	 * Will picked live rotation over a cached blur knowing it is the most
	 * expensive option -- the panorama and the blur pyramid both run every frame.
	 * If that ever needs winding back, blur once into a texture on menu open and
	 * blit that instead.
	 */
	public static boolean renderTitleBackground(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			int w = guiGraphics.guiWidth();
			int h = guiGraphics.guiHeight();
			// The panorama needs off-screen targets for its blur. With framebuffers
			// unavailable (old GPU / "Use VBOs"-era drivers / fboEnable off) the
			// ring backdrop the loading screens use stands in -- same palette,
			// no blur pass needed.
			if (!renderBlurredPanorama(Minecraft.getMinecraft())) {
				guiGraphics.fill(0, 0, w, h, OriginTheme.BG);
				if (!ringsFailed) {
					drawRings(guiGraphics, w, h);
				}
			}
			drawGrade(guiGraphics, w, h);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/**
	 * The Origin grade over the blurred panorama ("Lighter grade" -- the variant
	 * that keeps most of the world's colour): one alpha blend toward cool
	 * mineral bands, one restrained jade highlight, then the edge vignette.
	 */
	private static void drawGrade(Gfx guiGraphics, int w, int h) {
		int a = 0xDC << 24;
		int y1 = h * 40 / 100, y2 = h * 62 / 100;
		guiGraphics.fillGradient(0, 0, w, y1, a | 0x0B1110, a | 0x0D1715);
		guiGraphics.fillGradient(0, y1, w, y2, a | 0x0D1715, a | 0x091210);
		guiGraphics.fillGradient(0, y2, w, h, a | 0x091210, a | 0x070B0B);
		if (radialGlowId != null) {
			Gfx.defaultBlend();
			GlStateManager.color(0x4B / 255f, 0xC8 / 255f, 0xAE / 255f, 0.09f);
			int d = (int) Math.round(w * 1.1);
			guiGraphics.blit(radialGlowId, (int) Math.round(w * 0.70 - d / 2.0), (int) Math.round(h * 0.15 - d / 2.0),
					d, d, 0f, 0f, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX);
			Gfx.resetColor();
		}
		drawVignette(guiGraphics, w, h);
	}

	// ---- Menu blur (fixed-function box-filter pyramid, 1.16.5's method) ----
	// 1.21 blurs the panorama with a gaussian post chain. Pre-1.21 builds the same
	// look the other way round: the panorama is drawn at HALF resolution, halved
	// three more times through LINEAR-filtered targets (each step an exact 2x2 box
	// average), and the 1/16-size result is stretched back over the screen through
	// bilinear filtering. Targets are rebuilt only when the window size changes.
	// 1.8.9: the off-screen target is net.minecraft.client.shader.Framebuffer
	// (bindFramebuffer(true) also sets the viewport; bindFramebufferTexture binds
	// its colour texture for reading).
	private static final Framebuffer[] BLUR_PYRAMID = new Framebuffer[4];

	/** False when framebuffers are unavailable (nothing was drawn). */
	private static boolean renderBlurredPanorama(Minecraft mc) {
		if (!OpenGlHelper.isFramebufferEnabled()) {
			return false;
		}
		int fullW = Math.max(1, mc.displayWidth), fullH = Math.max(1, mc.displayHeight);
		int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		try {
			ensurePyramid(BLUR_PYRAMID, BLUR_PYRAMID.length, fullW, fullH);
			// The panorama at half size. The cube's first (and only) pass is opaque,
			// so no clear is needed.
			BLUR_PYRAMID[0].bindFramebuffer(true);
			drawPanoramaCube(mc, BLUR_PYRAMID[0].framebufferWidth, BLUR_PYRAMID[0].framebufferHeight);
			for (int i = 1; i < BLUR_PYRAMID.length; i++) {
				BLUR_PYRAMID[i].bindFramebuffer(true);
				drawFullscreenTexture(BLUR_PYRAMID[i - 1]);
			}
		} finally {
			restoreTarget(previous);
		}
		// Back onto the frame's own target, stretched through the linear filter.
		drawFullscreenTexture(BLUR_PYRAMID[BLUR_PYRAMID.length - 1]);
		return true;
	}

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

	/**
	 * The vanilla panorama cube, live-rotating, into the bound target. A port of
	 * the CubeMap/PanoramaRenderer pair later versions use (1.8.9's own
	 * GuiMainMenu.drawPanorama is private and draws into a 256 px square for its
	 * own blur): 85 degree perspective at the target's aspect, the same pitch
	 * wobble and yaw drift (PanoramaRenderer: time in ticks, xRot = sin(t*0.001)*5
	 * + 25, yRot = -t*0.1), one opaque pass. Fixed-function: projection and
	 * model-view are pushed and restored around it.
	 */
	private static void drawPanoramaCube(Minecraft mc, int targetW, int targetH) {
		double ticks = System.currentTimeMillis() / 50.0;
		float xRot = (float) (Math.sin(ticks * 0.001) * 5.0 + 25.0);
		float yRot = (float) ((-ticks * 0.1) % 360.0);

		GlStateManager.matrixMode(GL11.GL_PROJECTION);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		Project.gluPerspective(85.0F, targetW / (float) Math.max(1, targetH), 0.05F, 10.0F);
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		GlStateManager.color(1f, 1f, 1f, 1f);
		GlStateManager.rotate(180.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.disableBlend();
		GlStateManager.disableAlpha();
		GlStateManager.disableCull();
		GlStateManager.disableDepth();
		GlStateManager.depthMask(false);
		GlStateManager.enableTexture2D();
		GlStateManager.rotate(xRot, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(yRot, 0.0F, 1.0F, 0.0F);
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		for (int k = 0; k < 6; k++) {
			mc.getTextureManager().bindTexture(PANORAMA[k]);
			wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
			switch (k) {
				case 0:
					wr.pos(-1, -1, 1).tex(0, 0).endVertex();
					wr.pos(-1, 1, 1).tex(0, 1).endVertex();
					wr.pos(1, 1, 1).tex(1, 1).endVertex();
					wr.pos(1, -1, 1).tex(1, 0).endVertex();
					break;
				case 1:
					wr.pos(1, -1, 1).tex(0, 0).endVertex();
					wr.pos(1, 1, 1).tex(0, 1).endVertex();
					wr.pos(1, 1, -1).tex(1, 1).endVertex();
					wr.pos(1, -1, -1).tex(1, 0).endVertex();
					break;
				case 2:
					wr.pos(1, -1, -1).tex(0, 0).endVertex();
					wr.pos(1, 1, -1).tex(0, 1).endVertex();
					wr.pos(-1, 1, -1).tex(1, 1).endVertex();
					wr.pos(-1, -1, -1).tex(1, 0).endVertex();
					break;
				case 3:
					wr.pos(-1, -1, -1).tex(0, 0).endVertex();
					wr.pos(-1, 1, -1).tex(0, 1).endVertex();
					wr.pos(-1, 1, 1).tex(1, 1).endVertex();
					wr.pos(-1, -1, 1).tex(1, 0).endVertex();
					break;
				case 4:
					wr.pos(-1, -1, -1).tex(0, 0).endVertex();
					wr.pos(-1, -1, 1).tex(0, 1).endVertex();
					wr.pos(1, -1, 1).tex(1, 1).endVertex();
					wr.pos(1, -1, -1).tex(1, 0).endVertex();
					break;
				default:
					wr.pos(-1, 1, 1).tex(0, 0).endVertex();
					wr.pos(-1, 1, -1).tex(0, 1).endVertex();
					wr.pos(1, 1, -1).tex(1, 1).endVertex();
					wr.pos(1, 1, 1).tex(1, 0).endVertex();
					break;
			}
			tess.draw();
		}
		GlStateManager.matrixMode(GL11.GL_PROJECTION);
		GlStateManager.popMatrix();
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.popMatrix();
		GlStateManager.depthMask(true);
		GlStateManager.enableCull();
		GlStateManager.enableAlpha();
		GlStateManager.enableDepth();
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

	// When the title screen was (re)opened -- drives the mark's entrance ease.
	// Set by OriginTitleScreen; 0 = no animation (draw the settled mark).
	private static volatile long titleOpenedAt = 0;

	public static void setTitleOpenedAt(long millis) {
		titleOpenedAt = millis;
	}

	/** Main menu: the approved Origin atom above the compact action stack. */
	public static boolean renderTitleWordmark(Gfx guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			int w = guiGraphics.guiWidth();
			int h = guiGraphics.guiHeight();
			TitleLayout L = TitleLayout.of(w, h);
			long el = titleOpenedAt > 0 ? System.currentTimeMillis() - titleOpenedAt : Long.MAX_VALUE;
			double t = el == Long.MAX_VALUE ? 1.0
					: OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, (el - 40.0) / 420.0)));
			int size = Math.max(1, (int) Math.round(L.markSize * (0.88 + 0.12 * t)));
			OriginUi.logo(guiGraphics, L.markCenterX, L.markCenterY, size, (float) t);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
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

	private static void drawRings(Gfx guiGraphics, int w, int h) {
		double cx = w / 2.0;
		double cy = h / 2.0;
		long now = System.currentTimeMillis();
		Pose pose = guiGraphics.pose();

		Gfx.defaultBlend();
		for (Ring ring : rings) {
			double revs = (now / 1000.0) / ring.periodSeconds();
			// Wrap to [0,360) in double BEFORE the float cast: the absolute angle
			// grows to ~1e10 degrees over time, which a 32-bit float quantizes to
			// ~1000-degree steps -> rings freeze then jump.
			double angle = (ring.angle0() + (ring.reverse() ? -revs : revs) * 360.0) % 360.0;
			float scale = (float) (ring.widthFrac() * w * 1.1 / TEX); // *1.1: ellipse fills 0.9 of its square texture

			pose.pushPose();
			pose.translate(cx, cy, 0);
			pose.mulPose(Pose.Axis.ZP.rotationDegrees((float) angle));
			pose.scale(scale, scale, 1f);
			pose.translate(-TEX / 2f, -TEX / 2f, 0);
			GlStateManager.color(1f, 1f, 1f, ring.opacity());
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
		GlStateManager.color(1f, 1f, 1f, 0.028f);
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
		Gfx.resetColor();
		guiGraphics.blit(vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX);
		pose.popPose();
	}

	/** Thin L-brackets inset from each screen corner, in the soft-strong stroke tone. */
	private static void drawCornerBrackets(Gfx guiGraphics, int w, int h) {
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

	/** Draws the progress bar centered on cx at barTop, with the given width/height. */
	private static void drawBar(Gfx guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;
		// Track at ~16% white: at 8% the unfilled track vanished on the near-black
		// background and the bar read as the wrong size.
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF);              // track
		int fillW = Math.round(barW * Math.max(0f, Math.min(1f, progress)));
		if (fillW > 0) {
			guiGraphics.fill(bx - 1, by - 1, bx + fillW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW); // soft glow
			guiGraphics.fill(bx, by, bx + fillW, by + barH, OriginTheme.ACCENT);  // fill
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
