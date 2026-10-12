package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;

import java.util.Arrays;

/**
 * Free-form triangles in the GUI, for the Lunar-parity mods that draw shapes a
 * rectangle fill can't: the Cooldowns dials, the Minimap (round map, rotated
 * terrain, arrows, diamonds) and the Shinypots shine.
 *
 * WHY THIS EXISTS (1.21.6+ deferred GUI, verified against decompiled 1.21.10):
 * 1.21.1 drew these with an immediate Tesselator/BufferUploader draw. Here the
 * GUI is DEFERRED -- GuiGraphics only records render states, and GuiRenderer
 * draws them later in a layer order of its own -- so an immediate draw would
 * land under everything recorded earlier in the frame. A GuiElementRenderState
 * submitted into g.guiRenderState is the era's way in (the same shape as the
 * module's OriginUi.IconSdfRenderState / ModIcons).
 *
 * The GUI pipelines take QUADS and CULL back faces (RenderPipeline.Builder
 * defaults cull to true; GUI_SNIPPET never turns it off). So each triangle is
 * emitted as a degenerate quad (v0, v1, v2, v2) and re-wound, when needed, to
 * vanilla's front-facing order: the order fill() emits a rectangle in (top-left,
 * bottom-left, bottom-right), i.e. a negative (x1-x0)(y2-y0)-(y1-y0)(x2-x0).
 * Fans and rotated shapes may arrive wound either way, and none get culled.
 *
 * Usage: GuiTris t = GuiTris.color(); t.tri(...); ...; t.submit(g);
 */
public final class GuiTris {
	private final RenderPipeline pipeline;
	private final GpuTextureView view;
	private final boolean textured;
	// per vertex: x, y, u, v (4 floats) + its colour in a parallel int array.
	// (Colours stay ints: an ARGB packed into float bits can be a NaN pattern,
	// which the JVM is allowed to canonicalise.)
	private float[] data = new float[4 * 3 * 16];
	private int[] colors = new int[3 * 16];
	private int floats = 0, verts = 0;
	private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

	private GuiTris(RenderPipeline pipeline, GpuTextureView view) {
		this.pipeline = pipeline;
		this.view = view;
		this.textured = view != null;
	}

	/** Plain coloured triangles (vanilla's GUI pipeline: POSITION_COLOR, translucent). */
	public static GuiTris color() {
		return new GuiTris(RenderPipelines.GUI, null);
	}

	/** Textured, tinted triangles (POSITION_TEX_COLOR) through the given pipeline.
	 *  1.21.10: filtering / wrapping are the texture's own state (no samplers). */
	public static GuiTris textured(RenderPipeline pipeline, GpuTextureView view) {
		return new GuiTris(pipeline, view);
	}

	public boolean isEmpty() {
		return floats == 0;
	}

	public void tri(float x0, float y0, float x1, float y1, float x2, float y2, int argb) {
		tri(x0, y0, 0, 0, x1, y1, 0, 0, x2, y2, 0, 0, argb);
	}

	public void tri(float x0, float y0, float u0, float v0, float x1, float y1, float u1, float v1,
					float x2, float y2, float u2, float v2, int argb) {
		if (((x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)) > 0) {
			// wound the culled way round: swap the last two corners
			float tx = x1, ty = y1, tu = u1, tv = v1;
			x1 = x2;
			y1 = y2;
			u1 = u2;
			v1 = v2;
			x2 = tx;
			y2 = ty;
			u2 = tu;
			v2 = tv;
		}
		ensure(12);
		put(x0, y0, u0, v0, argb);
		put(x1, y1, u1, v1, argb);
		put(x2, y2, u2, v2, argb);
	}

	/** A quad as two triangles (corners in order round the edge, either direction). */
	public void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
		tri(x0, y0, x1, y1, x2, y2, argb);
		tri(x0, y0, x2, y2, x3, y3, argb);
	}

	public void rect(float x0, float y0, float x1, float y1, int argb) {
		quad(x0, y0, x0, y1, x1, y1, x1, y0, argb);
	}

	private void ensure(int more) {
		if (floats + more > data.length) {
			data = Arrays.copyOf(data, Math.max(data.length * 2, floats + more));
		}
		if (verts + 3 > colors.length) {
			colors = Arrays.copyOf(colors, Math.max(colors.length * 2, verts + 3));
		}
	}

	private void put(float x, float y, float u, float v, int argb) {
		data[floats++] = x;
		data[floats++] = y;
		data[floats++] = u;
		data[floats++] = v;
		colors[verts++] = argb;
		minX = Math.min(minX, x);
		minY = Math.min(minY, y);
		maxX = Math.max(maxX, x);
		maxY = Math.max(maxY, y);
	}

	/** Records everything added so far into the frame, under the current pose + scissor. */
	public void submit(GuiGraphics g) {
		if (floats == 0) {
			return;
		}
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
		int w = Math.max(1, (int) Math.ceil(maxX) - x0), h = Math.max(1, (int) Math.ceil(maxY) - y0);
		ScreenRectangle local = new ScreenRectangle(x0, y0, w, h).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(local) : local;
		if (bounds == null) {
			return;   // wholly scissored away
		}
		TextureSetup tex = textured ? TextureSetup.singleTexture(view) : TextureSetup.noTexture();
		g.guiRenderState.submitGuiElement(new TrisRenderState(pipeline, tex, pose,
				Arrays.copyOf(data, floats), Arrays.copyOf(colors, verts), textured, scissor, bounds));
	}

	private record TrisRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose,
								   float[] data, int[] colors, boolean textured, ScreenRectangle scissorArea,
								   ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vc) {
			for (int k = 0; k + 3 <= colors.length; k += 3) {
				vertex(vc, k);
				vertex(vc, k + 1);
				vertex(vc, k + 2);
				vertex(vc, k + 2);   // degenerate 4th corner: QUADS -> one real triangle
			}
		}

		private void vertex(VertexConsumer vc, int k) {
			int o = k * 4;
			VertexConsumer v = vc.addVertexWith2DPose(pose, data[o], data[o + 1]);
			if (textured) {
				v.setUv(data[o + 2], data[o + 3]);
			}
			v.setColor(colors[k]);
		}
	}
}
