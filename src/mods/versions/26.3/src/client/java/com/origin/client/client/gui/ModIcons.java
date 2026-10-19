package com.origin.client.client.gui;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

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
 * PER-VERSION DELTA (1.21.11): the GUI is deferred here, so the SDF quad is not
 * an immediate BufferBuilder draw under RenderSystem.setShader — it is a
 * GuiElementRenderState naming OriginShaders.icon() (the same RenderPipeline the
 * close/chevron/edit glyphs in OriginUi already draw with) submitted into
 * guiGraphics.guiRenderState, with an explicit LINEAR sampler. The mask
 * fallback blit carries its tint on the per-blit ARGB (no setShaderColor), and
 * DynamicTexture takes a debug-label supplier (no setFilter).
 */
public final class ModIcons {
	private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("originclient", "ui_mod_icons");
	private static final Identifier SDF_ATLAS = Identifier.fromNamespaceAndPath("originclient", "ui_mod_icons_sdf");

	private static final Map<String, int[]> CELLS = new HashMap<>();
	private static int cell = 192, atlasW = 1152, atlasH = 1344;
	private static volatile boolean loaded = false;
	private static boolean ok = false;
	private static boolean sdfOk = false;
	// 26.3: the SDF pipeline cannot be built during the startup reload (see
	// OriginShaders.sourcesPending), and the loading screen's warm-up is what
	// first loads this class. So the atlases are DECODED in ensureLoaded but only
	// registered on the first draw that can use them (ensureAtlas): the SDF once
	// the pipeline exists, the mask only if the pipeline is really broken. Before
	// this split the mask was registered and the SDF never retried (2026-10-18).
	private static NativeImage maskImage, sdfImage;
	private static boolean maskRegistered = false;

	private ModIcons() {
	}

	/**
	 * OriginAssets' per-frame upload slice: decodes now and uploads the atlas if
	 * the icon pipeline can be decided. False = not yet (the reload behind the
	 * loading overlay still running, see OriginShaders.sourcesPending); the slice
	 * retries, and if the overlay closes first the first draw uploads instead.
	 * Never registers the mask early -- that would lock the session onto it.
	 */
	public static boolean preload() {
		ensureLoaded();
		return !ok || ensureAtlas();
	}

	/** Whether this id has a glyph in the atlas. */
	public static boolean has(String id) {
		ensureLoaded();
		return ok && CELLS.containsKey(id);
	}

	/** Compatibility overload: white icon at the given alpha (0..1). */
	public static void draw(GuiGraphicsExtractor g, String id, int x, int y, int size, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		draw(g, id, x, y, size, (a << 24) | 0xFFFFFF);
	}

