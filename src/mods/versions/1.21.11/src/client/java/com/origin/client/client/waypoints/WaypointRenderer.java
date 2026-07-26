package com.origin.client.client.waypoints;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

// The WORLD-SPACE half of waypoint rendering: the beam and the block highlight —
// the parts that belong in the world and are allowed to be hidden by terrain
// (the beam is deliberately the ONLY occluded part; the highlight hugs a block
// face so it shows whenever the block does). Runs on WorldRenderEvents.END_MAIN.
//
// The icon + name + distance are NOT drawn here: they render as a HUD overlay
// (WaypointHud) projected from world position to screen — always on top,
// visible through blocks, and immune to world lighting/shader pipelines (the
// old SEE_THROUGH world text was depth-tested and re-lit under Iris, which is
// exactly what made it invisible). Each frame this pass hands the camera state
// to WaypointHud for that projection.
//
// PER-VERSION DELTA (1.21.11), all mirroring ChunkBorderRenderer in this module:
//  - WorldRenderContext moved to ...rendering.v1.world, and the camera position
//    now comes from context.worldState().cameraRenderState.pos rather than
//    ctx.camera().
//  - RenderType.lines() -> RenderTypes.lines().
//  - The lines vertex format REQUIRES a per-vertex LineWidth element; omitting
//    it throws "Missing elements in vertex: LineWidth" at the shared buffer
//    flush, outside any try/catch of ours.
//  - Camera matrices are no longer readable (RenderSystem.getProjectionMatrix is
//    gone), so capture() hands WaypointHud the camera position + orientation
//    quaternion instead and the HUD builds the projection itself.
public final class WaypointRenderer {
	private WaypointRenderer() {
	}

	public static void render(WorldRenderContext ctx) {
		if (!Mods.on("waypoints")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || ctx.matrices() == null || ctx.consumers() == null) {
			return;
		}
		PoseStack pose = ctx.matrices();
		// Hand the camera position + orientation to the HUD overlay.
		WaypointHud.capture(ctx);

		String dim = mc.level.dimension().identifier().toString();
		Vec3 c = ctx.worldState().cameraRenderState.pos;
		MultiBufferSource buf = ctx.consumers();
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
	}

	private static void drawBeam(PoseStack pose, MultiBufferSource buf, float x, float z,
								 float y0, float y1, int color) {
		VertexConsumer vc = buf.getBuffer(RenderTypes.lines());
		edge(vc, pose.last(), x, y0, z, x, y1, z, opaque(color));
	}

	// "Highlight Block": a clean 12-edge box outline around the waypoint's block,
	// in the waypoint's colour. Entirely self-contained — independent of the Block
	// Outline mod (which only styles the crosshair-target block).
	private static void drawBlockOutline(PoseStack pose, MultiBufferSource buf,
										 float x, float y, float z, int color) {
		VertexConsumer vc = buf.getBuffer(RenderTypes.lines());
		PoseStack.Pose last = pose.last();
		int argb = opaque(color);
		float x1 = x + 1f, y1 = y + 1f, z1 = z + 1f;
		// bottom square, top square, then the four verticals
		edge(vc, last, x, y, z, x1, y, z, argb);
		edge(vc, last, x1, y, z, x1, y, z1, argb);
		edge(vc, last, x1, y, z1, x, y, z1, argb);
		edge(vc, last, x, y, z1, x, y, z, argb);
		edge(vc, last, x, y1, z, x1, y1, z, argb);
		edge(vc, last, x1, y1, z, x1, y1, z1, argb);
		edge(vc, last, x1, y1, z1, x, y1, z1, argb);
		edge(vc, last, x, y1, z1, x, y1, z, argb);
		edge(vc, last, x, y, z, x, y1, z, argb);
		edge(vc, last, x1, y, z, x1, y1, z, argb);
		edge(vc, last, x1, y, z1, x1, y1, z1, argb);
		edge(vc, last, x, y, z1, x, y1, z1, argb);
	}

	private static void edge(VertexConsumer vc, PoseStack.Pose pose,
							 float ax, float ay, float az, float bx, float by, float bz, int argb) {
		float nx = bx - ax, ny = by - ay, nz = bz - az;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0e-5f) {
			return;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		vc.addVertex(pose, ax, ay, az).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(1.0f);
		vc.addVertex(pose, bx, by, bz).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(1.0f);
	}

	private static int opaque(int color) {
		return (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
	}
}
