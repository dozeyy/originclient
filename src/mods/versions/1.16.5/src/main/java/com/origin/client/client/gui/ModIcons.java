package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Origin's OWN icon set for the mod menu (2026-09 redesign, Will's call: no more
 * Minecraft items as icons). One family built to the Design Assets / Icon
 * Packs rules — 24-grid, 1.55 px stroke, round caps, one concept per glyph —
 * baked by tools/mod-menu/generate_icons.py into matched alpha-mask and signed-
 * distance atlases (192 px cells). Every icon is tinted at draw time, so the set
 * follows the Ion Jade palette by construction (TEXT on a card, ACCENT when the
 * mod is on, MUTED in a field), while screen-space SDF coverage keeps it crisp.
 *
 * The menu chrome (search, settings tabs, HUD editor, backing toggle) draws its
 * glyphs from the same atlas under '@' ids so the whole menu is one weight.
 *
 * Fails soft: if the atlas or its metrics are missing, {@link #draw} draws
 * nothing and the menu keeps working — a card with no icon beats a crash.
 */
public final class ModIcons {
	private static final Gson GSON = new Gson();
	private static final ResourceLocation ATLAS = new ResourceLocation("originclient", "ui_mod_icons");
	private static final ResourceLocation PX_ATLAS = new ResourceLocation("originclient", "ui_mod_icons_px");

	private static final Map<String, int[]> CELLS = new HashMap<>();
	private static int cell = 192, atlasW = 1152, atlasH = 1344;
	private static volatile boolean loaded = false;
	private static boolean ok = false;

	private ModIcons() {
	}

	/**
	 * Loads (uploads) the atlas now (OriginAssets' per-frame upload slices).
	 */
	public static boolean preload() {
		ensureLoaded();
		return true;
	}

	/** Whether this id has a glyph in the atlas. */
	public static boolean has(String id) {
		ensureLoaded();
		return ok && CELLS.containsKey(id);
	}

	/** Compatibility overload: white icon at the given alpha (0..1). */
	public static void draw(Gfx g, String id, int x, int y, int size, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		draw(g, id, x, y, size, (a << 24) | 0xFFFFFF);
	}

	/**
	 * Draws the glyph for `id` in a size x size box at (x,y), tinted with `argb`
	 * (its alpha carries the mod menu's open/close fade). The signed-distance
	 * path reconstructs coverage in screen space, keeping curves clean at every
	 * Minecraft GUI scale. A linear-filtered mask remains as a fail-soft fallback.
	 */
	public static void draw(Gfx g, String id, int x, int y, int size, int argb) {
		// Vanilla text-shadow for icons (Will, 2026-10-20): the same glyph again 1 GUI px right +
		// 1 down at a quarter of the tint (what Font.drawShadow does), drawn under the icon. Same
		// alpha, so hover/disabled/fade tints carry over; the offset is in GUI px so it tracks
		// GUI scale like text. Always on, no setting.
		// Sizes here are GUI px: the crisp bake is made at size x guiScale device px.
		drawShadowed(g, id, x, y, size, (int) Math.round(size * Minecraft.getInstance().getWindow().getGuiScale()), argb);
	}

	/**
	 * {@link #draw(Gfx, String, int, int, int, int)} for a caller whose pose is already in
	 * device pixels (OriginDeck scales by 1/guiScale): `size` IS the pixel size, so the crisp
	 * bake must not multiply it by the GUI scale again.
	 */
	public static void drawDevice(Gfx g, String id, int x, int y, int size, int argb) {
		drawShadowed(g, id, x, y, size, size, argb);
	}

	private static void drawShadowed(Gfx g, String id, int x, int y, int size, int px, int argb) {
		if (((argb >>> 24) & 0xFF) > 2) {
			drawCrisp(g, id, x + 1, y + 1, size, px, ((argb >>> 24) << 24) | (((argb >> 18) & 0x3F) << 16) | (((argb >> 10) & 0x3F) << 8) | ((argb >> 2) & 0x3F));
		}
		drawCrisp(g, id, x, y, size, px, argb);
	}

	// Pixel-exact menu icons. 1.16.5 has no icon SDF shader, and the 192 px mask cell sampled
	// GL_LINEAR at ~14x minification reads only 4 texels per pixel, so thin strokes aliased
	// (the mod menu's Options gear read as a diamond, the Edit HUD glyph lost its inner lines --
	// dev shots 2026-11-12 vs 1.21.1). Icons are instead area-averaged on the CPU to their exact
	// device-pixel size, once per (id, size), and drawn 1:1 (1.8.9 uses the same bake).
	private static NativeImage atlasImg;   // owned by the ATLAS DynamicTexture; read only here
	private static final int CRISP_MAX_PX = 256;
	// Baked icons per id, indexed by device size (an array: a frame's lookup allocates nothing).
	private static final Map<String, ResourceLocation[]> CRISP = new HashMap<>();
	private static boolean crispBroken = false;

	private static void drawCrisp(Gfx g, String id, int x, int y, int size, int px, int argb) {
		int a = (argb >>> 24) & 0xFF;
		if (a <= 2) {
			return;
		}
		if (crispBroken || pxHas(id)) {
			drawRaw(g, id, x, y, size, argb);
			return;
		}
		try {
			ensureLoaded();
			int[] c = ok ? CELLS.get(id) : null;
			px = Math.max(1, px);
			if (c == null || atlasImg == null || px > CRISP_MAX_PX) {
				drawRaw(g, id, x, y, size, argb);   // large icons minify little: the atlas is fine
				return;
			}
			ResourceLocation[] sizes = CRISP.get(id);
			if (sizes == null) {
				sizes = new ResourceLocation[CRISP_MAX_PX + 1];
				CRISP.put(id, sizes);
			}
			ResourceLocation rl = sizes[px];
			if (rl == null) {
				rl = bake(id, c, px);
				sizes[px] = rl;
			}
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.color4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
					(argb & 0xFF) / 255f, a / 255f);
			g.blit(rl, x, y, size, size, 0f, 0f, px, px, px, px);
			RenderSystem.color4f(1f, 1f, 1f, 1f);
		} catch (Throwable t) {
			// e.g. the atlas image was freed: the linear atlas path for the rest of the session
			crispBroken = true;
			RenderSystem.color4f(1f, 1f, 1f, 1f);
			drawRaw(g, id, x, y, size, argb);
		}
	}

	private static ResourceLocation bake(String id, int[] c, int px) {
		int cw = Math.min(cell, atlasW - c[0]), ch = Math.min(cell, atlasH - c[1]);
		int[] al0 = new int[cw * ch];
		NativeImage src = atlasImg;
		for (int y = 0; y < ch; y++) {
			for (int x = 0; x < cw; x++) {
				al0[y * cw + x] = src.getPixelRGBA(c[0] + x, c[1] + y) >>> 24;   // ABGR: alpha on top
			}
		}
		NativeImage out = new NativeImage(px, px, false);
		double step = cell / (double) px;
		for (int oy = 0; oy < px; oy++) {
			for (int ox = 0; ox < px; ox++) {
				double x0 = ox * step, x1 = x0 + step, y0 = oy * step, y1 = y0 + step;
				double sum = 0, wsum = 0;
				for (int sy = (int) Math.floor(y0); sy < y1; sy++) {
					double wy = Math.min(sy + 1, y1) - Math.max(sy, y0);
					int row = Math.min(sy, ch - 1) * cw;
					for (int sx = (int) Math.floor(x0); sx < x1; sx++) {
						double w = wy * (Math.min(sx + 1, x1) - Math.max(sx, x0));
						sum += w * al0[row + Math.min(sx, cw - 1)];
						wsum += w;
					}
				}
				int al = wsum > 0 ? (int) Math.round(sum / wsum) : 0;
				out.setPixelRGBA(ox, oy, (al << 24) | 0xFFFFFF);
			}
		}
		ResourceLocation rl = new ResourceLocation("originclient", "ui_mod_icon_" + id.replaceAll("[^a-z0-9_]", "_") + "_" + px);
		DynamicTexture tex;
		try {
			tex = new DynamicTexture(out);
		} catch (Throwable t) {
			out.close();
			throw t;
		}
		tex.setFilter(true, false);
		Minecraft.getInstance().getTextureManager().register(rl, tex);
		return rl;
	}

	/**
	 * The same glyph as {@link #draw(Gfx, String, int, int, int, int)} without the 1 px
	 * shadow copy: a bare line glyph sitting on a soft panel or the backdrop (Phase 4 title dock
	 * and button icons) must not carry a second, offset ghost of itself. Existing callers
	 * (the Mod Menu family) keep using draw(), so their pixels do not change.
	 */
	public static void drawFlat(Gfx g, String id, int x, int y, int size, int argb) {
		drawRaw(g, id, x, y, size, argb);
	}

	// --- Colour pixel icons (Will, 2026-10-20) -------------------------------------------
	// tools/mod-menu/pixel_title_icons.py bakes hard-pixel RGBA art (3-4 vanilla tones + dark
	// outline) into mod_icons_px.png: 12 px cells, drawn NEAREST at whole GUI pixels. An id found
	// there wins over the monochrome SDF/mask atlas; the tint only sets brightness (hover lights
	// it, disabled dims it) and alpha, so the colours stay. Missing/failed atlas = old glyphs.
	private static final Map<String, int[]> PX_CELLS = new HashMap<>();
	private static int pxCell = 12, pxW = 96, pxH = 12;
	private static volatile boolean pxLoaded = false;
	private static boolean pxOk = false;

	private static boolean pxHas(String id) {
		ensurePx();
		return pxOk && PX_CELLS.containsKey(id);
	}

	/** Brightness factor from a tint: ~0.9 grey (the menu's TEXT) and up = full colour. */
	private static int pxTint(int argb) {
		float l = (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
		int v = Math.round(Math.min(1f, l / 0.9f) * 255f);
		return (argb & 0xFF000000) | (v << 16) | (v << 8) | v;
	}

	private static void ensurePx() {
		if (pxLoaded) {
			return;
		}
		synchronized (ModIcons.class) {
			if (pxLoaded) {
				return;
			}
			pxLoaded = true;
			try {
				JsonObject j = com.origin.client.client.OriginAssets.json("/assets/originclient/textures/ui/mod_icons_px.json");
				pxCell = j.get("cell").getAsInt();
				JsonObject icons = j.getAsJsonObject("icons");
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : icons.entrySet()) {
					String k = e.getKey();
					JsonObject o = e.getValue().getAsJsonObject();
					PX_CELLS.put(k, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
				}
				NativeImage img = com.origin.client.client.OriginAssets.image("/assets/originclient/textures/ui/mod_icons_px.png");
				pxW = img.getWidth();
				pxH = img.getHeight();
				DynamicTexture tex;
				try {
					tex = new DynamicTexture(img);
				} catch (Throwable t) {
					img.close();
					throw t;
				}
				Minecraft.getInstance().getTextureManager().register(PX_ATLAS, tex);   // default sampler = NEAREST
				pxOk = true;
			} catch (Throwable t) {
				pxOk = false;
				com.origin.client.OriginClient.LOGGER.warn("Origin colour pixel icons failed to load; using the glyph set", t);
			}
		}
	}

	private static void drawPx(Gfx g, int[] c, int x, int y, int size, int argb) {
		// Integer-scale the 12 px art only (nearest at whole multiples = crisp pixels): the box
		// may be 14/18/40; draw the largest whole multiple that fits (at least 1x), centred.
		int k = Math.max(1, size / pxCell);
		int sz = pxCell * k;
		x += (size - sz) / 2;
		y += (size - sz) / 2;
		size = sz;
		int t = pxTint(argb);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(((t >> 16) & 0xFF) / 255f, ((t >> 8) & 0xFF) / 255f, (t & 0xFF) / 255f, ((t >>> 24) & 0xFF) / 255f);
		g.blit(PX_ATLAS, x, y, size, size, c[0], c[1], pxCell, pxCell, pxW, pxH);
		RenderSystem.color4f(1f, 1f, 1f, 1f);
	}

	private static void drawRaw(Gfx g, String id, int x, int y, int size, int argb) {
		int a = (argb >>> 24) & 0xFF;
		if (a <= 2) {
			return;
		}
		if (pxHas(id)) {
			drawPx(g, PX_CELLS.get(id), x, y, size, argb);
			return;
		}
		ensureLoaded();
		if (!ok) {
			return;
		}
		int[] c = CELLS.get(id);
		if (c == null) {
			return;
		}
		// 1.16.5 (fixed-function, no icon SDF shader): the linear-filtered mask atlas,
		// tinted through the global GL colour.
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, a / 255f);
		g.blit(ATLAS, x, y, size, size, c[0], c[1], cell, cell, atlasW, atlasH);
		RenderSystem.color4f(1f, 1f, 1f, 1f);
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		synchronized (ModIcons.class) {
			if (loaded) {
				return;
			}
			loaded = true;
			try {
				// Both atlases and the metrics are decoded on the startup reload's worker
				// threads (OriginAssets); what's left here is the one GPU upload.
				JsonObject j = com.origin.client.client.OriginAssets.json("/assets/originclient/textures/ui/mod_icons.json");
				cell = j.get("cell").getAsInt();
				JsonObject icons = j.getAsJsonObject("icons");
				// Gson 2.8.0 (1.16.5 / 1.17.1) has no JsonObject.keySet(): iterate the entries.
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : icons.entrySet()) {
					String k = e.getKey();
					JsonObject o = e.getValue().getAsJsonObject();
					CELLS.put(k, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
				}
				// Friends & Party has no glyph of its own yet: its card borrows the user mark.
				if (!CELLS.containsKey("friends") && CELLS.containsKey("@user")) {
					CELLS.put("friends", CELLS.get("@user"));
				}
				NativeImage img = com.origin.client.client.OriginAssets.image("/assets/originclient/textures/ui/mod_icons.png");
				atlasW = img.getWidth();
				atlasH = img.getHeight();
				// 1.16.5: no icon SDF shader, so the mask atlas is the one GPU atlas.
				DynamicTexture tex;
				try {
					tex = new DynamicTexture(img);
				} catch (Throwable t) {
					img.close();   // never reached a texture: ours to free
					throw t;
				}
				tex.setFilter(true, false);
				Minecraft.getInstance().getTextureManager().register(ATLAS, tex);
				atlasImg = img;   // the texture keeps its pixels: the crisp bake reads them
				ok = true;
			} catch (Throwable t) {
				ok = false;
				// The prefetched atlas, never handed over (the metrics failed first): free it.
				com.origin.client.client.OriginAssets.discard("/assets/originclient/textures/ui/mod_icons.png");
				com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			}
		}
	}
}
