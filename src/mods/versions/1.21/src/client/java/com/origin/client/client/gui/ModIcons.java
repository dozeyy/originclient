package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

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
	private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("originclient", "ui_mod_icons");
	private static final ResourceLocation SDF_ATLAS = ResourceLocation.fromNamespaceAndPath("originclient", "ui_mod_icons_sdf");

	private static final Map<String, int[]> CELLS = new HashMap<>();
	private static int cell = 192, atlasW = 1152, atlasH = 1344;
	private static volatile boolean loaded = false;
	private static boolean ok = false;
	private static boolean sdfOk = false;

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
	 * (its alpha carries the mod menu's open/close fade). The signed-distance
	 * path reconstructs coverage in screen space, keeping curves clean at every
	 * Minecraft GUI scale. A linear-filtered mask remains as a fail-soft fallback.
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

	private static void drawSdf(GuiGraphics g, int[] c, int x, int y, int size, int argb) {
		int r = (argb >> 16) & 0xFF;
		int green = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int a = (argb >>> 24) & 0xFF;
		float u0 = c[0] / (float) atlasW;
		float v0 = c[1] / (float) atlasH;
		float u1 = (c[0] + cell) / (float) atlasW;
		float v1 = (c[1] + cell) / (float) atlasH;
		Matrix4f matrix = g.pose().last().pose();
		BufferBuilder builder = Tesselator.getInstance().begin(
				VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		builder.addVertex(matrix, x, y, 0).setColor(r, green, b, a).setUv(u0, v0);
		builder.addVertex(matrix, x, y + size, 0).setColor(r, green, b, a).setUv(u0, v1);
		builder.addVertex(matrix, x + size, y + size, 0).setColor(r, green, b, a).setUv(u1, v1);
		builder.addVertex(matrix, x + size, y, 0).setColor(r, green, b, a).setUv(u1, v0);
		MeshData mesh = builder.build();
		if (mesh == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(() -> OriginShaders.ICON);
		RenderSystem.setShaderTexture(0, SDF_ATLAS);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		BufferUploader.drawWithShader(mesh);
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
				// Keep only one GPU atlas resident. The high-range SDF is the normal
				// path; the alpha mask is registered only if the icon shader did not
				// compile, preserving a usable fail-soft UI without doubling VRAM.
				if (OriginShaders.ICON != null) {
					try {
						NativeImage sdfImage;
						try (InputStream in = ModIcons.class.getResourceAsStream(
								"/assets/originclient/textures/ui/mod_icons_sdf.png")) {
							sdfImage = NativeImage.read(in);
						}
						if (sdfImage.getWidth() != atlasW || sdfImage.getHeight() != atlasH) {
							sdfImage.close();
							throw new IllegalStateException("Origin icon SDF atlas dimensions do not match the mask atlas");
						}
						DynamicTexture sdfTexture = new DynamicTexture(sdfImage);
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
					DynamicTexture tex = new DynamicTexture(img);
					tex.setFilter(true, false);
					Minecraft.getInstance().getTextureManager().register(ATLAS, tex);
				}
				ok = true;
			} catch (Throwable t) {
				ok = false;
				sdfOk = false;
				com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			}
		}
	}
}
