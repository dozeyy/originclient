package com.origin.client.client.waypoints;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
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

	public static void render(WorldRenderContext ctx) {
		if (!Mods.on("waypoints")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		PoseStack pose = ctx.matrixStack();
		if (pose == null) {
			return;
		}
		// Hand the view/projection matrices + camera position to the HUD overlay.
		WaypointHud.capture(ctx);

		String dim = mc.level.dimension().location().toString();
		Vec3 c = ctx.camera().getPosition();
		MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
		// Beam hard-cutoff distance (no fade): the player's render distance, in blocks.
		double cutoff = mc.options.getEffectiveRenderDistance() * 16.0;

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
		buf.endBatch();
		OriginRenderTypes.draw();   // the beams
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
