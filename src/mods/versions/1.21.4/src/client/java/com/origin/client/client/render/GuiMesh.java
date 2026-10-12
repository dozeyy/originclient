package com.origin.client.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * Free-form GUI triangles (fans, rings, rotated quads, a textured map) for the
 * Lunar-parity HUD pieces that vanilla's rectangle fills can't draw: the
 * Cooldowns dials and the Minimap. Coordinates are GUI-local (under the
 * current pose); winding doesn't matter.
 *
 * PER-VERSION (1.21.2-1.21.4): drawn immediate-mode like 1.21.1's code did, but
 * this era's GuiGraphics BATCHES its own fills/blits/text, so draw() flushes that
 * batch first (everything submitted before lands underneath, everything after
 * on top) and uses this era's CoreShaders keys. Depth test off and culling off,
 * as 1.21.1's minimap/dials drew: GUI items earlier in the frame wrote depth
 * that would otherwise hide flat HUD geometry. 1.21.6+ modules have the same
 * API on top of GuiElementRenderState instead.
 */
public final class GuiMesh {
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

	/** Draws everything added so far, now, under g's current pose. */
	public void draw(GuiGraphics g) {
		if (vertices < 3) {
			return;
		}
		g.flush();   // GuiGraphics' batch first: earlier GUI content goes underneath
		Matrix4f m = g.pose().last().pose();
		BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
				texture != null ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
		int n = vertices - vertices % 3;
		for (int k = 0; k < n; k++) {
			int i = k * 5;
			int c = Float.floatToRawIntBits(data[i + 4]);
			var v = b.addVertex(m, data[i], data[i + 1], 0);
			if (texture != null) {
				v.setUv(data[i + 2], data[i + 3]);
			}
			v.setColor((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF);
		}
		MeshData mesh = b.build();
		if (mesh == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.disableDepthTest();
		if (texture != null) {
			RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
			RenderSystem.setShaderTexture(0, texture);
		} else {
			RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		}
		BufferUploader.drawWithShader(mesh);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}
}
