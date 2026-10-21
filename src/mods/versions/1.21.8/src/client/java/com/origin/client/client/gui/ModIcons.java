package com.origin.client.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3x2f;

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
 *
 * PER-VERSION DELTA (1.21.6-1.21.8): the GUI is deferred here, so the SDF quad is
 * not an immediate BufferBuilder draw under a global setShader -- it is a
 * GuiElementRenderState naming OriginShaders.ICON, submitted into
 * guiGraphics.guiRenderState (the same shape as OriginSdfFont's text quads:
 * buildVertices(VertexConsumer, float), addVertexWith2DPose with an explicit z,
 * TextureSetup.singleTexture(view) and a REAL bounds()). LINEAR sampling comes
 * from the texture's own filter (setFilter). The mask fallback carries its tint
 * on the per-blit ARGB (no setShaderColor on this era).
 */
public final class ModIcons {
	private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("originclient", "ui_mod_icons");
	private static final ResourceLocation PX_ATLAS = ResourceLocation.fromNamespaceAndPath("originclient", "ui_mod_icons_px");
	private static final ResourceLocation SDF_ATLAS = ResourceLocation.fromNamespaceAndPath("originclient", "ui_mod_icons_sdf");

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
				JsonObject j = com.origin.client.client.OriginAssets.json("/assets/originclient/textures/ui/mod_icons_px.json");
				pxCell = j.get("cell").getAsInt();
				JsonObject icons = j.getAsJsonObject("icons");
				for (String k : icons.keySet()) {
					JsonObject o = icons.getAsJsonObject(k);
					PX_CELLS.put(k, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
				}
				NativeImage img = com.origin.client.client.OriginAssets.image("/assets/originclient/textures/ui/mod_icons_px.png");
				pxW = img.getWidth();
				pxH = img.getHeight();
				DynamicTexture tex;
				try {
					tex = new DynamicTexture(() -> "originclient:ui_mod_icons_px", img);
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

	private static void drawPx(GuiGraphics g, int[] c, int x, int y, int size, int argb) {
		// Integer-scale the 12 px art only (nearest at whole multiples = crisp pixels): the box
		// may be 14/18/40; draw the largest whole multiple that fits (at least 1x), centred.
		int k = Math.max(1, size / pxCell);
		int sz = pxCell * k;
		x += (size - sz) / 2;
		y += (size - sz) / 2;
		size = sz;
		g.blit(RenderPipelines.GUI_TEXTURED, PX_ATLAS, x, y, (float) c[0], (float) c[1], size, size, pxCell, pxCell, pxW, pxH, pxTint(argb));
	}

	private static void drawRaw(GuiGraphics g, String id, int x, int y, int size, int argb) {
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
		if (sdfOk && OriginShaders.ICON != null) {
			drawSdf(g, c, x, y, size, argb);
			return;
		}
		// Fail-soft mask path: the tint rides the per-blit ARGB (this era has no
		// RenderSystem.setShaderColor); blend state is the pipeline's.
		g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, (float) c[0], (float) c[1], size, size, cell, cell, atlasW, atlasH, argb);
	}

	// One SDF glyph quad: local rect + atlas UV + pose, transformed in buildVertices.
	// Same shape as OriginSdfFont.MsdfTextRenderState, including a real, non-null
	// bounds(): the deferred renderer culls on bounds() and reads null as "nothing
	// visible" (the invisible-MSDF-text bug).
	private record IconGlyphRenderState(
			GpuTextureView atlasView, Matrix3x2f pose, int x0, int y0, int x1, int y1,
			float u0, float v0, float u1, float v1, int color,
			ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vertexConsumer, float depth) {
			vertexConsumer.addVertexWith2DPose(pose, x0, y0, depth).setColor(color).setUv(u0, v0);
			vertexConsumer.addVertexWith2DPose(pose, x0, y1, depth).setColor(color).setUv(u0, v1);
			vertexConsumer.addVertexWith2DPose(pose, x1, y1, depth).setColor(color).setUv(u1, v1);
			vertexConsumer.addVertexWith2DPose(pose, x1, y0, depth).setColor(color).setUv(u1, v0);
		}

		@Override
		public RenderPipeline pipeline() {
			return OriginShaders.ICON;
		}

		@Override
		public TextureSetup textureSetup() {
			return TextureSetup.singleTexture(atlasView);
		}

		@Override
		public ScreenRectangle scissorArea() {
			return scissorArea;
		}

		@Override
		public ScreenRectangle bounds() {
			return bounds;
		}
	}

	private static void drawSdf(GuiGraphics g, int[] c, int x, int y, int size, int argb) {
		float u0 = c[0] / (float) atlasW;
		float v0 = c[1] / (float) atlasH;
		float u1 = (c[0] + cell) / (float) atlasW;
		float v1 = (c[1] + cell) / (float) atlasH;
		GpuTextureView atlasView = Minecraft.getInstance().getTextureManager().getTexture(SDF_ATLAS).getTextureView();
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		ScreenRectangle localRect = new ScreenRectangle(x, y, size, size).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(localRect) : localRect;
		if (bounds == null) {
			return;   // fully scissored away
		}
		g.guiRenderState.submitGuiElement(new IconGlyphRenderState(
				atlasView, pose, x, y, x + size, y + size, u0, v0, u1, v1, argb, scissor, bounds));
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
				for (String k : icons.keySet()) {
					JsonObject o = icons.getAsJsonObject(k);
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
						// 1.21.6-1.21.8 DynamicTexture takes a debug-label supplier;
						// setFilter(true, ...) = LINEAR, which the SDF reconstruction needs.
						DynamicTexture sdfTexture;
						try {
							sdfTexture = new DynamicTexture(() -> "originclient:ui_mod_icons_sdf", sdfImage);
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
						tex = new DynamicTexture(() -> "originclient:ui_mod_icons", img);
					} catch (Throwable t) {
						img.close();   // never reached a texture: ours to free
						throw t;
					}
					tex.setFilter(true, false); // GUI_TEXTURED samples NEAREST otherwise
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
