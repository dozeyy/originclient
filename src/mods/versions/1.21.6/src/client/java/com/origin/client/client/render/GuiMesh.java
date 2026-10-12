package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3x2f;

import java.util.Arrays;

/**
 * Free-form GUI triangles (fans, rings, rotated quads, a textured map) for the
 * Lunar-parity HUD pieces that vanilla's rectangle fills can't draw: the
 * Cooldowns dials and the Minimap. Coordinates are GUI-local (under the
 * current pose); winding doesn't matter.
 *
 * PER-VERSION (1.21.6-1.21.8, deferred GUI): the same API as the 1.21.2-1.21.4
 * modules' GuiMesh, built on this era's GUI instead of an immediate draw. draw()
 * submits ONE GuiElementRenderState into g.guiRenderState -- the shape of the
 * module's own ModIcons/OriginSdfFont states: buildVertices with
 * addVertexWith2DPose, a TextureSetup, and a real bounds() (the deferred
 * renderer layers by bounds and skips a null one). Layering therefore follows
 * submission order like everything else in this GUI: anything drawn after the
 * mesh that overlaps it (the Cooldowns item, the minimap's labels) lands on top.
 *
 * This era's GuiRenderer draws every GUI mesh with a QUADS index buffer, so each
 * triangle goes out as a degenerate quad (a, b, c, c). The two pipelines are
 * vanilla's GUI / GUI_TEXTURED with culling OFF (vanilla's cull on, and these
 * triangles are wound either way); everything else -- shaders, translucent
 * blend, vertex format -- is vanilla's. Verified against the decompiled 1.21.6
 * and 1.21.8 GuiRenderer / GuiRenderState / RenderPipelines.
 */
public final class GuiMesh {
	/** vanilla RenderPipelines.GUI, unculled. */
	public static final RenderPipeline COLOR = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
					.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/gui_mesh"))
					.withCull(false)
					.build());

	/** vanilla RenderPipelines.GUI_TEXTURED, unculled. */
	public static final RenderPipeline TEXTURED = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/gui_mesh_textured"))
					.withCull(false)
					.build());

	private final ResourceLocation texture;   // null = flat colour
	private float[] data = new float[5 * 96];  // x, y, u, v, colour-bits per vertex
	private int vertices = 0;

	private GuiMesh(ResourceLocation texture) {
		this.texture = texture;
	}

	/** POSITION_COLOR triangles. */
	public static GuiMesh colors() {
		return new GuiMesh(null);
	}

	/** POSITION_TEX_COLOR triangles sampling the given texture. */
	public static GuiMesh textured(ResourceLocation texture) {
		return new GuiMesh(texture);
	}

	public boolean isEmpty() {
		return vertices == 0;
	}

	/** One vertex; every three make a triangle. */
	public GuiMesh vertex(float x, float y, float u, float v, int argb) {
		if ((vertices + 1) * 5 > data.length) {
			data = Arrays.copyOf(data, data.length * 2);
		}
		int i = vertices * 5;
		data[i] = x;
		data[i + 1] = y;
		data[i + 2] = u;
		data[i + 3] = v;
		data[i + 4] = Float.intBitsToFloat(argb);
		vertices++;
		return this;
	}

	public GuiMesh tri(float x0, float y0, float x1, float y1, float x2, float y2, int argb) {
		return vertex(x0, y0, 0, 0, argb).vertex(x1, y1, 0, 0, argb).vertex(x2, y2, 0, 0, argb);
	}

	public GuiMesh quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
		tri(x0, y0, x1, y1, x2, y2, argb);
		return tri(x0, y0, x2, y2, x3, y3, argb);
	}

	public GuiMesh rect(float x0, float y0, float x1, float y1, int argb) {
		return quad(x0, y0, x0, y1, x1, y1, x1, y0, argb);
	}

	/** Submits everything added so far, under g's current pose and scissor. */
	public void draw(GuiGraphics g) {
		int n = vertices - vertices % 3;
		if (n < 3) {
			return;
		}
		TextureSetup tex;
		if (texture != null) {
			GpuTextureView view = Minecraft.getInstance().getTextureManager().getTexture(texture).getTextureView();
			if (view == null) {
				return;
			}
			tex = TextureSetup.singleTexture(view);
		} else {
			tex = TextureSetup.noTexture();
		}
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int k = 0; k < n; k++) {
			float x = data[k * 5], y = data[k * 5 + 1];
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		int bx = (int) Math.floor(minX), by = (int) Math.floor(minY);
		ScreenRectangle local = new ScreenRectangle(bx, by,
				Math.max(1, (int) Math.ceil(maxX) - bx), Math.max(1, (int) Math.ceil(maxY) - by)).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(local) : local;
		if (bounds == null) {
			return;   // fully scissored away
		}
		g.guiRenderState.submitGuiElement(new MeshRenderState(texture != null ? TEXTURED : COLOR, tex, pose,
				Arrays.copyOf(data, n * 5), n, texture != null, scissor, bounds));
	}

	private record MeshRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose,
								   float[] data, int count, boolean uv,
								   ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer out, float depth) {
			for (int t = 0; t + 2 < count; t += 3) {
				put(out, t, depth);
				put(out, t + 1, depth);
				put(out, t + 2, depth);
				put(out, t + 2, depth);   // degenerate 4th corner: the GUI draws QUADS
			}
		}

		private void put(VertexConsumer out, int k, float depth) {
			int i = k * 5;
			VertexConsumer v = out.addVertexWith2DPose(pose, data[i], data[i + 1], depth);
			if (uv) {
				v.setUv(data[i + 2], data[i + 3]);
			}
			v.setColor(Float.floatToRawIntBits(data[i + 4]));
		}
	}
}
