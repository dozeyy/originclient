package com.origin.client.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * Faint orbit hairlines (A-Workbench-3): tilted pill (stadium) outlines -- two straight sides and
 * perfect semicircle ends, never ellipses (Will, 2026-10-25) -- 1 physical px wide, drawn behind the
 * title's logo (three rings at 0 / 60 / 120 deg) and the loading picture's logo (one ring). They
 * echo the Origin mark's rings; the mark itself is still only origin_logo.png, never redrawn.
 *
 * <p>Cost: the rings are baked ONCE into a static vertex buffer (DEBUG_LINES, POSITION_COLOR) per
 * geometry, and every later frame is one draw of that buffer with no allocation. The geometry only
 * changes on a resize. Opacity rides the shader colour, so a fade never rebuilds the buffer.
 *
 * <p>Fail-soft: any throw latches this instance off for the session (the screen simply has no
 * rings). Render thread only.
 */
public final class OrbitHairlines {
	/** Segments per semicircular end (the flat sides are one segment each). */
	private static final int ARC_SEGMENTS = 48;

	private final int rings;
	private final double firstDeg;
	private VertexBuffer vbo;
	private float kCx = Float.NaN, kCy, kA, kB;
	private int kArgb;
	private boolean failed;
	private final Matrix4f modelView = new Matrix4f();

	public OrbitHairlines(int rings, double firstDeg) {
		this.rings = rings;
		this.firstDeg = firstDeg;
	}

	/**
	 * Draws the rings centred on (cx, cy) in the current GUI pose, half-extents a (long) x b (short, = the end radius), GUI px,
	 * ring i tilted firstDeg + i * 60 deg, colour argb, its alpha further scaled by {@code alpha}.
	 */
	public void draw(GuiGraphics g, float cx, float cy, float a, float b, int argb, float alpha) {
		if (failed || alpha <= 0.004f || a <= 0f || b <= 0f) {
			return;
		}
		try {
			ShaderInstance shader = GameRenderer.getPositionColorShader();
			if (shader == null) {
				return;   // not loaded yet (the very start of the first reload): no rings this frame
			}
			if (vbo == null || cx != kCx || cy != kCy || a != kA || b != kB || argb != kArgb) {
				rebuild(cx, cy, a, b, argb);
			}
			g.flush();   // anything batched draws first, so the rings stay where the caller put them
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, Math.min(1f, alpha));
			modelView.set(RenderSystem.getModelViewMatrix()).mul(g.pose().last().pose());
			vbo.bind();
			vbo.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
			VertexBuffer.unbind();
		} catch (Throwable t) {
			failed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin orbit hairlines failed; drawing none this session", t);
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	/**
	 * One pill's outline as a closed loop of points {x0, y0, x1, y1, ...} (the last joins the first), centred on
	 * (cx, cy), half-extents a (long) x b (short, = the end radius), tilted {@code tiltDeg}. The one geometry for
	 * the hairlines and the loading screen's progress ring (OrbitProgress), so the fill lies exactly on its track.
	 *
	 * <p>Stadium outline in the ring's own frame: the right semicircle about (L, 0) from -90 to +90 deg, then the
	 * left one about (-L, 0) from +90 to +270 deg; consecutive arcs join with one straight segment, the stadium's
	 * flat sides. L = a - b, so the long extent is 2a and the short one 2b (never an ellipse).
	 */
	static float[] stadium(double cx, double cy, double a, double b, double tiltDeg) {
		double half = Math.max(0.0, a - b), rad = Math.min(a, b);
		double tilt = Math.toRadians(tiltDeg), ct = Math.cos(tilt), st = Math.sin(tilt);
		int n = 2 * (ARC_SEGMENTS + 1);
		float[] out = new float[2 * n];
		for (int k = 0; k < n; k++) {
			boolean right = k <= ARC_SEGMENTS;
			int j = right ? k : k - (ARC_SEGMENTS + 1);
			double phi = Math.PI * (j / (double) ARC_SEGMENTS) + (right ? -Math.PI / 2.0 : Math.PI / 2.0);
			double ex = (right ? half : -half) + rad * Math.cos(phi), ey = rad * Math.sin(phi);
			out[2 * k] = (float) (cx + ex * ct - ey * st);
			out[2 * k + 1] = (float) (cy + ex * st + ey * ct);
		}
		return out;
	}

	private void rebuild(float cx, float cy, float a, float b, int argb) {
		int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, bl = argb & 0xFF, al = (argb >>> 24) & 0xFF;
		BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i < rings; i++) {
			float[] pts = stadium(cx, cy, a, b, firstDeg + i * 60.0);
			int n = pts.length / 2;
			for (int p = 0; p < n; p++) {
				int q = (p + 1) % n;   // closed: the last point joins the first
				bb.addVertex(pts[2 * p], pts[2 * p + 1], 0f).setColor(r, gg, bl, al);
				bb.addVertex(pts[2 * q], pts[2 * q + 1], 0f).setColor(r, gg, bl, al);
			}
		}
		MeshData mesh = bb.build();
		if (mesh == null) {
			throw new IllegalStateException("empty orbit mesh");
		}
		if (vbo == null) {
			vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
		}
		vbo.bind();
		vbo.upload(mesh);   // upload() closes the mesh
		VertexBuffer.unbind();
		kCx = cx;
		kCy = cy;
		kA = a;
		kB = b;
		kArgb = argb;
	}
}
