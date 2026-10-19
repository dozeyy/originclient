package com.origin.client.client.waypoints;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.AfterShadersPass;
import com.origin.client.client.render.OriginRenderTypes;
import com.origin.client.client.render.ThickLine;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

// The WORLD-SPACE half of waypoint rendering: the beam and the block highlight —
// the parts that belong in the world and are allowed to be hidden by terrain
// (the beam is deliberately the ONLY occluded part; the highlight hugs a block
// face so it shows whenever the block does). Runs on WorldRenderEvents.LAST.
//
// The icon + name + distance are NOT drawn here: they render as a HUD overlay
// (WaypointHud) projected from world position to screen — always on top,
// visible through blocks, and immune to world lighting/shader pipelines (the
// old SEE_THROUGH world text was depth-tested and re-lit under Iris, which is
// exactly what made it invisible). Each frame this pass hands the camera
// matrices to WaypointHud for that projection.
public final class WaypointRenderer {
	private WaypointRenderer() {
	}

	// Shader packs: with an Iris pack on, the world part below is NOT drawn at
	// LAST but by AfterShadersPass (right after the pack's composite), so a
	// pack's clouds can't cover the beam. See that class for the why and the
	// fail-soft rule. Shaders OFF: unchanged -- drawn at LAST, same calls, same
	// order, into the frame's shared bufferSource.
	private static boolean warnedFailure;

	/** First failure of the LAST pass goes to the log once, with its stack. */
	private static void warnOnce(String where, Throwable t) {
		if (!warnedFailure) {
			warnedFailure = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: waypoint {} pass failed (logged once)", where, t);
		}
	}

	// AfterShadersPass slot; -1 = not registered, so the LAST entry keeps drawing.
	private static int slot = -1;

	/** Registers the after-shaders drawer (from OriginClientMod, in draw order). */
	public static void init() {
		slot = AfterShadersPass.register("waypoints", WaypointRenderer::drawDeferred);
	}

	public static void render(WorldRenderContext ctx) {
		try {
			renderLast(ctx);
		} catch (Throwable t) {
			warnOnce("world (LAST)", t);
		}
	}

	// The ONE early-out for both paths: waypoints mod on and a player in a world.
	// Returns the client, or null when there is nothing to draw.
	private static Minecraft active() {
		if (!Mods.on("waypoints")) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null || mc.level == null ? null : mc;
	}

	private static void renderLast(WorldRenderContext ctx) {
		Minecraft mc = active();
		if (mc == null) {
			return;
		}
		PoseStack pose = ctx.matrixStack();
		if (pose == null) {
			return;
		}
		// Hand the view/projection matrices + camera position to the HUD overlay
		// (also while the world part is deferred: this is the HUD's own capture).
		WaypointHud.capture(ctx);
		if (AfterShadersPass.deferring(slot)) {
			return;   // AfterShadersPass.run() draws it, after the pack's composite
		}
		drawWorld(mc, pose, ctx.camera().getPosition(), mc.renderBuffers().bufferSource());
	}

	// The AfterShadersPass drawer: same drawWorld body as the LAST path, with the
	// pass's recorded matrices and its own line buffer, behind the same active() check.
	private static void drawDeferred(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource lines) {
		Minecraft mc = active();
		if (mc != null) {
			drawWorld(mc, pose, cam, lines);
		}
	}

	private static void drawWorld(Minecraft mc, PoseStack pose, Vec3 c, MultiBufferSource.BufferSource buf) {
		String dim = mc.level.dimension().location().toString();
		// Beam hard-cutoff distance (no fade): the player's render distance, in blocks.
		double cutoff = mc.options.getEffectiveRenderDistance() * 16.0;

		try {
			for (Waypoints.Waypoint w : Waypoints.all()) {
				if (!w.enabled || !dim.equals(w.dimension)) {
					continue;
				}
				double dx = (w.x + 0.5) - c.x;
				double dz = (w.z + 0.5) - c.z;
				double dist = Math.sqrt(dx * dx + (w.y + 1 - c.y) * (w.y + 1 - c.y) + dz * dz);

				// Beam column (y-3 .. y+160), depth-tested camera-facing quad.
				if (w.showBeam && dist <= cutoff) {
					drawBeam(pose, dx, dz, w.y - 3 - c.y, w.y + 160 - c.y, w.color);
				}
				if (w.highlightBlock) {
					drawBlockOutline(pose, buf, (float) (w.x - c.x), (float) (w.y - c.y), (float) (w.z - c.z), w.color);
				}
			}
		} finally {
			// Always flush, also after a throw mid-loop, so no half-built vertices
			// stay in either buffer for the next caller (normal path: same two
			// calls, same order, as before).
			try {
				buf.endBatch();
			} finally {
				OriginRenderTypes.draw();   // the beams
			}
		}
	}

	// The beam is REAL geometry (ThickLine.vertical), not a RenderType.lines()
	// segment: lines are widened in screen space from their projected endpoints,
	// and a 163-block segment with one end behind the camera projected
	// sign-flipped -- the beam swung and bent as the camera turned. Its width
	// grows with horizontal distance (~2.5px on screen, like the old line) so it
	// stays readable out to the render-distance cutoff, never thinner than
	// 0.2 blocks up close. Depth-tested, so terrain still hides it.
	private static void drawBeam(PoseStack pose, double x, double z, double y0, double y1, int color) {
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
		float a = ((argb >>> 24) & 0xFF) / 255f;
		double halfWidth = Math.max(0.1, Math.sqrt(x * x + z * z) * 0.0016);
		ThickLine.vertical(OriginRenderTypes.begin(), pose.last(), x, z, y0, y1, halfWidth, r, g, b, a);
	}

	// "Highlight Block": a clean 12-edge box outline around the waypoint's block,
	// in the waypoint's colour. Entirely self-contained — independent of the Block
	// Outline mod (which only styles the crosshair-target block).
	private static void drawBlockOutline(PoseStack pose, MultiBufferSource buf,
										 float x, float y, float z, int color) {
		VertexConsumer vc = buf.getBuffer(RenderType.lines());
		var last = pose.last();
		Matrix4f m = last.pose();
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		float x1 = x + 1f, y1 = y + 1f, z1 = z + 1f;
		// bottom square, top square, then the four verticals
		edge(vc, m, last, x, y, z, x1, y, z, argb);
		edge(vc, m, last, x1, y, z, x1, y, z1, argb);
		edge(vc, m, last, x1, y, z1, x, y, z1, argb);
		edge(vc, m, last, x, y, z1, x, y, z, argb);
		edge(vc, m, last, x, y1, z, x1, y1, z, argb);
		edge(vc, m, last, x1, y1, z, x1, y1, z1, argb);
		edge(vc, m, last, x1, y1, z1, x, y1, z1, argb);
		edge(vc, m, last, x, y1, z1, x, y1, z, argb);
		edge(vc, m, last, x, y, z, x, y1, z, argb);
		edge(vc, m, last, x1, y, z, x1, y1, z, argb);
		edge(vc, m, last, x1, y, z1, x1, y1, z1, argb);
		edge(vc, m, last, x, y, z1, x, y1, z1, argb);
	}

	private static void edge(VertexConsumer vc, Matrix4f m, PoseStack.Pose pose,
							 float ax, float ay, float az, float bx, float by, float bz, int argb) {
		float nx = bx - ax, ny = by - ay, nz = bz - az;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0e-5f) {
			return;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		vc.addVertex(m, ax, ay, az).setColor(argb).setNormal(pose, nx, ny, nz);
		vc.addVertex(m, bx, by, bz).setColor(argb).setNormal(pose, nx, ny, nz);
	}
}
