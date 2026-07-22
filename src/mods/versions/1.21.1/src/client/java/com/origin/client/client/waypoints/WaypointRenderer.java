package com.origin.client.client.waypoints;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
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

			// Half-height beam column (160 blocks), depth-tested lines.
			if (w.showBeam && dist <= cutoff) {
				drawBeam(pose, buf, (float) dx, (float) dz,
						(float) (w.y - 3 - c.y), (float) (w.y + 160 - c.y), w.color);
			}
			if (w.highlightBlock) {
				drawBlockOutline(pose, buf, (float) (w.x - c.x), (float) (w.y - c.y), (float) (w.z - c.z), w.color);
			}
		}
		buf.endBatch();
	}

	private static void drawBeam(PoseStack pose, MultiBufferSource buf, float x, float z,
								 float y0, float y1, int color) {
		VertexConsumer vc = buf.getBuffer(RenderType.lines());
		var last = pose.last();
		Matrix4f m = last.pose();
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		vc.addVertex(m, x, y0, z).setColor(argb).setNormal(last, 0f, 1f, 0f);
		vc.addVertex(m, x, y1, z).setColor(argb).setNormal(last, 0f, 1f, 0f);
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
