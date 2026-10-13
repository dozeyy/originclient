package com.origin.client.client.waypoints;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginRenderTypes;
import com.origin.client.client.render.ThickLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;

// The WORLD-SPACE half of waypoint rendering: the beam and the block highlight —
// the parts that belong in the world and are allowed to be hidden by terrain
// (the beam is deliberately the ONLY occluded part; the highlight hugs a block
// face so it shows whenever the block does).
//
// 1.8.9: runs from WorldHooks' RenderWorldLastEvent, where the GL model-view is
// the camera transform; vertices are the world minus RenderManager.viewerPos
// (the interpolated camera entity's feet), so GL applies the camera rotation
// exactly once (the newer modules' "rotated twice" bug cannot happen here).
//
// The icon + name + distance are NOT drawn here: they render as a HUD overlay
// (WaypointHud) projected from world position to screen — always on top,
// visible through blocks, and immune to world lighting/shader pipelines. Each
// frame this pass hands the camera matrices to WaypointHud for that projection.
public final class WaypointRenderer {
	private WaypointRenderer() {
	}

	public static void render(float partialTicks) {
		if (!Mods.on("waypoints")) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.theWorld == null) {
			return;
		}
		// Hand the view/projection matrices + camera position to the HUD overlay.
		WaypointHud.capture(partialTicks);

		RenderManager rm = mc.getRenderManager();
		double cx = rm.viewerPosX, cy = rm.viewerPosY, cz = rm.viewerPosZ;
		String dim = Waypoints.currentDim();
		// Beam hard-cutoff distance (no fade): the player's render distance, in blocks.
		double cutoff = mc.gameSettings.renderDistanceChunks * 16.0;

		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (!w.enabled || !dim.equals(w.dimension)) {
				continue;
			}
			double dx = (w.x + 0.5) - cx;
			double dz = (w.z + 0.5) - cz;
			double dist = Math.sqrt(dx * dx + (w.y + 1 - cy) * (w.y + 1 - cy) + dz * dz);

			// Beam column (y-3 .. y+160), depth-tested camera-facing quad.
			if (w.showBeam && dist <= cutoff) {
				drawBeam(dx, dz, w.y - 3 - cy, w.y + 160 - cy, w.color);
			}
			if (w.highlightBlock) {
				drawBlockOutline(w.x - cx, w.y - cy, w.z - cz, w.color);
			}
		}
		OriginRenderTypes.draw();   // the beams + highlight boxes
	}

	// The beam is REAL geometry (ThickLine.vertical), not a GL line: it must LOOK
	// the same as on every other version (mandate 2) -- its width grows with
	// horizontal distance (~2.5px on screen) so it stays readable out to the
	// render-distance cutoff, never thinner than 0.2 blocks up close.
	// Depth-tested, so terrain still hides it.
	private static void drawBeam(double x, double z, double y0, double y1, int color) {
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
		float a = ((argb >>> 24) & 0xFF) / 255f;
		double halfWidth = Math.max(0.1, Math.sqrt(x * x + z * z) * 0.0016);
		ThickLine.vertical(OriginRenderTypes.begin(), x, z, y0, y1, halfWidth, r, g, b, a);
	}

	// "Highlight Block": a clean 12-edge box outline around the waypoint's block,
	// in the waypoint's colour. Entirely self-contained — independent of the Block
	// Outline mod (which only styles the crosshair-target block). Through Origin's
	// private LINES buffer.
	private static void drawBlockOutline(double x, double y, double z, int color) {
		WorldRenderer vc = OriginRenderTypes.beginLines();
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		double x1 = x + 1, y1 = y + 1, z1 = z + 1;
		// bottom square, top square, then the four verticals
		edge(vc, x, y, z, x1, y, z, argb);
		edge(vc, x1, y, z, x1, y, z1, argb);
		edge(vc, x1, y, z1, x, y, z1, argb);
		edge(vc, x, y, z1, x, y, z, argb);
		edge(vc, x, y1, z, x1, y1, z, argb);
		edge(vc, x1, y1, z, x1, y1, z1, argb);
		edge(vc, x1, y1, z1, x, y1, z1, argb);
		edge(vc, x, y1, z1, x, y1, z, argb);
		edge(vc, x, y, z, x, y1, z, argb);
		edge(vc, x1, y, z, x1, y1, z, argb);
		edge(vc, x1, y, z1, x1, y1, z1, argb);
		edge(vc, x, y, z1, x, y1, z1, argb);
	}

	private static void edge(WorldRenderer vc, double ax, double ay, double az, double bx, double by, double bz, int argb) {
		int cr = (argb >> 16) & 0xFF, cg = (argb >> 8) & 0xFF, cb = argb & 0xFF, ca = (argb >>> 24) & 0xFF;
		vc.pos(ax, ay, az).color(cr, cg, cb, ca).endVertex();
		vc.pos(bx, by, bz).color(cr, cg, cb, ca).endVertex();
	}
}
