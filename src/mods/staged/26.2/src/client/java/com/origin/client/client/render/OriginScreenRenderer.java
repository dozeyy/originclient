package com.origin.client.client.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;

// Shared Origin screen rendering, used by both the loading screen
// (LoadingOverlayMixin) and the main menu (TitleScreenMixin): near-black
// (#050505) background, the pre-rendered crisp orbital rings (mirroring the
// launcher's OriginBackground), fine grain, and the "Origin" wordmark in the
// website's Inter font (baked to a texture so it shows instantly and carries no
// custom-glyph-rendering risk).
//
// 26.2 render port: immediate-mode GuiGraphics -> retained-mode
// GuiGraphicsExtractor; texture tint folds into the blit color arg (no
// RenderSystem.setShaderColor), blend is per-RenderPipeline, and pose() is the 2D
// org.joml.Matrix3x2fStack. Textures load via the classloader (not the resource
// manager), so this is safe during the earliest loading overlay, and degrades
// gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {
	private static final Gson GSON = new Gson();
	private static final int TEX = 768;
	private static final int BG_COLOR = OriginTheme.BG;

	// Untinted draw = full white; alpha-only tint packs onto white.
	private static final int WHITE = 0xFFFFFFFF;

	private static int alpha(double a) {
		int ai = Math.max(0, Math.min(255, (int) Math.round(a * 255.0)));
		return (ai << 24) | 0x00FFFFFF;
	}

	// Fail-soft master switch: if ANY Origin screen draw throws (e.g. a
	// Minecraft GUI API that renamed/changed shape in a different game
	// version), rendering flips to vanilla permanently for this session
	// instead of crashing.
	private static volatile boolean broken = false;

	private static volatile boolean loaded = false;
	private static boolean ringsFailed = false;
	private static final List<Ring> rings = new ArrayList<>();
	private static Identifier grainId;

	// Edge vignette (transparent core -> soft black corners).
	private static Identifier vignetteId;
	private static final int VIGNETTE_TEX = 1024;

	// Baked "ORIGIN" wordmark. Null -> fall back to vanilla drawString.
	private static Identifier wordmarkId;
	private static int wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH;
	// Per-letter reveal bands: [x0,x1] texture-px columns, one per glyph.
	private static int[] wmLetterX0, wmLetterX1;

	// Loading-screen animation clock.
	private static long loadStartMs = 0L;

	// ---- Main-menu ambient layer ----
	private static final double RING_HEIGHT_RATIO = 0.37;
	private static final double BREATH_MS = 3200.0;
	private static final double[][] BODIES = {
			{0, 26.0, 0.0, 0.010, 0.85},
			{2, 40.0, 0.5, 0.008, 0.55},
	};
	private static final int PARTICLE_COUNT = 28;

	// Cursor-follow glow (the website's two-layer spotlight).
	private static Identifier radialGlowId;
	private static final int RADIAL_TEX = 512;
	private static double haloX = Double.NaN, haloY = Double.NaN;
	private static double glowHover = 0.0;
	private static long glowLastNanos = 0L;

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

	public static void renderLoading(GuiGraphicsExtractor guiGraphics, float progress) {
		if (broken) {
			return;
		}
		try {
			renderLoading0(guiGraphics, progress);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void renderLoading0(GuiGraphicsExtractor guiGraphics, float progress) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		if (loadStartMs == 0L) {
			loadStartMs = System.currentTimeMillis();
		}
		long elapsed = System.currentTimeMillis() - loadStartMs;

		guiGraphics.fill(0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		drawVignette(guiGraphics, w, h);

		double markInkH = fitInkHeight(h * 0.135, w, 0.82);
		double markCenterY = h * 0.48;
		drawWordmarkReveal(guiGraphics, w / 2.0, markCenterY, markInkH, elapsed);

		double dispW = (wordmarkId != null && wmInkH > 0) ? wmInkW * (markInkH / wmInkH) : w * 0.24;
		int barW = (int) Math.round(dispW);
		int barH = Math.max(3, (int) Math.round(h * 0.012));
		int barTop = (int) Math.round(markCenterY + markInkH * 1.15);
		drawBar(guiGraphics, w / 2.0, barTop, barW, barH, clamped);

		int captionY = barTop + barH + Math.max(8, (int) Math.round(h * 0.035));
		drawCaption(guiGraphics, w / 2.0, captionY, elapsed);
		drawCornerBrackets(guiGraphics, w, h);
	}

	public static boolean renderLoadingScene(GuiGraphicsExtractor guiGraphics, net.minecraft.network.chat.Component title) {
		if (broken) {
			return false;
		}
		try {
			renderLoadingScene0(guiGraphics, title);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void renderLoadingScene0(GuiGraphicsExtractor guiGraphics, net.minecraft.network.chat.Component title) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		guiGraphics.fill(0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
		drawFrame(guiGraphics, w, h);

		Font font = mc.font;
		int barW = Math.max(80, (int) (w * 0.30));
		int barH = Math.max(2, (int) (h * 0.010));
		float titleScale = 1.5f;
		int titleH = (int) Math.ceil(font.lineHeight * titleScale);
		double gap = Math.max(8.0, h * 0.045);

		String s = title == null ? "" : title.getString().trim();
		boolean hasTitle = !s.isEmpty();
		double groupH = (hasTitle ? titleH + gap : 0) + barH;
		double groupTop = h / 2.0 - groupH / 2.0;

		if (hasTitle) {
			Matrix3x2fStack pose = guiGraphics.pose();
			pose.pushMatrix();
			pose.translate((float) (w / 2.0), (float) groupTop);
			pose.scale(titleScale, titleScale);
			guiGraphics.text(font, s, -font.width(s) / 2, 0, OriginTheme.TEXT, false);
			pose.popMatrix();
		}
		int barTop = (int) Math.round(groupTop + (hasTitle ? titleH + gap : 0));
		drawIndeterminateBar(guiGraphics, w / 2.0, barTop, barW, barH);
	}

	public static void renderConnectingBar(GuiGraphicsExtractor guiGraphics) {
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

	private static void drawIndeterminateBar(GuiGraphicsExtractor guiGraphics, double cx, int barTop, int barW, int barH) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF); // track
		int segW = Math.max(8, (int) (barW * 0.32));
		int travel = Math.max(1, barW - segW);
		double t = (System.currentTimeMillis() % 1800L) / 1800.0;
		double eased = 0.5 - 0.5 * Math.cos(t * 2.0 * Math.PI);
		int segX = bx + (int) Math.round(eased * travel);
		guiGraphics.fill(segX - 1, by - 1, segX + segW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW);
		guiGraphics.fill(segX, by, segX + segW, by + barH, OriginTheme.ACCENT);
	}

	public static boolean renderTitleBackground(GuiGraphicsExtractor guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();

			guiGraphics.fill(0, 0, w, h, BG_COLOR);
			if (!ringsFailed) {
				drawRings(guiGraphics, w, h);
				drawGrain(guiGraphics, w, h);
			}
			drawParticles(guiGraphics, w, h);
			drawOrbitingBodies(guiGraphics, w, h);
			drawFrame(guiGraphics, w, h);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	public static void renderTitleCursorGlow(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
		if (broken) {
			return;
		}
		try {
			renderTitleCursorGlow0(guiGraphics, mouseX, mouseY, hoveringClickable);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void renderTitleCursorGlow0(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hoveringClickable) {
		ensureLoaded();
		if (radialGlowId == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();

		long now = System.nanoTime();
		double dtMs = glowLastNanos == 0 ? 16.7 : Math.min(100.0, (now - glowLastNanos) / 1_000_000.0);
		glowLastNanos = now;

		double target = hoveringClickable ? 1.0 : 0.0;
		double step = dtMs / 250.0;
		glowHover = target > glowHover ? Math.min(target, glowHover + step) : Math.max(target, glowHover - step);
		double hv = OriginTheme.easeOut(glowHover);

		if (Double.isNaN(haloX)) {
			haloX = mouseX;
			haloY = mouseY;
		}
		double f = 1.0 - Math.pow(1.0 - 0.38, dtMs / 16.7);
		haloX += (mouseX - haloX) * f;
		haloY += (mouseY - haloY) * f;

		drawRadial(guiGraphics, haloX, haloY, w * (0.14 + 0.04 * hv), 0.112 + 0.063 * hv);
		drawRadial(guiGraphics, mouseX, mouseY, w * (0.032 + 0.018 * hv), 0.30 + 0.17 * hv);
	}

	private static void drawRadial(GuiGraphicsExtractor guiGraphics, double cx, double cy, double diameter, double a) {
		int d = Math.max(2, (int) Math.round(diameter));
		// region 512x512 -> dest d x d, alpha-only tint.
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, radialGlowId,
				(int) Math.round(cx - d / 2.0), (int) Math.round(cy - d / 2.0),
				0f, 0f, d, d, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, RADIAL_TEX, alpha(a));
	}

	public static boolean renderTitleWordmark(GuiGraphicsExtractor guiGraphics) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			Minecraft mc = Minecraft.getInstance();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();

			int singleplayerTop = h / 4 + 48;
			double centerY = singleplayerTop / 2.0;
			double inkH = fitInkHeight(h * 0.13, w, 0.82);
			double pulse = 0.5 - 0.5 * Math.cos(System.currentTimeMillis() / BREATH_MS * 2.0 * Math.PI);
			drawWordmarkGlow(guiGraphics, w / 2.0, centerY, inkH, 1.06, (float) (0.05 + 0.10 * pulse));
			drawWordmark(guiGraphics, w / 2.0, centerY, inkH);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	public static boolean renderWordmarkAt(GuiGraphicsExtractor guiGraphics, double cx, double cy, double targetInkHeight, float a) {
		if (broken) {
			return false;
		}
		try {
			ensureLoaded();
			if (wordmarkId == null || wmInkH <= 0) {
				return false;
			}
			drawBakedInk(guiGraphics, wordmarkId, wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH, cx, cy, targetInkHeight, a);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void drawBakedInk(GuiGraphicsExtractor guiGraphics, Identifier tex, int texW, int texH,
									 int inkX, int inkY, int inkW, int inkH, double cx, double cy, double targetInkHeight, float a) {
		float scale = (float) (targetInkHeight / inkH);
		double icx = (inkX + inkW / 2.0) * scale;
		double icy = (inkY + inkH / 2.0) * scale;
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.translate((float) (cx - icx), (float) (cy - icy));
		pose.scale(scale, scale);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, texW, texH, texW, texH, alpha(a));
		pose.popMatrix();
	}

	// Main menu: account chip (Origin ring mark + username) in the top-left. The
	// 26.2 player-face API (PlayerFaceRenderer.draw / SkinManager.getInsecureSkin)
	// was removed in the retained-mode overhaul, so the head uses the Origin mark
	// (the chip's existing fallback) rather than the player's skin.
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
			int w = mc.getWindow().getGuiScaledWidth();
			int head = 18;
			int padX = 8, padY = 6, gap = 8;
			int chipH = head + padY * 2;
			int chipW = padX + head + gap + font.width(name) + padX;
			int x = Math.max(10, (int) Math.round(w * 0.03));
			int y = x;

			OriginUi.panel(guiGraphics, x, y, chipW, chipH, OriginTheme.RADIUS_MD,
					OriginTheme.PANEL_TRANSLUCENT, 0);

			int hx = x + padX, hy = y + padY;
			OriginUi.logo(guiGraphics, hx + head / 2.0, hy + head / 2.0, head, 1f);

			guiGraphics.text(font, name, hx + head + gap,
					y + (chipH - font.lineHeight) / 2, OriginTheme.TEXT, false);
		} catch (Throwable t) {
			fail(t);
		}
	}

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

	private static void drawRings(GuiGraphicsExtractor guiGraphics, int w, int h) {
		double cx = w / 2.0;
		double cy = h / 2.0;
		long now = System.currentTimeMillis();
		Matrix3x2fStack pose = guiGraphics.pose();

		for (Ring ring : rings) {
			double revs = (now / 1000.0) / ring.periodSeconds();
			double angle = (ring.angle0() + (ring.reverse() ? -revs : revs) * 360.0) % 360.0;
			float scale = (float) (ring.widthFrac() * w * 1.1 / TEX);

			pose.pushMatrix();
			pose.translate((float) cx, (float) cy);
			pose.rotate((float) Math.toRadians(angle));
			pose.scale(scale, scale);
			pose.translate(-TEX / 2f, -TEX / 2f);
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, ring.texture(), 0, 0, 0f, 0f, TEX, TEX, TEX, TEX, alpha(ring.opacity()));
			pose.popMatrix();
		}
	}

	private static void drawGrain(GuiGraphicsExtractor guiGraphics, int w, int h) {
		if (grainId == null) {
			return;
		}
		double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		int realW = (int) Math.ceil(w * gs);
		int realH = (int) Math.ceil(h * gs);
		int tile = 256;
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs));
		for (int y = 0; y < realH; y += tile) {
			for (int x = 0; x < realW; x += tile) {
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, grainId, x, y, 0f, 0f, tile, tile, tile, tile, alpha(0.028));
			}
		}
		pose.popMatrix();
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}

	private static void drawParticles(GuiGraphicsExtractor guiGraphics, int w, int h) {
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

	private static void drawOrbitingBodies(GuiGraphicsExtractor guiGraphics, int w, int h) {
		if (ringsFailed || rings.isEmpty() || radialGlowId == null) {
			return;
		}
		double cx = w / 2.0, cy = h / 2.0;
		double now = System.currentTimeMillis() / 1000.0;
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
	}

	private static void drawWordmarkGlow(GuiGraphicsExtractor guiGraphics, double inkCenterX, double inkCenterY,
										 double targetInkHeight, double scaleMul, float a) {
		if (wordmarkId == null || wmInkH <= 0 || a <= 0.001f) {
			return;
		}
		float scale = (float) (targetInkHeight / wmInkH * scaleMul);
		double icx = (wmInkX + wmInkW / 2.0) * scale;
		double icy = (wmInkY + wmInkH / 2.0) * scale;
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.translate((float) (inkCenterX - icx), (float) (inkCenterY - icy));
		pose.scale(scale, scale);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, wordmarkId, 0, 0, 0f, 0f, wmTexW, wmTexH, wmTexW, wmTexH, alpha(a));
		pose.popMatrix();
	}

	private static void drawFrame(GuiGraphicsExtractor guiGraphics, int w, int h) {
		drawVignette(guiGraphics, w, h);
		drawCornerBrackets(guiGraphics, w, h);
	}

	private static void drawVignette(GuiGraphicsExtractor guiGraphics, int w, int h) {
		if (vignetteId == null) {
			return;
		}
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.scale((float) w / VIGNETTE_TEX, (float) h / VIGNETTE_TEX);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, vignetteId, 0, 0, 0f, 0f, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, VIGNETTE_TEX, WHITE);
		pose.popMatrix();
	}

	private static void drawCornerBrackets(GuiGraphicsExtractor guiGraphics, int w, int h) {
		int inset = Math.max(10, (int) Math.round(w * 0.022));
		int len = Math.max(10, (int) Math.round(w * 0.018));
		int th = Math.max(1, (int) Math.round(w * 0.0015));
		int c = OriginTheme.STROKE_STRONG;
		guiGraphics.fill(inset, inset, inset + len, inset + th, c);
		guiGraphics.fill(inset, inset, inset + th, inset + len, c);
		guiGraphics.fill(w - inset - len, inset, w - inset, inset + th, c);
		guiGraphics.fill(w - inset - th, inset, w - inset, inset + len, c);
		guiGraphics.fill(inset, h - inset - th, inset + len, h - inset, c);
		guiGraphics.fill(inset, h - inset - len, inset + th, h - inset, c);
		guiGraphics.fill(w - inset - len, h - inset - th, w - inset, h - inset, c);
		guiGraphics.fill(w - inset - th, h - inset - len, w - inset, h - inset, c);
	}

	private static void drawWordmarkReveal(GuiGraphicsExtractor guiGraphics, double inkCenterX, double inkCenterY,
										   double targetInkHeight, long elapsedMs) {
		if (wordmarkId == null || wmLetterX0 == null || wmInkH <= 0) {
			drawWordmark(guiGraphics, inkCenterX, inkCenterY, targetInkHeight);
			return;
		}
		float scale = (float) (targetInkHeight / wmInkH);
		double icx = (wmInkX + wmInkW / 2.0) * scale;
		double icy = (wmInkY + wmInkH / 2.0) * scale;
		double rise = wmInkH * 0.10;
		double stagger = 55.0;
		double dur = 300.0;

		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.translate((float) (inkCenterX - icx), (float) (inkCenterY - icy));
		pose.scale(scale, scale);
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
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, wordmarkId, x0, yoff, (float) x0, 0f, bw, wmTexH, bw, wmTexH, wmTexW, wmTexH, alpha(eased));
		}
		pose.popMatrix();
	}

	private static void drawCaption(GuiGraphicsExtractor guiGraphics, double cx, int y, long elapsedMs) {
		Font font = Minecraft.getInstance().font;
		String base = "LOADING";
		int tracking = 2;
		int dots = (int) ((elapsedMs / 400L) % 4L);
		int dotW = font.width(".") + tracking;
		int textW = 0;
		for (int i = 0; i < base.length(); i++) {
			textW += font.width(String.valueOf(base.charAt(i))) + tracking;
		}
		double penX = cx - (textW + 3 * dotW) / 2.0;
		for (int i = 0; i < base.length(); i++) {
			String ch = String.valueOf(base.charAt(i));
			guiGraphics.text(font, ch, (int) Math.round(penX), y, OriginTheme.MUTED, false);
			penX += font.width(ch) + tracking;
		}
		for (int i = 0; i < dots; i++) {
			guiGraphics.text(font, ".", (int) Math.round(penX), y, OriginTheme.MUTED, false);
			penX += dotW;
		}
	}

	private static int drawWordmark(GuiGraphicsExtractor guiGraphics, double inkCenterX, double inkCenterY, double targetInkHeight) {
		if (wordmarkId != null) {
			float scale = (float) (targetInkHeight / wmInkH);
			double icx = (wmInkX + wmInkW / 2.0) * scale;
			double icy = (wmInkY + wmInkH / 2.0) * scale;

			Matrix3x2fStack pose = guiGraphics.pose();
			pose.pushMatrix();
			pose.translate((float) (inkCenterX - icx), (float) (inkCenterY - icy));
			pose.scale(scale, scale);
			guiGraphics.blit(RenderPipelines.GUI_TEXTURED, wordmarkId, 0, 0, 0f, 0f, wmTexW, wmTexH, wmTexW, wmTexH, WHITE);
			pose.popMatrix();

			return (int) Math.round(inkCenterY + (wmInkH * scale) / 2.0);
		}

		Font font = Minecraft.getInstance().font;
		String mark = "ORIGIN";
		float scale = 4.0f;
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		pose.translate((float) inkCenterX, (float) inkCenterY);
		pose.scale(scale, scale);
		int textW = font.width(mark);
		guiGraphics.text(font, mark, -textW / 2, -4, OriginTheme.TEXT, false);
		pose.popMatrix();
		return (int) Math.round(inkCenterY + 5 * scale);
	}

	private static void drawBar(GuiGraphicsExtractor guiGraphics, double cx, int barTop, int barW, int barH, float progress) {
		int bx = (int) Math.round(cx - barW / 2.0);
		int by = barTop;
		guiGraphics.fill(bx, by, bx + barW, by + barH, 0x29FFFFFF);              // track
		int fillW = Math.round(barW * progress);
		if (fillW > 0) {
			guiGraphics.fill(bx - 1, by - 1, bx + fillW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW); // soft glow
			guiGraphics.fill(bx, by, bx + fillW, by + barH, OriginTheme.ACCENT);  // fill
		}
	}

	// ---- Loading ----

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
			grainId = registerTexture(mc, "origin_grain", "/assets/originclient/textures/ui/grain.png");
		} catch (Exception e) {
			ringsFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin screen ring/grain textures failed to load; using plain background", e);
		}

		try {
			Minecraft mc = Minecraft.getInstance();
			vignetteId = registerTexture(mc, "origin_vignette", "/assets/originclient/textures/ui/vignette.png");
		} catch (Exception e) {
			vignetteId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin vignette failed to load; skipping edge vignette", e);
		}

		try {
			Minecraft mc = Minecraft.getInstance();
			JsonObject wm;
			try (InputStream in = open("/assets/originclient/textures/ui/wordmark.json")) {
				wm = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			wmTexW = wm.get("width").getAsInt();
			wmTexH = wm.get("height").getAsInt();
			wmInkX = wm.get("inkX").getAsInt();
			wmInkY = wm.get("inkY").getAsInt();
			wmInkW = wm.get("inkWidth").getAsInt();
			wmInkH = wm.get("inkHeight").getAsInt();
			if (wm.has("letters")) {
				var la = wm.getAsJsonArray("letters");
				int[] x0 = new int[la.size()];
				int[] x1 = new int[la.size()];
				for (int i = 0; i < la.size(); i++) {
					var band = la.get(i).getAsJsonArray();
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

		try {
			Minecraft mc = Minecraft.getInstance();
			radialGlowId = registerTexture(mc, "origin_radial_glow", "/assets/originclient/textures/ui/radial_glow.png");
		} catch (Exception e) {
			radialGlowId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin radial glow failed to load; skipping cursor glow", e);
		}
	}

	private static Identifier registerTexture(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		Identifier id = Identifier.fromNamespaceAndPath("originclient", name);
		DynamicTexture texture = new DynamicTexture(() -> name, image);
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
