package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

// Chunk Borders overlay (F3+G-style, but themable): draws the player's
// current chunk as corner pillars + horizontal rings every 16 blocks, in the
// configured color, from the world-render hook. Line "thickness" is
// emulated with offset passes (GL core line width is unreliable).
public final class ChunkBorderRenderer {
	private ChunkBorderRenderer() {
	}

	public static void render(WorldRenderContext context) {
		if (!Mods.on("chunkborders")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || context.consumers() == null || context.matrixStack() == null) {
			return;
		}

		int color = Mods.color("chunkborders", "color");
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		int passes = (int) Math.max(1, Math.min(3, Mods.num("chunkborders", "thickness")));

		Vec3 cam = context.camera().getPosition();
		int cx = ((int) Math.floor(mc.player.getX())) >> 4;
		int cz = ((int) Math.floor(mc.player.getZ())) >> 4;
		double x0 = (cx << 4) - cam.x, x1 = x0 + 16;
		double z0 = (cz << 4) - cam.z, z1 = z0 + 16;
		int yBase = (int) Math.floor(mc.player.getY() / 16.0) * 16;

		PoseStack.Pose pose = context.matrixStack().last();
		VertexConsumer buf = context.consumers().getBuffer(RenderType.lines());

		for (int pass = 0; pass < passes; pass++) {
			double o = pass * 0.02;
			// corner pillars (±48 blocks around the player's section)
			for (double[] c : new double[][]{{x0 - o, z0 - o}, {x1 + o, z0 - o}, {x0 - o, z1 + o}, {x1 + o, z1 + o}}) {
				line(buf, pose, c[0], yBase - 48 - cam.y, c[1], c[0], yBase + 64 - cam.y, c[1], r, g, b);
			}
			// horizontal rings at each section boundary
			for (int i = -3; i <= 4; i++) {
				double y = yBase + i * 16 - cam.y;
				line(buf, pose, x0 - o, y, z0 - o, x1 + o, y, z0 - o, r, g, b);
				line(buf, pose, x1 + o, y, z0 - o, x1 + o, y, z1 + o, r, g, b);
				line(buf, pose, x1 + o, y, z1 + o, x0 - o, y, z1 + o, r, g, b);
				line(buf, pose, x0 - o, y, z1 + o, x0 - o, y, z0 - o, r, g, b);
			}
		}
	}

	private static void line(VertexConsumer buf, PoseStack.Pose pose,
							 double ax, double ay, double az, double bx, double by, double bz,
							 float r, float g, float b) {
		float nx = (float) (bx - ax), ny = (float) (by - ay), nz = (float) (bz - az);
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) {
			nx /= len;
			ny /= len;
			nz /= len;
		}
		buf.addVertex(pose, (float) ax, (float) ay, (float) az).setColor(r, g, b, 0.9f).setNormal(pose, nx, ny, nz);
		buf.addVertex(pose, (float) bx, (float) by, (float) bz).setColor(r, g, b, 0.9f).setNormal(pose, nx, ny, nz);
	}
}
