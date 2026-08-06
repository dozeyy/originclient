package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

// World-space overlays for 26.2's submit/gizmo pipeline. RenderType.lines() +
// MultiBufferSource are gone; lines now go through SubmitNodeCollector —
// submitCustomGeometry(pose, RenderTypes.lines(), (pose, buf) -> emit) for the
// chunk-border grid, and submitShapeOutline(pose, shape, RenderTypes.lines(),
// color, width, false) per entity box for hitboxes. Driven from
// LevelRenderEvents.COLLECT_SUBMITS. All coordinates are camera-relative (the
// pose is the view transform with the camera at the origin), matching how vanilla
// submits world geometry.
public final class OriginWorldRender {
	private OriginWorldRender() {
	}

	public static void collect(LevelRenderContext ctx) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) {
				return;
			}
			Vec3 cam = ctx.levelState().cameraRenderState.pos;
			if (Mods.on("chunkborders")) {
				chunkBorders(ctx, mc, cam);
			}
			// Hitboxes are drawn by HitboxMixin (the native gizmo system — smooth stroke
			// width, interpolated, cheap), NOT here. The old submit-outline path stuttered
			// and the ThickLine path was too heavy.
		} catch (Throwable t) {
			// A bad frame must never take the level render down.
		}
	}

	// ---- chunk borders ----

	private static void chunkBorders(LevelRenderContext ctx, Minecraft mc, Vec3 cam) {
		int chunkX = Mth.floor(mc.player.getX()) >> 4;
		int chunkZ = Mth.floor(mc.player.getZ()) >> 4;
		double x0 = (chunkX << 4) - cam.x, x1 = x0 + 16;
		double z0 = (chunkZ << 4) - cam.z, z1 = z0 + 16;
		double yMin = mc.level.getMinY() - cam.y;
		double yMax = mc.level.getMaxY() - cam.y;
		double playerY = mc.player.getY();

		ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.lines(), (pose, buf) -> {
			if (Mods.bool("chunkborders", "grid")) {
				int col = OriginColorPicker.liveColor("chunkborders", "color");
				int t = thickness("thickness");
				post(buf, pose, x0, z0, yMin, yMax, col, t);
				post(buf, pose, x1, z0, yMin, yMax, col, t);
				post(buf, pose, x0, z1, yMin, yMax, col, t);
				post(buf, pose, x1, z1, yMin, yMax, col, t);
				int yBase = Mth.floor(playerY / 16.0) * 16;
				for (int i = -4; i <= 4; i++) {
					double y = yBase + i * 16 - cam.y;
					if (y < yMin || y > yMax) {
						continue;
					}
					ring(buf, pose, x0, z0, x1, z1, y, col, t);
				}
				int step = (int) Math.max(1, Math.min(16, Mods.num("chunkborders", "gridSize")));
				double fy = Math.floor(playerY) - cam.y;
				for (int i = step; i < 16; i += step) {
					line(buf, pose, x0 + i, fy, z0, x0 + i, fy, z1, col);
					line(buf, pose, x0, fy, z0 + i, x1, fy, z0 + i, col);
				}
			}
			if (Mods.bool("chunkborders", "innerCorners")) {
				int col = OriginColorPicker.liveColor("chunkborders", "innerColor");
				int t = thickness("innerThickness");
				double cy0 = Math.max(yMin, playerY - 48 - cam.y), cy1 = Math.min(yMax, playerY + 48 - cam.y);
				post(buf, pose, x0, z0, cy0, cy1, col, t);
				post(buf, pose, x1, z0, cy0, cy1, col, t);
				post(buf, pose, x0, z1, cy0, cy1, col, t);
				post(buf, pose, x1, z1, cy0, cy1, col, t);
			}
			if (Mods.bool("chunkborders", "outerCorners")) {
				int col = OriginColorPicker.liveColor("chunkborders", "outerColor");
				int t = thickness("outerThickness");
				double cy0 = Math.max(yMin, playerY - 48 - cam.y), cy1 = Math.min(yMax, playerY + 48 - cam.y);
				post(buf, pose, x0 - 16, z0 - 16, cy0, cy1, col, t);
				post(buf, pose, x1 + 16, z0 - 16, cy0, cy1, col, t);
				post(buf, pose, x0 - 16, z1 + 16, cy0, cy1, col, t);
				post(buf, pose, x1 + 16, z1 + 16, cy0, cy1, col, t);
			}
		});
	}

	private static int thickness(String key) {
		return (int) Math.max(1, Math.min(3, Mods.num("chunkborders", key)));
	}

	private static void post(VertexConsumer buf, PoseStack.Pose pose, double x, double z,
							 double y0, double y1, int color, int passes) {
		for (int p = 0; p < passes; p++) {
			double o = p * 0.03;
			line(buf, pose, x + o, y0, z + o, x + o, y1, z + o, color);
		}
	}

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
		int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
		int a = (color >>> 24) & 0xFF;
		if (a <= 0) {
			a = 230;
		}
		float nx = (float) (bx - ax), ny = (float) (by - ay), nz = (float) (bz - az);
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) {
			nx /= len;
			ny /= len;
			nz /= len;
		}
		// RenderTypes.lines() REQUIRES a per-vertex LineWidth element — omitting it
		// throws "Missing elements in vertex" when the deferred submitCustomGeometry
		// lambda flushes (in the render frame, OUTSIDE collect()'s try/catch, so it
		// crashes the game rather than fail-soft). Thickness is emulated via the
		// offset passes in post()/ring(), so a unit width here just satisfies the format.
		buf.addVertex(pose, (float) ax, (float) ay, (float) az).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(1.0f);
		buf.addVertex(pose, (float) bx, (float) by, (float) bz).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(1.0f);
	}

	// ---- hitboxes ----

	private static void hitboxes(LevelRenderContext ctx, Minecraft mc, Vec3 cam) {
		int color = OriginColorPicker.liveColor("hitboxes", "lineColor");
		float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 1f;
		}
		// lineWidth 0.5..N → block-space thickness, same feel as the block outline.
		double lw = Math.max(0.5, Mods.num("hitboxes", "lineWidth"));
		double maxD = Mods.num("hitboxes", "maxDistance");
		double maxSq = maxD * maxD;
		final float fr = r, fg = g, fb = b, fa = a;
		final double ft = 0.006 + (lw - 1) * 0.005;
		// Draw each box's 12 edges as SOLID geometry (ThickLine → debugQuads), not
		// RenderTypes.lines() GL lines. GL lines can't set a reliable per-draw width
		// and read jagged/flickery at distance ("glitchy"); solid edges are smooth and
		// consistent, matching the Block Outline. One submit for all boxes.
		ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.debugQuads(), (pose, q) -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e == mc.player && mc.options.getCameraType().isFirstPerson()) {
					continue;
				}
				if (!hitboxEnabled(e) || e.distanceToSqr(cam) > maxSq) {
					continue;
				}
				net.minecraft.world.phys.AABB box = e.getBoundingBox().move(-cam.x, -cam.y, -cam.z);
				boxEdges(q, pose, box, ft, fr, fg, fb, fa);
			}
		});
	}

	// The 12 edges of an AABB, each a solid ThickLine box (smooth, real thickness).
	private static void boxEdges(VertexConsumer q, PoseStack.Pose pose, net.minecraft.world.phys.AABB box,
								 double t, float r, float g, float b, float a) {
		double x0 = box.minX, y0 = box.minY, z0 = box.minZ, x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
		double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
		com.origin.client.client.render.ThickLine.edge(q, pose, x0, y0, z0, x1, y0, z0, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x1, y0, z0, x1, y0, z1, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x1, y0, z1, x0, y0, z1, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x0, y0, z1, x0, y0, z0, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x0, y1, z0, x1, y1, z0, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x1, y1, z0, x1, y1, z1, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x1, y1, z1, x0, y1, z1, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x0, y1, z1, x0, y1, z0, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x0, y0, z0, x0, y1, z0, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x1, y0, z0, x1, y1, z0, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x1, y0, z1, x1, y1, z1, cx, cy, cz, t, r, g, b, a);
		com.origin.client.client.render.ThickLine.edge(q, pose, x0, y0, z1, x0, y1, z1, cx, cy, cz, t, r, g, b, a);
	}

	// Maps an entity to the mod's category toggles (a pragmatic subset of the
	// full option list — players/monsters/passive/items/projectiles/other).
	private static boolean hitboxEnabled(Entity e) {
		if (e instanceof Player) {
			return Mods.bool("hitboxes", "players");
		}
		if (e instanceof Enemy) {
			return Mods.bool("hitboxes", "monsters");
		}
		if (e instanceof Animal) {
			return Mods.bool("hitboxes", "passive");
		}
		if (e instanceof ItemEntity) {
			return Mods.bool("hitboxes", "items");
		}
		if (e instanceof Projectile) {
			return Mods.bool("hitboxes", "projectiles");
		}
		return Mods.bool("hitboxes", "other");
	}
}
