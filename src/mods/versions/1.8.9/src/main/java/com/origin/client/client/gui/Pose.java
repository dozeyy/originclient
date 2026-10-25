package com.origin.client.client.gui;

import net.minecraft.client.renderer.GlStateManager;

/**
 * 1.8.9's stand-in for PoseStack: a thin face over the FIXED-FUNCTION GL matrix
 * stack. 1.8.9 has no matrix objects at all -- every vertex goes through the
 * current GL model-view matrix -- so the 1.16.5 code that pushes, translates,
 * scales and rotates a PoseStack ports by a type swap (PoseStack -> Pose), and
 * the vertices it emits afterwards (wr.pos(x, y, z), FontRenderer, RenderItem,
 * Gui.drawRect ...) pick the transform up from GL by themselves.
 *
 * What does NOT exist: {@code last().pose()} (there is no matrix to hand out).
 * 1.16.5's {@code buf.vertex(matrix, x, y, z)} becomes {@code wr.pos(x, y, z)}.
 *
 * Every Pose instance drives the SAME GL stack, so `new Pose()` works anywhere
 * (GUI, HUD, world rendering). Each instance counts its own pushes and ignores
 * a pop it never pushed: an unbalanced pop in Origin code must never underflow
 * the GL stack under vanilla's feet (GL_STACK_UNDERFLOW corrupts the frame).
 * Render thread only.
 */
public final class Pose {
	private int depth = 0;

	public Pose() {
	}

	/** glPushMatrix on the current (model-view) stack. */
	public void pushPose() {
		Gfx.flushBatch();   // queued fills were made under the matrix we are about to change
		GlStateManager.pushMatrix();
		depth++;
	}

	/** glPopMatrix -- only if this Pose pushed (see the class comment). */
	public void popPose() {
		if (depth > 0) {
			depth--;
			Gfx.flushBatch();
			GlStateManager.popMatrix();
		}
	}

	/** Pops everything this Pose still has pushed (fail-soft cleanup after a throw). */
	public void popAll() {
		while (depth > 0) {
			popPose();
		}
	}

	/** How many pushes this Pose has open. */
	public int depth() {
		return depth;
	}

	public void translate(double x, double y, double z) {
		Gfx.flushBatch();
		GlStateManager.translate(x, y, z);
	}

	public void scale(float x, float y, float z) {
		Gfx.flushBatch();
		GlStateManager.scale(x, y, z);
	}

	/** PoseStack.mulPose(quaternion) -- here an axis-angle rotation (see {@link Axis}). */
	public void mulPose(Rotation r) {
		if (r != null && r.degrees != 0f) {
			Gfx.flushBatch();
			GlStateManager.rotate(r.degrees, r.x, r.y, r.z);
		}
	}

	/** 1.16.5 had `Vector3f.ZP.rotationDegrees(deg)` (pre-JOML) / later `Axis.ZP`; here `Pose.Axis.ZP`. */
	public enum Axis {
		XP(1f, 0f, 0f), XN(-1f, 0f, 0f),
		YP(0f, 1f, 0f), YN(0f, -1f, 0f),
		ZP(0f, 0f, 1f), ZN(0f, 0f, -1f);

		private final float x, y, z;

		Axis(float x, float y, float z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		public Rotation rotationDegrees(float degrees) {
			return new Rotation(degrees, x, y, z);
		}

		public Rotation rotation(float radians) {
			return new Rotation((float) Math.toDegrees(radians), x, y, z);
		}
	}

	/** An axis-angle rotation in DEGREES (what glRotatef takes). */
	public static final class Rotation {
		public final float degrees, x, y, z;

		public Rotation(float degrees, float x, float y, float z) {
			this.degrees = degrees;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}
}
