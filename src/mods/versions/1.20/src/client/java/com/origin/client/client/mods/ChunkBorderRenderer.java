package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// Chunk Borders overlay, fully wired to the mod's options:
//   Grid          — the current chunk's footprint (4 full-height edges), a
//                   section ring at every 16 blocks of Y, and a floor lattice
//                   every `gridSize` blocks. Uses color + thickness.
//   Inner Corners — tall posts at the current chunk's 4 corners (own colour/width).
//   Outer Corners — posts at the 4 corners one chunk further out (own colour/width).
// Thickness is emulated with parallel offset passes (core-GL line width is
// unreliable). Drawn from the world-render hook; colours carry their own alpha.
public final class ChunkBorderRenderer {
	private ChunkBorderRenderer() {
	}

	public static void render(WorldRenderContext context) {
		if (!Mods.on("chunkborders")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || context.consumers() == null || context.matrixStack() == null) {
			return;
		}

		Vec3 cam = context.camera().getPosition();
		int chunkX = Mth.floor(mc.player.getX()) >> 4;
		int chunkZ = Mth.floor(mc.player.getZ()) >> 4;
		double x0 = (chunkX << 4) - cam.x, x1 = x0 + 16;
		double z0 = (chunkZ << 4) - cam.z, z1 = z0 + 16;
		double yMin = mc.level.getMinBuildHeight() - cam.y;
		double yMax = mc.level.getMaxBuildHeight() - cam.y;

		PoseStack.Pose pose = context.matrixStack().last();
		VertexConsumer buf = context.consumers().getBuffer(RenderType.lines());

		if (Mods.bool("chunkborders", "grid")) {
			int col = OriginColorPicker.liveColor("chunkborders", "color");
			int t = thickness("thickness");
			// full-height edges of the chunk
			post(buf, pose, x0, z0, yMin, yMax, col, t);
			post(buf, pose, x1, z0, yMin, yMax, col, t);
			post(buf, pose, x0, z1, yMin, yMax, col, t);
			post(buf, pose, x1, z1, yMin, yMax, col, t);
			// section ring at each 16-block Y boundary
			int yBase = (Mth.floor(mc.player.getY() / 16.0)) * 16;
			for (int i = -4; i <= 4; i++) {
				double y = yBase + i * 16 - cam.y;
				if (y < yMin || y > yMax) {
					continue;
				}
				ring(buf, pose, x0, z0, x1, z1, y, col, t);
			}
			// floor lattice every `gridSize` blocks, at the player's foot level
			int step = (int) Math.max(1, Math.min(16, Mods.num("chunkborders", "gridSize")));
			double fy = Math.floor(mc.player.getY()) - cam.y;
			for (int i = step; i < 16; i += step) {
				line(buf, pose, x0 + i, fy, z0, x0 + i, fy, z1, col);
				line(buf, pose, x0, fy, z0 + i, x1, fy, z0 + i, col);
			}
		}

		if (Mods.bool("chunkborders", "innerCorners")) {
			int col = OriginColorPicker.liveColor("chunkborders", "innerColor");
			int t = thickness("innerThickness");
			double py = mc.player.getY();
			double cy0 = Math.max(yMin, py - 48 - cam.y), cy1 = Math.min(yMax, py + 48 - cam.y);
			post(buf, pose, x0, z0, cy0, cy1, col, t);
			post(buf, pose, x1, z0, cy0, cy1, col, t);
			post(buf, pose, x0, z1, cy0, cy1, col, t);
			post(buf, pose, x1, z1, cy0, cy1, col, t);
		}

		if (Mods.bool("chunkborders", "outerCorners")) {
			int col = OriginColorPicker.liveColor("chunkborders", "outerColor");
			int t = thickness("outerThickness");
			double py = mc.player.getY();
			double cy0 = Math.max(yMin, py - 48 - cam.y), cy1 = Math.min(yMax, py + 48 - cam.y);
			post(buf, pose, x0 - 16, z0 - 16, cy0, cy1, col, t);
			post(buf, pose, x1 + 16, z0 - 16, cy0, cy1, col, t);
			post(buf, pose, x0 - 16, z1 + 16, cy0, cy1, col, t);
			post(buf, pose, x1 + 16, z1 + 16, cy0, cy1, col, t);
		}
	}

	private static int thickness(String key) {
		return (int) Math.max(1, Math.min(3, Mods.num("chunkborders", key)));
	}

	// vertical post at (x,z) from y0..y1, thickened with parallel offset passes
	private static void post(VertexConsumer buf, PoseStack.Pose pose, double x, double z,
							 double y0, double y1, int color, int passes) {
		for (int p = 0; p < passes; p++) {
			double o = p * 0.03;
			line(buf, pose, x + o, y0, z + o, x + o, y1, z + o, color);
		}
	}

	// horizontal rectangle ring at height y
	private static void ring(VertexConsumer buf, PoseStack.Pose pose, double x0, double z0,
							 double x1, double z1, double y, int color, int passes) {
		for (int p = 0; p < passes; p++) {
			double o = p * 0.03;
			line(buf, pose, x0, y + o, z0, x1, y + o, z0, color);
			line(buf, pose, x1, y + o, z0, x1, y + o, z1, color);
			line(buf, pose, x1, y + o, z1, x0, y + o, z1, color);
			line(buf, pose, x0, y + o, z1, x0, y + o, z0, color);
		}
	}

	private static void line(VertexConsumer buf, PoseStack.Pose pose,
							 double ax, double ay, double az, double bx, double by, double bz, int color) {
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 0.9f;
		}
		float nx = (float) (bx - ax), ny = (float) (by - ay), nz = (float) (bz - az);
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) {
			nx /= len;
			ny /= len;
			nz /= len;
		}
		buf.vertex(pose.pose(), (float) ax, (float) ay, (float) az).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
		buf.vertex(pose.pose(), (float) bx, (float) by, (float) bz).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
	}
}
