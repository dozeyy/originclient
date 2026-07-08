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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.origin.client.client.theme.OriginTheme;

// Shared Origin screen rendering, used by both the loading screen
// (LoadingOverlayMixin) and the main menu (TitleScreenMixin): charcoal
// background, the pre-rendered orbital rings (mirroring the launcher's
// OriginBackground), subtle grain, and the "Origin" wordmark in the website's
// Inter font (baked to a texture so it shows instantly and carries no
// custom-glyph-rendering risk).
//
// Textures load via the classloader (not the resource manager), so this is
// safe during the earliest loading overlay while resources are still loading,
// and degrades gracefully if any asset fails rather than crashing.
public final class OriginScreenRenderer {
	private static final Gson GSON = new Gson();
	private static final int TEX = 768;
	private static final int BG_COLOR = OriginTheme.BG;

	private static boolean loaded = false;
	private static boolean ringsFailed = false;
	private static final List<Ring> rings = new ArrayList<>();
	private static ResourceLocation grainId;

	// Baked "ORIGIN" wordmark (Inter). Null -> fall back to vanilla drawString.
	private static ResourceLocation wordmarkId;
	private static int wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH;

	// Baked caption glyph strip ("LOADING 0123456789%") for the loading bar's
	// live percentage. Null -> caption is skipped (no vanilla-font fallback, to
	// avoid tofu boxes during the first resource load).
	private static ResourceLocation captionId;
	private static int capAtlasW, capAtlasH, capCellH, capCapH;
	private static final Map<Character, CaptionGlyph> captionGlyphs = new HashMap<>();

	private record Ring(ResourceLocation texture, double widthFrac, float opacity,
						double angle0, double periodSeconds, boolean reverse) {
	}

	private record CaptionGlyph(int x, int y, int width, double bearingX, double advance) {
	}

	private OriginScreenRenderer() {
	}

	// ---- Public entry points ----

	/** Loading screen: charcoal + grain + wordmark + progress bar + "LOADING xx%" caption. No rings. */
	public static void renderLoading(GuiGraphics guiGraphics, float progress) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float clamped = Math.max(0f, Math.min(1f, progress));

		guiGraphics.fill(0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawGrain(guiGraphics, w, h);
		}

		// Wordmark + bar + caption as a group, roughly centered.
		double inkH = fitInkHeight(h * 0.15, w, 0.85);
		int wordmarkBottom = drawWordmark(guiGraphics, w / 2.0, h * 0.43, inkH);
		int barBottom = drawProgressBar(guiGraphics, w, h, wordmarkBottom, clamped);