	/**
	 * Draws the glyph for `id` in a size x size box at (x,y), tinted with `argb`
	 * (its alpha carries the mod menu's open/close fade). The signed-distance
	 * path reconstructs coverage in screen space, keeping curves clean at every
	 * Minecraft GUI scale. A linear-filtered mask remains as a fail-soft fallback.
	 */
	public static void draw(GuiGraphicsExtractor g, String id, int x, int y, int size, int argb) {
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
		if (!ensureAtlas()) {
			return;   // pipeline still pending (loading overlay): draw nothing this frame
		}
		if (sdfOk) {
			drawSdf(g, c, x, y, size, argb);
			return;
		}
		// Fail-soft mask path: the tint rides the per-blit ARGB (this era has no
		// RenderSystem.setShaderColor); blend state is the pipeline's.
		g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, (float) c[0], (float) c[1], size, size, cell, cell, atlasW, atlasH, argb);
	}

	// One SDF glyph quad: local rect + atlas UV + pose, transformed in buildVertices.
	// Same shape as OriginUi.IconSdfRenderState and vanilla's own
	// ColoredRectangleRenderState, including a real, non-null bounds(): the
	// deferred renderer culls on bounds() and reads null as "nothing visible"
	// (the invisible-MSDF-text bug — see OriginSdfFont.MsdfTextRenderState).
	private record IconGlyphRenderState(
			GpuTextureView atlasView, Matrix3x2fc pose, int x0, int y0, int x1, int y1,
			float u0, float v0, float u1, float v1, int color,
			ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vertexConsumer) {
			vertexConsumer.addVertexWith2DPose(pose, x0, y0).setColor(color).setUv(u0, v0);
			vertexConsumer.addVertexWith2DPose(pose, x0, y1).setColor(color).setUv(u0, v1);
			vertexConsumer.addVertexWith2DPose(pose, x1, y1).setColor(color).setUv(u1, v1);
			vertexConsumer.addVertexWith2DPose(pose, x1, y0).setColor(color).setUv(u1, v0);
		}

		@Override
		public RenderPipeline pipeline() {
			return OriginShaders.icon();
		}

		@Override
		public TextureSetup textureSetup() {
			// SDF needs linear sampling; on this era the sampler is chosen per draw.
			return TextureSetup.singleTexture(atlasView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
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

	private static void drawSdf(GuiGraphicsExtractor g, int[] c, int x, int y, int size, int argb) {
		float u0 = c[0] / (float) atlasW;
		float v0 = c[1] / (float) atlasH;
		float u1 = (c[0] + cell) / (float) atlasW;
		float v1 = (c[1] + cell) / (float) atlasH;
		GpuTextureView atlasView = Minecraft.getInstance().getTextureManager().getTexture(SDF_ATLAS).getTextureView();
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		ScreenRectangle localRect = new ScreenRectangle(x, y, size, size).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(localRect) : localRect;
		g.guiRenderState.addGuiElement(new IconGlyphRenderState(
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
				// The metrics and both atlases are decoded on the startup reload's worker
				// threads (OriginAssets); the one GPU upload is ensureAtlas's.
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
				// Decode only (main 0ccf5094): registration waits for ensureAtlas, the first
				// draw that can use an atlas. The images come from OriginAssets' prefetch
				// (decoded on the reload's worker threads) or are decoded now if it hasn't run.
				maskImage = com.origin.client.client.OriginAssets.image("/assets/originclient/textures/ui/mod_icons.png");
				atlasW = maskImage.getWidth();
				atlasH = maskImage.getHeight();
				try {
					sdfImage = com.origin.client.client.OriginAssets.image("/assets/originclient/textures/ui/mod_icons_sdf.png");
					if (sdfImage.getWidth() != atlasW || sdfImage.getHeight() != atlasH) {
						sdfImage.close();
						sdfImage = null;
						throw new IllegalStateException("Origin icon SDF atlas dimensions do not match the mask atlas");
					}
				} catch (Throwable sdfFailure) {
					com.origin.client.OriginClient.LOGGER.warn(
							"Origin icon SDF failed to load; using the smooth mask fallback", sdfFailure);
				}
				ok = true;
			} catch (Throwable t) {
				ok = false;
				sdfOk = false;
				// Decoded atlases that will never be registered: free them, and any
				// prefetched ones never handed over (the metrics failed first).
				if (maskImage != null) {
					maskImage.close();
					maskImage = null;
				}
				if (sdfImage != null) {
					sdfImage.close();
					sdfImage = null;
				}
				com.origin.client.client.OriginAssets.discard("/assets/originclient/textures/ui/mod_icons.png");
				com.origin.client.client.OriginAssets.discard("/assets/originclient/textures/ui/mod_icons_sdf.png");
				com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			}
		}
	}

	/**
	 * Render thread, from draw: registers the one atlas this session draws with.
	 * Keeps only one GPU atlas resident: the high-range SDF is the normal path;
	 * the alpha mask is registered only if the icon pipeline is genuinely broken
	 * (or the SDF png failed), preserving a usable fail-soft UI without doubling
	 * VRAM. Returns false while the pipeline is still pending -- nothing to draw
	 * with yet, and registering the mask then would lock the session onto it.
	 */
	private static boolean ensureAtlas() {
		if (sdfOk || maskRegistered) {
			return true;
		}
		if (sdfImage != null) {
			if (OriginShaders.icon() == null && OriginShaders.sourcesPending()) {
				return false;
			}
			if (OriginShaders.icon() != null) {
				try {
					// Sampling is chosen per draw (IconGlyphRenderState binds LINEAR), so
					// the texture itself registers plain. 1.21.11 DynamicTexture takes a
					// debug-label supplier; setFilter is gone.
					DynamicTexture sdfTexture = new DynamicTexture(() -> "originclient:ui_mod_icons_sdf", sdfImage);
					sdfImage = null;   // owned by the texture now
					Minecraft.getInstance().getTextureManager().register(SDF_ATLAS, sdfTexture);
					sdfOk = true;
					maskImage.close();
					maskImage = null;
					return true;
				} catch (Throwable sdfFailure) {
					com.origin.client.OriginClient.LOGGER.warn(
							"Origin icon SDF failed to load; using the smooth mask fallback", sdfFailure);
				}
			}
			if (sdfImage != null) {
				sdfImage.close();
				sdfImage = null;
			}
		}
		// The mask blits through GUI_TEXTURED, whose default sampler is NEAREST,
		// so bind a LINEAR clamp sampler on the texture (same pattern as OriginUi.reg).
		NativeImage img = maskImage;
		maskImage = null;
		try {
			DynamicTexture tex;
			try {
				tex = new DynamicTexture(() -> "originclient:ui_mod_icons", img) {
					{
						this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
					}
				};
			} catch (Throwable t) {
				img.close();   // never reached a texture: ours to free
				throw t;
			}
			Minecraft.getInstance().getTextureManager().register(ATLAS, tex);
			maskRegistered = true;
			return true;
		} catch (Throwable t) {
			ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin mod icons failed to load; cards draw without icons", t);
			return false;
		}
	}
}
