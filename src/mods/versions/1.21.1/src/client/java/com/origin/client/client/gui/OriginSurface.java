package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The clean control surface (Will's approved mockup + his "add more texture so
 * they don't look so plain", 2026-10-19). One button =
 *
 *   1. the BODY: an exact rounded rect (radius 1 GUI px) with a 1-physical-px
 *      hairline, drawn by the SDF round shader in physical-pixel space, and
 *   2. the DETAIL: one baked texture per physical size, blitted 1:1 over it —
 *      fine static grain, a soft top highlight + gradient (a hint of bevel), a
 *      bottom inner shade and a crisp inner hairline. Baked once on the CPU, so
 *      the texture costs one blit per button per frame, nothing per pixel.
 *
 * Masks are baked at PHYSICAL resolution (GUI size x guiScale) and blitted
 * with the pose scaled to physical pixels, so every grain dot and highlight
 * row lands on exactly one screen pixel at any GUI scale (smooth-ui-baked-masks).
 * The cache holds the few sizes a screen uses; old ones are released.
 */
public final class OriginSurface {
	private OriginSurface() {
	}

	private static final int CACHE_MAX = 48;
	private static final Map<Long, ResourceLocation> DETAIL = new LinkedHashMap<>(64, 0.75f, true);
	private static int nextId = 0;

	/** Physical-pixel GUI scale (integer in vanilla, 1 at minimum). */
	public static int scale() {
		return (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	/**
	 * Draws the surface over the GUI-px box (x, y, w, h).
	 * @param fill   body colour (ARGB)
	 * @param border hairline colour (ARGB, alpha 0 = none)
	 * @param detail detail-overlay strength 0..1 (0 skips the blit)
	 */
	public static void draw(GuiGraphics g, int x, int y, int w, int h, int fill, int border, float detail) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int gs = scale();
		int pw = w * gs, ph = h * gs;
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(1f / gs, 1f / gs, 1f);
		// Radius: 1 GUI px (2/3/4 physical px at scale 2/3/4) — near-square, as
		// the launcher's corners. aaPad 0: straight edges stay crisp.
		OriginUi.roundRect(g, 0, 0, pw, ph, gs, fill, border, 0f);
		if (detail > 0.01f) {
			ResourceLocation tex = detail(pw, ph, gs);
			if (tex != null) {
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				RenderSystem.setShaderColor(1f, 1f, 1f, Math.min(1f, detail));
				g.blit(tex, 0, 0, 0f, 0f, pw, ph, pw, ph);
				RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			}
		}
		pose.popPose();
	}

	/** A 1-physical-px ring just outside the box (keyboard focus). */
	public static void focusRing(GuiGraphics g, int x, int y, int w, int h, int argb) {
		int gs = scale();
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(1f / gs, 1f / gs, 1f);
		int o = gs; // 1 GUI px offset outside the control
		OriginUi.roundRect(g, -o, -o, w * gs + 2 * o, h * gs + 2 * o, 2 * gs, 0, argb, 0f);
		pose.popPose();
	}

	/** Fills a GUI-px span at physical precision: x0..x1 may be fractional (bars). */
	public static void fillPhysical(GuiGraphics g, double x0, double y0, double x1, double y1, int argb) {
		int gs = scale();
		int px0 = (int) Math.round(x0 * gs), px1 = (int) Math.round(x1 * gs);
		int py0 = (int) Math.round(y0 * gs), py1 = (int) Math.round(y1 * gs);
		if (px1 <= px0 || py1 <= py0) {
			return;
		}
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		g.fill(px0, py0, px1, py1, argb);
		pose.popPose();
	}

	private static ResourceLocation detail(int pw, int ph, int r) {
		if (pw > 2048 || ph > 512) {
			return null; // never bake something huge; the body alone still reads fine
		}
		long key = ((long) pw << 32) | ((long) ph << 8) | r;
		ResourceLocation id = DETAIL.get(key);
		if (id != null) {
			return id;
		}
		try {
			NativeImage img = bake(pw, ph, r);
			DynamicTexture tex = new DynamicTexture(img);
			tex.setFilter(false, false); // 1:1 blit, nearest: every dot on one pixel
			id = ResourceLocation.fromNamespaceAndPath("originclient", "surface/detail_" + (nextId++));
			Minecraft.getInstance().getTextureManager().register(id, tex);
			DETAIL.put(key, id);
			if (DETAIL.size() > CACHE_MAX) {
				var it = DETAIL.entrySet().iterator();
				var eldest = it.next();
				it.remove();
				Minecraft.getInstance().getTextureManager().release(eldest.getValue());
			}
			return id;
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin surface detail bake failed; plain surface", t);
			return null;
		}
	}

	/**
	 * Bakes the detail layers, composited "over" in premultiplied space and
	 * stored straight-alpha (the default GUI blend). Every layer stays INSIDE
	 * the hairline (1px in) and inside the rounded corners. Values are tuned to
	 * be felt, not seen: clean, not busy.
	 */
	private static NativeImage bake(int pw, int ph, int r) {
		NativeImage img = new NativeImage(pw, ph, true);
		float topBand = Math.max(2f, ph * 0.45f);
		float botBand = Math.max(2f, ph * 0.30f);
		for (int y = 0; y < ph; y++) {
			for (int x = 0; x < pw; x++) {
				float cov = coverage(x, y, pw, ph, r);
				if (cov <= 0f) {
					img.setPixelRGBA(x, y, 0);
					continue;
				}
				// premultiplied accumulator: c = white-ness * a
				float a = 0f, c = 0f;
				// grain: static hash noise, white or black, about +-5% alpha
				float n = hash(x, y) * 2f - 1f;
				float ga = Math.abs(n) * 0.05f;
				float[] o = over(c, a, n > 0 ? 1f : 0f, ga);
				c = o[0];
				a = o[1];
				// top gradient: white 7% fading out over the top 45%
				if (y < topBand) {
					o = over(c, a, 1f, 0.07f * (1f - y / topBand));
					c = o[0];
					a = o[1];
				}
				// bottom inner shade: black up to 9% over the bottom 30%
				float fromBottom = ph - 1 - y;
				if (fromBottom < botBand) {
					o = over(c, a, 0f, 0.09f * (1f - fromBottom / botBand));
					c = o[0];
					a = o[1];
				}
				// crisp edges just inside the hairline: a bright top row (the bevel
				// highlight), a dark bottom row, faint side rows (inner hairline)
				if (y == 1) {
					o = over(c, a, 1f, 0.16f);
				} else if (y == ph - 2) {
					o = over(c, a, 0f, 0.18f);
				} else if (x == 1 || x == pw - 2) {
					o = over(c, a, 1f, 0.05f);
				}
				c = o[0];
				a = o[1];
				a *= cov;
				c *= cov;
				int alpha = Math.round(Math.min(1f, a) * 255f);
				int v = a > 0f ? Math.round(Math.min(1f, c / a) * 255f) : 0;
				// NativeImage stores ABGR; grey (r = g = b) makes the order moot.
				img.setPixelRGBA(x, y, (alpha << 24) | (v << 16) | (v << 8) | v);
			}
		}
		return img;
	}

	/** Coverage 0..1 of pixel (x, y) by the box inset 1px (inside the hairline), rounded corners. */
	private static float coverage(int x, int y, int pw, int ph, int r) {
		if (x < 1 || y < 1 || x > pw - 2 || y > ph - 2) {
			return 0f;
		}
		float ri = Math.max(0f, r - 1f);
		if (ri <= 0f) {
			return 1f;
		}
		float cx = x + 0.5f, cy = y + 0.5f;
		float lx = 1f + ri, rx = pw - 1f - ri, ty = 1f + ri, by = ph - 1f - ri;
		float dx = cx < lx ? lx - cx : (cx > rx ? cx - rx : 0f);
		float dy = cy < ty ? ty - cy : (cy > by ? cy - by : 0f);
		if (dx == 0f || dy == 0f) {
			return 1f;
		}
		float d = (float) Math.sqrt(dx * dx + dy * dy) - ri;
		return Math.max(0f, Math.min(1f, 0.5f - d));
	}

	private static float[] over(float c, float a, float srcWhite, float srcA) {
		return new float[]{srcWhite * srcA + c * (1f - srcA), srcA + a * (1f - srcA)};
	}

	/** Stable per-pixel hash in [0,1): the grain is the same every frame. */
	private static float hash(int x, int y) {
		int h = x * 374761393 + y * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}
}
