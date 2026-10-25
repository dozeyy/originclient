package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * The loading screens' progress element (A-Workbench-3, "From Play to the main menu": "one orbit ring
 * fills in emerald with the real progress"): a stroke laid along one of the faint orbit pills behind
 * the loading logo, from the pill's topmost point round its path, covering exactly the share of the
 * loop that the load reports. The faint pill underneath (OrbitHairlines) stays as its track. With no
 * real measure, a fixed-length segment travels the loop instead (never a made-up number).
 *
 * <p>Geometry: the pill's outline comes from {@link OrbitHairlines#stadium} (the same points as its
 * track), in physical pixels, with per-point normals and the running length along the loop. It is
 * built once per size and cached; a frame only walks the cached arrays and emits quads (a core at full
 * colour and a 1 px feather each side fading to clear, so the stroke is smooth at every tilt). Nothing
 * is allocated per frame beyond what GuiGraphics' own buffer does for any fill.
 *
 * <p>Fail-soft: a throw latches this off for the session ({@link #failed}) and the caller falls back
 * to the straight rail. Render thread only.
 */
final class OrbitProgress {
	private float[] px, py, nx, ny, cum;
	private int n;
	private float total;
	private double kCx = Double.NaN, kCy, kA, kB, kTilt;
	private boolean failed;

	boolean failed() {
		return failed;
	}

	/**
	 * Draws the stroke from {@code from} to {@code to} (shares of the loop, 0 = the pill's top; {@code to}
	 * may pass 1, which wraps round to the start). Centre, half-extents and stroke width are physical px;
	 * the pose is scaled to physical pixels here. True = drawn (or nothing to draw); false = latched off.
	 */
	boolean draw(GuiGraphics g, int gs, double cx, double cy, double a, double b, double tiltDeg,
				 double from, double to, float width, int argb) {
		if (failed) {
			return false;
		}
		if ((argb >>> 24) == 0 || to <= from) {
			return true;
		}
		try {
			if (px == null || cx != kCx || cy != kCy || a != kA || b != kB || tiltDeg != kTilt) {
				build(cx, cy, a, b, tiltDeg);
			}
			PoseStack pose = g.pose();
			pose.pushPose();
			pose.scale(1f / gs, 1f / gs, 1f);
			try {
				Matrix4f m = pose.last().pose();
				VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
				float hw = Math.max(0.5f, width / 2f - 0.5f);   // core half-width; the feather adds 1 px each side
				int clear = argb & 0x00FFFFFF;
				if (to > 1.0) {
					span(vc, m, from * total, total, hw, argb, clear);
					span(vc, m, 0, Math.min(1.0, to - 1.0) * total, hw, argb, clear);
				} else {
					span(vc, m, from * total, to * total, hw, argb, clear);
				}
				g.flush();
			} finally {
				pose.popPose();
			}
			return true;
		} catch (Throwable t) {
			failed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin loading progress ring failed; using the straight bar this session", t);
			return false;
		}
	}

	/** The loop between lengths s0 and s1 (0 <= s0 < s1 <= total), segment by segment. */
	private void span(VertexConsumer vc, Matrix4f m, double s0, double s1, float hw, int argb, int clear) {
		if (s1 - s0 < 0.01) {
			return;
		}
		for (int i = 0; i < n; i++) {
			float c0 = cum[i], c1 = cum[i + 1];
			if (c1 <= s0) {
				continue;
			}
			if (c0 >= s1) {
				break;
			}
			int j = (i + 1) % n;
			float len = c1 - c0;
			float t0 = len <= 0f ? 0f : (float) Math.max(0.0, (s0 - c0) / len);
			float t1 = len <= 0f ? 1f : (float) Math.min(1.0, (s1 - c0) / len);
			float ax = lerp(px[i], px[j], t0), ay = lerp(py[i], py[j], t0);
			float bx = lerp(px[i], px[j], t1), by = lerp(py[i], py[j], t1);
			float anx = lerp(nx[i], nx[j], t0), any = lerp(ny[i], ny[j], t0);
			float bnx = lerp(nx[i], nx[j], t1), bny = lerp(ny[i], ny[j], t1);
			// Core, then the two 1 px feathers (inner edge full colour, outer edge clear).
			band(vc, m, ax, ay, anx, any, bx, by, bnx, bny, -hw, hw, argb, argb);
			band(vc, m, ax, ay, anx, any, bx, by, bnx, bny, hw, hw + 1f, argb, clear);
			band(vc, m, ax, ay, anx, any, bx, by, bnx, bny, -hw, -hw - 1f, argb, clear);
		}
	}

	/** One quad between offsets o0 and o1 along the normals, coloured c0 at o0 and c1 at o1. */
	private static void band(VertexConsumer vc, Matrix4f m, float ax, float ay, float anx, float any,
							 float bx, float by, float bnx, float bny, float o0, float o1, int c0, int c1) {
		float x0 = ax + anx * o0, y0 = ay + any * o0;   // a, inner
		float x1 = ax + anx * o1, y1 = ay + any * o1;   // a, outer
		float x2 = bx + bnx * o1, y2 = by + bny * o1;   // b, outer
		float x3 = bx + bnx * o0, y3 = by + bny * o0;   // b, inner
		// RenderType.gui() culls back faces: keep GuiGraphics.fill's winding (negative shoelace area, y down).
		float area = (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3);
		if (area <= 0f) {
			vc.addVertex(m, x0, y0, 0f).setColor(c0);
			vc.addVertex(m, x1, y1, 0f).setColor(c1);
			vc.addVertex(m, x2, y2, 0f).setColor(c1);
			vc.addVertex(m, x3, y3, 0f).setColor(c0);
		} else {
			vc.addVertex(m, x3, y3, 0f).setColor(c0);
			vc.addVertex(m, x2, y2, 0f).setColor(c1);
			vc.addVertex(m, x1, y1, 0f).setColor(c1);
			vc.addVertex(m, x0, y0, 0f).setColor(c0);
		}
	}

	private void build(double cx, double cy, double a, double b, double tiltDeg) {
		float[] pts = OrbitHairlines.stadium(cx, cy, a, b, tiltDeg);
		int count = pts.length / 2;
		// Start at the topmost point, so the fill grows from the top of the ring.
		int top = 0;
		for (int i = 1; i < count; i++) {
			if (pts[2 * i + 1] < pts[2 * top + 1]) {
				top = i;
			}
		}
		px = new float[count];
		py = new float[count];
		nx = new float[count];
		ny = new float[count];
		cum = new float[count + 1];
		for (int i = 0; i < count; i++) {
			int k = (top + i) % count;
			px[i] = pts[2 * k];
			py[i] = pts[2 * k + 1];
		}
		for (int i = 0; i < count; i++) {
			int prev = (i - 1 + count) % count, next = (i + 1) % count;
			float tx = px[next] - px[prev], ty = py[next] - py[prev];
			float len = (float) Math.sqrt(tx * tx + ty * ty);
			nx[i] = len > 0f ? -ty / len : 0f;
			ny[i] = len > 0f ? tx / len : 0f;
		}
		float acc = 0f;
		for (int i = 0; i < count; i++) {
			cum[i] = acc;
			int j = (i + 1) % count;
			float dx = px[j] - px[i], dy = py[j] - py[i];
			acc += (float) Math.sqrt(dx * dx + dy * dy);
		}
		cum[count] = acc;
		n = count;
		total = acc;
		kCx = cx;
		kCy = cy;
		kA = a;
		kB = b;
		kTilt = tiltDeg;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
