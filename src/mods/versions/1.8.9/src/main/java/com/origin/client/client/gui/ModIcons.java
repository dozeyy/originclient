package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

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
		if (((argb >>> 24) & 0xFF) > 2) {
			drawRaw(g, id, x + 1, y + 1, size, ((argb >>> 24) << 24) | (((argb >> 18) & 0x3F) << 16) | (((argb >> 10) & 0x3F) << 8) | ((argb >> 2) & 0x3F));
		}
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
				JsonObject j;
				try (InputStream in = ModIcons.class.getResourceAsStream("/assets/originclient/textures/ui/mod_icons_px.json")) {
					j = GSON.fromJson(new String(com.origin.client.client.IoCompat.readAllBytes(in), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
				}
				pxCell = j.get("cell").getAsInt();
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : j.getAsJsonObject("icons").entrySet()) {
					JsonObject o = e.getValue().getAsJsonObject();
					PX_CELLS.put(e.getKey(), new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
				}
				java.awt.image.BufferedImage img = Gfx.readImage("/assets/originclient/textures/ui/mod_icons_px.png");
				pxW = img.getWidth();
				pxH = img.getHeight();
				pxOk = Gfx.registerTexture(PX_ATLAS, img, false) != null;   // nearest
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
		Gfx.defaultBlend();
		GlStateManager.color(((t >> 16) & 0xFF) / 255f, ((t >> 8) & 0xFF) / 255f, (t & 0xFF) / 255f, ((t >>> 24) & 0xFF) / 255f);
		g.blit(PX_ATLAS, x, y, size, size, c[0], c[1], pxCell, pxCell, pxW, pxH);
		GlStateManager.color(1f, 1f, 1f, 1f);
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
		// 1.8.9 (fixed-function, no icon SDF shader -- as 1.16.5): the linear-filtered
		// mask atlas, tinted through the global GL colour.
		Gfx.defaultBlend();
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, a / 255f);
		g.blit(ATLAS, x, y, size, size, c[0], c[1], cell, cell, atlasW, atlasH);
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	// Pixel-exact small icons. The 192 px cell sampled GL_LINEAR at ~8x minification
	// reads only 4 texels per pixel, so thin strokes stair-step ("the bottom icons
	// look jagged", Will's 2026-10-20 playtest) and a mip chain washed them out
	// (see ensureLoaded). Small fixed-size icons are instead area-averaged on the
	// CPU to their exact device-pixel size, once, and drawn 1:1.
	private static java.awt.image.BufferedImage atlasImg;
	// Baked crisp icons per id, indexed by physical size (an array: a frame's lookup allocates nothing).
	private static final int CRISP_MAX_PX = 256;
	private static final java.util.Map<String, ResourceLocation[]> CRISP = new java.util.HashMap<>();

	/**
	 * The title's flat glyph (1.21.1's ModIcons.drawFlat): the crisp, physical-pixel bake tinted `argb`, with NO
	 * shadow -- the Workbench dock and the worded buttons' lead glyphs sit flat on their card.
	 */
	public static void drawFlat(Gfx g, String id, int x, int y, int size, int argb) {
		drawCrispRaw(g, id, x, y, size, argb);
	}

	public static void drawCrisp(Gfx g, String id, int x, int y, int size, int argb) {
		if (((argb >>> 24) & 0xFF) > 2) {
			drawCrispRaw(g, id, x + 1, y + 1, size, ((argb >>> 24) << 24) | (((argb >> 18) & 0x3F) << 16) | (((argb >> 10) & 0x3F) << 8) | ((argb >> 2) & 0x3F));   // vanilla text shadow, see draw()
		}
		drawCrispRaw(g, id, x, y, size, argb);
	}

	private static void drawCrispRaw(Gfx g, String id, int x, int y, int size, int argb) {
		try {
			int a = (argb >>> 24) & 0xFF;
			if (a <= 2) {
				return;
			}
				if (pxHas(id)) {
					drawPx(g, PX_CELLS.get(id), x, y, size, argb);
					return;
				}
			ensureLoaded();
			int[] c = ok ? CELLS.get(id) : null;
			if (c == null || atlasImg == null) {
				return;
			}
			int px = Math.max(1, size * Gfx.guiScale());
			if (px > CRISP_MAX_PX) {
				drawRaw(g, id, x, y, size, argb);
				return;
			}
			ResourceLocation[] sizes = CRISP.get(id);
			if (sizes == null) {
				sizes = new ResourceLocation[CRISP_MAX_PX + 1];
				CRISP.put(id, sizes);
			}
			ResourceLocation rl = sizes[px];
			if (rl == null) {
				java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
				double step = cell / (double) px;
				for (int oy = 0; oy < px; oy++) {
					for (int ox = 0; ox < px; ox++) {
						double x0 = c[0] + ox * step, x1 = x0 + step, y0 = c[1] + oy * step, y1 = y0 + step;
						double sum = 0, wsum = 0;
						for (int sy = (int) Math.floor(y0); sy < y1; sy++) {
							double wy = Math.min(sy + 1, y1) - Math.max(sy, y0);
							for (int sx = (int) Math.floor(x0); sx < x1; sx++) {
								double w = wy * (Math.min(sx + 1, x1) - Math.max(sx, x0));
								sum += w * (atlasImg.getRGB(Math.min(sx, atlasW - 1), Math.min(sy, atlasH - 1)) >>> 24);
								wsum += w;
							}
						}
						int al = wsum > 0 ? (int) Math.round(sum / wsum) : 0;
						out.setRGB(ox, oy, (al << 24) | 0xFFFFFF);
					}
				}
				rl = new ResourceLocation("originclient", "ui_mod_icon_" + id.replaceAll("[^a-z0-9_]", "_") + "_" + px);
				if (Gfx.registerTexture(rl, out, true) == null) {
					return;
				}
				sizes[px] = rl;
			}
			Gfx.defaultBlend();
			GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
					(argb & 0xFF) / 255f, a / 255f);
			g.blit(rl, x, y, size, size, 0, 0, px, px, px, px);
			GlStateManager.color(1f, 1f, 1f, 1f);
		} catch (Throwable t) {
			drawRaw(g, id, x, y, size, argb);   // the linear atlas path
		}
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
				JsonObject j;
				try (InputStream in = ModIcons.class.getResourceAsStream("/assets/originclient/textures/ui/mod_icons.json")) {
					j = GSON.fromJson(new String(com.origin.client.client.IoCompat.readAllBytes(in), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
				}
				cell = j.get("cell").getAsInt();
				JsonObject icons = j.getAsJsonObject("icons");
				// Gson 2.2.4 (1.8.9) has no JsonObject.keySet(): iterate the entries.
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : icons.entrySet()) {
					String k = e.getKey();
					JsonObject o = e.getValue().getAsJsonObject();
					CELLS.put(k, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
				}
				// Friends & Party has no glyph of its own yet: its card borrows the user mark.
				if (!CELLS.containsKey("friends") && CELLS.containsKey("@user")) {
					CELLS.put("friends", CELLS.get("@user"));
				}
				java.awt.image.BufferedImage img = Gfx.readImage("/assets/originclient/textures/ui/mod_icons.png");
				atlasImg = img;
				atlasW = img.getWidth();
				atlasH = img.getHeight();
				// 1.8.9: no icon SDF shader, so the mask atlas is the one GPU atlas, sampled
				// GL_LINEAR without mipmaps exactly like 1.16.5 (tex.setFilter(true, false)).
				// A mip chain averaged the 2 px strokes of 14-28 px icons into faint grey
				// (dev shots 2026-10-13: close/grid/list icons washed out next to 1.16.5's).
				ok = Gfx.registerTexture(ATLAS, img, true, false) != null;
			} catch (Throwable t) {
				ok = false;
				com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			}
		}
	}
}
