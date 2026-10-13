package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.OriginRenderTypes;
import com.origin.client.client.render.ThickLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.MathHelper;

// Chunk Borders overlay, fully wired to the mod's options:
//   Grid          — the current chunk's footprint (4 full-height edges), a
//                   section ring at every 16 blocks of Y, and a floor lattice
//                   every `gridSize` blocks. Uses color + thickness.
//   Inner Corners — tall posts at the current chunk's 4 corners (own colour/width).
//   Outer Corners — posts at the 4 corners one chunk further out (own colour/width).
// Posts are real camera-facing quads (ThickLine.vertical) whose width IS the
// thickness -- the same look as every other version; rings and the floor
// lattice are short lines thickened with parallel offset passes (GL line width
// is unreliable). Colours carry their own alpha.
//
// 1.8.9: drawn from WorldHooks' RenderWorldLastEvent, where the GL model-view is
// exactly the camera transform (rotation + eye offset) -- vertices are the world
// minus RenderManager.viewerPos (the interpolated camera entity's feet), the
// space vanilla's own entities and outlines are emitted in, so the camera
// rotation is applied once by GL and never by us. World floor y=0, top
// World.getHeight() (256).
public final class ChunkBorderRenderer {
	private ChunkBorderRenderer() {
	}

	public static void render(float partialTicks) {
		if (!Mods.on("chunkborders")) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.theWorld == null) {
			return;
		}
		RenderManager rm = mc.getRenderManager();
		double camX = rm.viewerPosX, camY = rm.viewerPosY, camZ = rm.viewerPosZ;
		int chunkX = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
		int chunkZ = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
		double x0 = (chunkX << 4) - camX, x1 = x0 + 16;
		double z0 = (chunkZ << 4) - camZ, z1 = z0 + 16;
		double yMin = 0.0 - camY;
		double yMax = mc.theWorld.getHeight() - camY;

		// Rings + lattice go through Origin's private LINES buffer, posts through
		// its quads buffer; both are flushed together by draw() at the end.
		WorldRenderer buf = OriginRenderTypes.beginLines();
		WorldRenderer posts = OriginRenderTypes.begin();

		if (Mods.bool("chunkborders", "grid")) {
			int col = OriginColorPicker.liveColor("chunkborders", "color");
			int t = thickness("thickness");
			// full-height edges of the chunk
			post(posts, x0, z0, yMin, yMax, col, t);
			post(posts, x1, z0, yMin, yMax, col, t);
			post(posts, x0, z1, yMin, yMax, col, t);
			post(posts, x1, z1, yMin, yMax, col, t);
			// section ring at each 16-block Y boundary
			int yBase = (MathHelper.floor_double(mc.thePlayer.posY / 16.0)) * 16;
			for (int i = -4; i <= 4; i++) {
				double y = yBase + i * 16 - camY;
				if (y < yMin || y > yMax) {
					continue;
				}
				ring(buf, x0, z0, x1, z1, y, col, t);
			}
			// floor lattice every `gridSize` blocks, at the player's foot level
			int step = (int) Math.max(1, Math.min(16, Mods.num("chunkborders", "gridSize")));
			double fy = Math.floor(mc.thePlayer.posY) - camY;
			for (int i = step; i < 16; i += step) {
				line(buf, x0 + i, fy, z0, x0 + i, fy, z1, col);
				line(buf, x0, fy, z0 + i, x1, fy, z0 + i, col);
			}
		}

		if (Mods.bool("chunkborders", "innerCorners")) {
			int col = OriginColorPicker.liveColor("chunkborders", "innerColor");
			int t = thickness("innerThickness");
			double py = mc.thePlayer.posY;
			double cy0 = Math.max(yMin, py - 48 - camY), cy1 = Math.min(yMax, py + 48 - camY);
			post(posts, x0, z0, cy0, cy1, col, t);
			post(posts, x1, z0, cy0, cy1, col, t);
			post(posts, x0, z1, cy0, cy1, col, t);
			post(posts, x1, z1, cy0, cy1, col, t);
		}

		if (Mods.bool("chunkborders", "outerCorners")) {
			int col = OriginColorPicker.liveColor("chunkborders", "outerColor");
			int t = thickness("outerThickness");
			double py = mc.thePlayer.posY;
			double cy0 = Math.max(yMin, py - 48 - camY), cy1 = Math.min(yMax, py + 48 - camY);
			post(posts, x0 - 16, z0 - 16, cy0, cy1, col, t);
			post(posts, x1 + 16, z0 - 16, cy0, cy1, col, t);
			post(posts, x0 - 16, z1 + 16, cy0, cy1, col, t);
			post(posts, x1 + 16, z1 + 16, cy0, cy1, col, t);
		}
		OriginRenderTypes.draw();   // the posts + the lines
	}

	private static int thickness(String key) {
		return (int) Math.max(1, Math.min(3, Mods.num("chunkborders", key)));
	}

	// Vertical post at (x,z) from y0..y1. Real geometry, NOT a GL line: the
	// posts must look the same as on every other version (a GL line is a fixed
	// few pixels wide). Width per thickness step is ~2px on screen at any
	// distance (scaled by horizontal distance), never under 2cm up close.
	private static void post(WorldRenderer q, double x, double z,
							 double y0, double y1, int color, int thickness) {
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 0.9f;   // same fallback as line()
		}
		double halfWidth = Math.max(0.01, Math.sqrt(x * x + z * z) * 0.0013) * thickness;
		ThickLine.vertical(q, x, z, y0, y1, halfWidth, r, g, b, a);
	}

	// horizontal rectangle ring at height y
	private static void ring(WorldRenderer buf, double x0, double z0,
							 double x1, double z1, double y, int color, int passes) {
		for (int p = 0; p < passes; p++) {
			double o = p * 0.03;
			line(buf, x0, y + o, z0, x1, y + o, z0, color);
			line(buf, x1, y + o, z0, x1, y + o, z1, color);
			line(buf, x1, y + o, z1, x0, y + o, z1, color);
			line(buf, x0, y + o, z1, x0, y + o, z0, color);
		}
	}

	private static void line(WorldRenderer buf,
							 double ax, double ay, double az, double bx, double by, double bz, int color) {
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 0.9f;
		}
		buf.pos(ax, ay, az).color(r, g, b, a).endVertex();
		buf.pos(bx, by, bz).color(r, g, b, a).endVertex();
	}
}
