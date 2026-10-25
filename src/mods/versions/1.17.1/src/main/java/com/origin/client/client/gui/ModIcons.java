package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.math.Matrix4f;

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
	private static final ResourceLocation SDF_ATLAS = new ResourceLocation("originclient", "ui_mod_icons_sdf");

	private static final Map<String, int[]> CELLS = new HashMap<>();
	private static int cell = 192, atlasW = 1152, atlasH = 1344;
	private static volatile boolean loaded = false;
	private static boolean ok = false;
	private static boolean sdfOk = false;

	private ModIcons() {
	}

	/**
	 * Loads (uploads) the atlas now (OriginAssets' per-frame upload slices).
	 * Only once the icon shader exists: the atlas choice is made once, here.
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
		if (((argb >>> 24) & 0xFF) > 2) {
			drawRaw(g, id, x + 1, y + 1, size, ((argb >>> 24) << 24) | (((argb >> 18) & 0x3F) << 16) | (((argb >> 10) & 0x3F) << 8) | ((argb >> 2) & 0x3F));
		}
		drawRaw(g, id, x, y, size, argb);
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
		RenderSystem.setShaderColor(((t >> 16) & 0xFF) / 255f, ((t >> 8) & 0xFF) / 255f, (t & 0xFF) / 255f, ((t >>> 24) & 0xFF) / 255f);
		g.blit(PX_ATLAS, x, y, size, size, c[0], c[1], pxCell, pxCell, pxW, pxH);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
		OriginShaders.ensureCompiled();
		ensureLoaded();
		if (!ok) {
			return;
		}
		int[] c = CELLS.get(id);
		if (c == null) {
			return;
		}
		if (sdfOk && OriginShaders.ICON != null) {
			drawSdf(g, c, x, y, size, argb);
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, a / 255f);
		g.blit(ATLAS, x, y, size, size, c[0], c[1], cell, cell, atlasW, atlasH);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void drawSdf(Gfx g, int[] c, int x, int y, int size, int argb) {
		int r = (argb >> 16) & 0xFF;
		int green = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int a = (argb >>> 24) & 0xFF;
		float u0 = c[0] / (float) atlasW;
		float v0 = c[1] / (float) atlasH;
		float u1 = (c[0] + cell) / (float) atlasW;
		float v1 = (c[1] + cell) / (float) atlasH;
		Matrix4f matrix = g.pose().last().pose();
		BufferBuilder builder = Tesselator.getInstance().getBuilder();
		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		// This era's builder wants the attributes in FORMAT order: position, uv, colour.
		builder.vertex(matrix, x, y, 0).uv(u0, v0).color(r, green, b, a).endVertex();
		builder.vertex(matrix, x, y + size, 0).uv(u0, v1).color(r, green, b, a).endVertex();
		builder.vertex(matrix, x + size, y + size, 0).uv(u1, v1).color(r, green, b, a).endVertex();
		builder.vertex(matrix, x + size, y, 0).uv(u1, v0).color(r, green, b, a).endVertex();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(() -> OriginShaders.ICON);
		RenderSystem.setShaderTexture(0, SDF_ATLAS);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		builder.end();
		BufferUploader.end(builder);
		OriginShaders.restoreState();
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
				// Gson 2.8.0 (1.17.1) has no JsonObject.keySet(): iterate the entries.
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
				// Keep only one GPU atlas resident. The high-range SDF is the normal
				// path; the alpha mask is registered only if the icon shader did not
				// compile, preserving a usable fail-soft UI without doubling VRAM.
				if (OriginShaders.ICON == null) {
					com.origin.client.client.OriginAssets.discard("/assets/originclient/textures/ui/mod_icons_sdf.png");
				} else {
					try {
						NativeImage sdfImage = com.origin.client.client.OriginAssets.image(
								"/assets/originclient/textures/ui/mod_icons_sdf.png");
						if (sdfImage.getWidth() != atlasW || sdfImage.getHeight() != atlasH) {
							sdfImage.close();
							throw new IllegalStateException("Origin icon SDF atlas dimensions do not match the mask atlas");
						}
						DynamicTexture sdfTexture;
						try {
							sdfTexture = new DynamicTexture(sdfImage);
						} catch (Throwable t) {
							sdfImage.close();   // never reached a texture: ours to free
							throw t;
						}
						sdfTexture.setFilter(true, false);
						Minecraft.getInstance().getTextureManager().register(SDF_ATLAS, sdfTexture);
						sdfOk = true;
					} catch (Throwable sdfFailure) {
						com.origin.client.OriginClient.LOGGER.warn(
								"Origin icon SDF failed to load; using the smooth mask fallback", sdfFailure);
					}
				}
				if (sdfOk) {
					img.close();
				} else {
					DynamicTexture tex;
					try {
						tex = new DynamicTexture(img);
					} catch (Throwable t) {
						img.close();   // never reached a texture: ours to free
						throw t;
					}
					tex.setFilter(true, false);
					Minecraft.getInstance().getTextureManager().register(ATLAS, tex);
				}
				ok = true;
			} catch (Throwable t) {
				ok = false;
				sdfOk = false;
				// Prefetched atlases never handed over (the metrics failed first): free them.
				com.origin.client.client.OriginAssets.discard("/assets/originclient/textures/ui/mod_icons.png");
				com.origin.client.client.OriginAssets.discard("/assets/originclient/textures/ui/mod_icons_sdf.png");
				com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			}
		}
	}
}