		int pct = Math.round(clamped * 100f);
		double capH = Math.max(7.0, h * 0.02);
		drawCaption(guiGraphics, w / 2.0, barBottom + Math.max(8, (int) (h * 0.02)),
				"LOADING " + pct + "%", capH, OriginTheme.MUTED);
	}

	/** Main menu background: charcoal + rotating rings + grain (behind vanilla's logo/buttons). */
	public static void renderTitleBackground(GuiGraphics guiGraphics) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		guiGraphics.fill(0, 0, w, h, BG_COLOR);
		if (!ringsFailed) {
			drawRings(guiGraphics, w, h);
			drawGrain(guiGraphics, w, h);
		}
	}

	/** Main menu: draw the "ORIGIN" wordmark centered between the top of the screen and the Singleplayer button. */
	public static void renderTitleWordmark(GuiGraphics guiGraphics) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		int singleplayerTop = h / 4 + 48;        // vanilla TitleScreen first-button Y
		double centerY = singleplayerTop / 2.0;  // midpoint between top of screen and that button
		double inkH = fitInkHeight(h * 0.16, w, 0.82);
		drawWordmark(guiGraphics, w / 2.0, centerY, inkH);
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

	private static void drawRings(GuiGraphics guiGraphics, int w, int h) {
		double cx = w / 2.0;
		double cy = h / 2.0;
		long now = System.currentTimeMillis();
		PoseStack pose = guiGraphics.pose();

		RenderSystem.enableBlend();
		for (Ring ring : rings) {
			double revs = (now / 1000.0) / ring.periodSeconds();
			double angle = ring.angle0() + (ring.reverse() ? -revs : revs) * 360.0;
			float scale = (float) (ring.widthFrac() * w * 1.1 / TEX); // *1.1: ellipse fills 0.9 of its square texture

			pose.pushPose();
			pose.translate(cx, cy, 0);
			pose.mulPose(Axis.ZP.rotationDegrees((float) angle));
			pose.scale(scale, scale, 1f);
			pose.translate(-TEX / 2f, -TEX / 2f, 0);
			RenderSystem.setShaderColor(1f, 1f, 1f, ring.opacity());
			guiGraphics.blit(ring.texture(), 0, 0, 0, 0, TEX, TEX, TEX, TEX);
			pose.popPose();
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void drawGrain(GuiGraphics guiGraphics, int w, int h) {
		if (grainId == null) {
			return;
		}
		int tile = 128;
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, 0.028f);
		for (int y = 0; y < h; y += tile) {
			for (int x = 0; x < w; x += tile) {
				guiGraphics.blit(grainId, x, y, 0, 0, tile, tile, tile, tile);
			}
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			guiGraphics.blit(wordmarkId, 0, 0, 0, 0, wmTexW, wmTexH, wmTexW, wmTexH);
			pose.popPose();

			return (int) Math.round(inkCenterY + (wmInkH * scale) / 2.0);
		}

		// Fallback (texture missing): vanilla font, centered on the point.
		Font font = Minecraft.getInstance().font;
		String mark = "ORIGIN";
		float scale = 4.0f;
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(inkCenterX, inkCenterY, 0);
		pose.scale(scale, scale, 1f);
		int textW = font.width(mark);
		guiGraphics.drawString(font, mark, -textW / 2, -4, OriginTheme.TEXT, false);
		pose.popPose();
		return (int) Math.round(inkCenterY + 5 * scale);
	}

	/** Draws the progress bar just below the wordmark; returns the bar's bottom Y. */
	private static int drawProgressBar(GuiGraphics guiGraphics, int w, int h, int wordmarkBottom, float progress) {
		int barW = Math.max(120, (int) (w * 0.22));
		int barH = 3;
		int bx = (w - barW) / 2;
		int by = wordmarkBottom + Math.max(14, (int) (h * 0.05)); // right under the wordmark

		guiGraphics.fill(bx, by, bx + barW, by + barH, OriginTheme.STROKE);       // track
		int fillW = Math.round(barW * progress);
		if (fillW > 0) {
			guiGraphics.fill(bx - 1, by - 1, bx + fillW + 1, by + barH + 1, OriginTheme.ACCENT_GLOW); // soft glow
			guiGraphics.fill(bx, by, bx + fillW, by + barH, OriginTheme.ACCENT);  // fill
		}
		return by + barH;
	}

	/** Composes a caption from the baked glyph strip, centered horizontally on centerX, tinted `color`. */
	private static void drawCaption(GuiGraphics guiGraphics, double centerX, double topY, String text, double targetCapHeight, int color) {
		if (captionId == null) {
			return; // no vanilla-font fallback: avoids tofu during the first resource load
		}
		double scale = targetCapHeight / capCapH;

		double total = 0;
		for (int i = 0; i < text.length(); i++) {
			CaptionGlyph g = captionGlyphs.get(text.charAt(i));
			if (g != null) {
				total += g.advance();
			}
		}

		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;

		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(r, g, b, a);
		PoseStack pose = guiGraphics.pose();
		double penX = centerX - (total * scale) / 2.0;
		for (int i = 0; i < text.length(); i++) {
			CaptionGlyph glyph = captionGlyphs.get(text.charAt(i));
			if (glyph == null) {
				continue;
			}
			if (glyph.width() > 0) {
				pose.pushPose();
				pose.translate(penX + glyph.bearingX() * scale, topY, 0);
				pose.scale((float) scale, (float) scale, 1f);
				guiGraphics.blit(captionId, 0, 0, glyph.x(), glyph.y(), glyph.width(), capCellH, capAtlasW, capAtlasH);
				pose.popPose();
			}
			penX += glyph.advance() * scale;
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	// ---- Loading ----

	private static synchronized void ensureLoaded() {
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
			grainId = registerTexture(mc, "origin_grain", "/assets/originclient/textures/ui/grain.png");
		} catch (Exception e) {
			ringsFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin screen ring/grain textures failed to load; using plain background", e);
		}

		// Wordmark loads separately: a failure here falls back to vanilla-font
		// text without disabling the ring/grain background.
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
			wordmarkId = registerTexture(mc, "origin_wordmark", "/assets/originclient/textures/ui/wordmark.png");
		} catch (Exception e) {
			wordmarkId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin wordmark failed to load; using vanilla font", e);
		}

		// Caption glyph strip (for the loading-bar percentage). Optional.
		try {
			Minecraft mc = Minecraft.getInstance();
			JsonObject cap;
			try (InputStream in = open("/assets/originclient/textures/ui/caption.json")) {
				cap = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			}
			capAtlasW = cap.get("atlasWidth").getAsInt();
			capAtlasH = cap.get("atlasHeight").getAsInt();
			capCellH = cap.get("cellHeight").getAsInt();
			capCapH = cap.get("capHeight").getAsInt();
			JsonObject glyphs = cap.getAsJsonObject("glyphs");
			for (String key : glyphs.keySet()) {
				JsonObject g = glyphs.getAsJsonObject(key);
				double bearingX = g.has("bearingX") ? g.get("bearingX").getAsDouble() : 0.0;
				captionGlyphs.put(key.charAt(0), new CaptionGlyph(
						g.get("x").getAsInt(), g.get("y").getAsInt(), g.get("width").getAsInt(),
						bearingX, g.get("advance").getAsDouble()));
			}
			captionId = registerTexture(mc, "origin_caption", "/assets/originclient/textures/ui/caption.png");
		} catch (Exception e) {
			captionId = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin caption strip failed to load; skipping caption", e);
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
