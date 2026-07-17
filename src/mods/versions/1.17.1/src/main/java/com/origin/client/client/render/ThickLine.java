package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Draws an axis-aligned edge as a solid box, so a line can actually get thicker.
 *
 * Why this exists: vanilla's RenderType.lines() builds its LineStateShard with
 * OptionalDouble.empty(), which pins GL line width to
 * max(2.5, windowWidth/1920*2.5) with no way to set it per-draw. Both the hitbox
 * and block-outline renderers used to fake width by stacking concentric copies
 * of the whole wireframe a few millimetres apart. That never worked: at any
 * distance the copies converge into one line, and near the corners they fan out
 * into the little inward "dots" Will saw. More lines is not a thicker line.
 *
 * So thickness becomes real geometry -- each edge is a thin cuboid of side `t`
 * drawn as quads. One edge, one box, genuinely thicker.
 *
 * The box is biased INWARD: its outer face sits exactly on the original edge, so
 * raising thickness eats into the shape instead of ballooning outward off it.
 */
public final class ThickLine {

	/**
	 * How far the outer face stands proud of the surface it hugs. Purely a
	 * depth-fighting escape: a face landing exactly on the block's own face is
	 * coplanar with it, and the outline flickers through the block as the camera
	 * moves. 1mm at block scale clears it and is invisible.
	 */
	private static final double EPS = 0.001;

	private ThickLine() {
	}

	/**
	 * Emits one axis-aligned edge as a box.
	 *
	 * @param c  the shape's centre, in the SAME space as the edge coords -- used
	 *           only to decide which way "inward" is per axis.
	 * @param t  thickness in blocks.
	 */
	public static void edge(VertexConsumer q, PoseStack.Pose pose,
			double ax, double ay, double az, double bx, double by, double bz,
			double cx, double cy, double cz, double t,
			float r, float g, float b, float a) {
		double[] p0 = {ax, ay, az};
		double[] p1 = {bx, by, bz};
		double[] c = {cx, cy, cz};
		double[] lo = new double[3];
		double[] hi = new double[3];
		for (int i = 0; i < 3; i++) {
			if (p0[i] != p1[i]) {
				// The edge runs along this axis: keep its full length, so corners
				// still meet and no gaps open up.
				lo[i] = Math.min(p0[i], p1[i]);
				hi[i] = Math.max(p0[i], p1[i]);
			} else {
				// Across the edge: grow inward from the surface, but let the outer
				// face stand EPS proud of it. Landing exactly on the surface makes
				// the two coplanar, and the depth buffer can't separate them -- the
				// outline then flickers in and out of the block as the camera moves.
				// EPS is small enough to still read as paint on the block.
				double s = Math.signum(p0[i] - c[i]);
				if (s > 0) {
					lo[i] = p0[i] - t;
					hi[i] = p0[i] + EPS;
				} else if (s < 0) {
					lo[i] = p0[i] - EPS;
					hi[i] = p0[i] + t;
				} else {
					// Dead centre (a flat shape on this axis): straddle it.
					lo[i] = p0[i] - t / 2 - EPS;
					hi[i] = p0[i] + t / 2 + EPS;
				}
			}
		}
		box(q, pose, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], r, g, b, a);
	}

	/** Six quads, wound so every face is visible (the render type is NO_CULL). */
	private static void box(VertexConsumer q, PoseStack.Pose pose,
			double x0, double y0, double z0, double x1, double y1, double z1,
			float r, float g, float b, float a) {
		float ax = (float) x0, ay = (float) y0, az = (float) z0;
		float bx = (float) x1, by = (float) y1, bz = (float) z1;
		// down / up
		quad(q, pose, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, r, g, b, a);
		quad(q, pose, ax, by, bz, bx, by, bz, bx, by, az, ax, by, az, r, g, b, a);
		// north / south
		quad(q, pose, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az, r, g, b, a);
		quad(q, pose, bx, ay, bz, bx, by, bz, ax, by, bz, ax, ay, bz, r, g, b, a);
		// west / east
		quad(q, pose, ax, ay, bz, ax, by, bz, ax, by, az, ax, ay, az, r, g, b, a);
		quad(q, pose, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz, r, g, b, a);
	}

	private static void quad(VertexConsumer q, PoseStack.Pose pose,
			float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, float x3, float y3, float z3,
			float r, float g, float b, float a) {
		q.vertex(pose.pose(), x0, y0, z0).color(r, g, b, a).endVertex();
		q.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).endVertex();
		q.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).endVertex();
		q.vertex(pose.pose(), x3, y3, z3).color(r, g, b, a).endVertex();
	}
}
