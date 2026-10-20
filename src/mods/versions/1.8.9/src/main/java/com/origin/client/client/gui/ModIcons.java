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
		int a = (argb >>> 24) & 0xFF;
		if (a <= 2) {
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
	private static final java.util.Map<String, ResourceLocation> CRISP = new java.util.HashMap<>();

	public static void drawCrisp(Gfx g, String id, int x, int y, int size, int argb) {
		try {
			int a = (argb >>> 24) & 0xFF;
			if (a <= 2) {
				return;
			}
			ensureLoaded();
			int[] c = ok ? CELLS.get(id) : null;
			if (c == null || atlasImg == null) {
				return;
			}
			int scale = new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()).getScaleFactor();
			int px = Math.max(1, size * scale);
			String key = id + "@" + px;
			ResourceLocation rl = CRISP.get(key);
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
				CRISP.put(key, rl);
			}
			Gfx.defaultBlend();
			GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
					(argb & 0xFF) / 255f, a / 255f);
			g.blit(rl, x, y, size, size, 0, 0, px, px, px, px);
			GlStateManager.color(1f, 1f, 1f, 1f);
		} catch (Throwable t) {
			draw(g, id, x, y, size, argb);   // the linear atlas path
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
