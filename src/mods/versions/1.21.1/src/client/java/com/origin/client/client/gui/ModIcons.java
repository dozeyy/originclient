package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Origin's OWN icon set for the mod menu (2026-09 redesign, Will's call: no more
 * Minecraft items as icons). One family built to the Design Assets / Icon
 * Packs rules — 24-grid, 2 px stroke, round caps, one concept per glyph — baked
 * by tools/mod-menu/generate_icons.py into a single atlas of white alpha masks
 * (96 px cells, supersampled). Because the atlas is a MASK, every icon is
 * tinted at draw time with a theme colour, so the set follows the Slate palette
 * by construction (TEXT on a card, ACCENT when the mod is on, MUTED in a field).
 *
 * The menu chrome (search, settings tabs, HUD editor, backing toggle) draws its
 * glyphs from the same atlas under '@' ids so the whole menu is one weight.
 *
 * Fails soft: if the atlas or its metrics are missing, {@link #draw} draws
 * nothing and the menu keeps working — a card with no icon beats a crash.
 */
public final class ModIcons {
	private static final Gson GSON = new Gson();
	private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("originclient", "ui_mod_icons");

	private static final Map<String, int[]> CELLS = new HashMap<>();
	private static int cell = 96, atlasW = 576, atlasH = 576;
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
	public static void draw(GuiGraphics g, String id, int x, int y, int size, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		draw(g, id, x, y, size, (a << 24) | 0xFFFFFF);
	}

	/**
	 * Draws the glyph for `id` in a size x size box at (x,y), tinted with `argb`
	 * (its alpha carries the mod menu's open/close fade). GL_LINEAR sampling of
	 * the 96 px mask keeps the stroke smooth at any menu size.
	 */
	public static void draw(GuiGraphics g, String id, int x, int y, int size, int argb) {
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
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, a / 255f);
		g.blit(ATLAS, x, y, size, size, c[0], c[1], cell, cell, atlasW, atlasH);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
					j = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
				}
				cell = j.get("cell").getAsInt();
				JsonObject icons = j.getAsJsonObject("icons");
				for (String k : icons.keySet()) {
					JsonObject o = icons.getAsJsonObject(k);
					CELLS.put(k, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
				}
				NativeImage img;
				try (InputStream in = ModIcons.class.getResourceAsStream("/assets/originclient/textures/ui/mod_icons.png")) {
					img = NativeImage.read(in);
				}
				atlasW = img.getWidth();
				atlasH = img.getHeight();
				// Linear filtering is what makes the supersampled mask read as a
				// smooth stroke instead of a scaled bitmap.
				DynamicTexture tex = new DynamicTexture(img);
				tex.setFilter(true, false);
				Minecraft.getInstance().getTextureManager().register(ATLAS, tex);
				ok = true;
			} catch (Throwable t) {
				ok = false;
				com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			}
		}
	}
}
