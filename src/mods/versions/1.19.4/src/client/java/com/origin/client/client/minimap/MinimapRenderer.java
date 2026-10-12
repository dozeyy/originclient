package com.origin.client.client.minimap;

import com.origin.client.client.mods.Era;
import com.origin.client.client.gui.Gfx;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.client.waypoints.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one map view -- terrain, background, border, entity radar, waypoints, the
 * player arrow and compass letters -- for both the HUD minimap and the full-screen
 * map. Everything is immediate-mode geometry (like the Cooldowns dials), so a round
 * map is simply a textured triangle fan: no stencil, no scissor, and rotation is free
 * because only the texture coordinates turn.
 */
final class MinimapRenderer {
	private static final int CIRCLE_SEGMENTS = 72;
	private static final int MAX_ENTITY_MARKERS = 300;   // per-frame cap on radar dots

	private MinimapRenderer() {
	}

	/**
	 * A map on screen: centre (cx, cy) in local GUI units, its half-extent (a circle
	 * uses halfW as radius), the world point at the centre, pixels per block, and the
	 * rotation that turns world (x, z) offsets into screen offsets.
	 */
	static final class View {
		final float cx, cy, halfW, halfH;
		final boolean circle;
		final double wx, wz;
		final float ppb;
		final float cos, sin;

		View(float cx, float cy, float halfW, float halfH, boolean circle, double wx, double wz, float ppb, float angle) {
			this.cx = cx;
			this.cy = cy;
			this.halfW = halfW;
			this.halfH = circle ? halfW : halfH;
			this.circle = circle;
			this.wx = wx;
			this.wz = wz;
			this.ppb = ppb;
			this.cos = (float) Math.cos(angle);
			this.sin = (float) Math.sin(angle);
		}

		/** How far from the centre, in blocks, the corners of this view reach. */
		double radiusBlocks() {
			double r = circle ? halfW : Math.sqrt(halfW * halfW + halfH * halfH);
			return r / ppb;
		}

		float sx(double x, double z) {
			float dx = (float) (x - wx) * ppb, dz = (float) (z - wz) * ppb;
			return cx + dx * cos - dz * sin;
		}

		float sy(double x, double z) {
			float dx = (float) (x - wx) * ppb, dz = (float) (z - wz) * ppb;
			return cy + dx * sin + dz * cos;
		}

		/** World x/z under a screen point (the inverse rotation). */
		double worldX(float sx, float sy) {
			float dx = sx - cx, dy = sy - cy;
			return wx + (dx * cos + dy * sin) / ppb;
		}

		double worldZ(float sx, float sy) {
			float dx = sx - cx, dy = sy - cy;
			return wz + (-dx * sin + dy * cos) / ppb;
		}

		/** Whether a marker of half-size `m` at (x, y) fits fully inside the map. */
		boolean contains(float x, float y, float m) {
			float dx = x - cx, dy = y - cy;
			if (circle) {
				float r = halfW - m;
				return dx * dx + dy * dy <= r * r;
			}
			return Math.abs(dx) <= halfW - m && Math.abs(dy) <= halfH - m;
		}

		/** Pulls a point outside the map back onto its edge (inset by `m`). */
		float[] clampToEdge(float x, float y, float m) {
			float dx = x - cx, dy = y - cy;
			float k;
			if (circle) {
				float len = (float) Math.sqrt(dx * dx + dy * dy);
				k = len <= 0.001f ? 0 : (halfW - m) / len;
			} else {
				float kx = Math.abs(dx) <= 0.001f ? Float.MAX_VALUE : (halfW - m) / Math.abs(dx);
				float ky = Math.abs(dy) <= 0.001f ? Float.MAX_VALUE : (halfH - m) / Math.abs(dy);
				k = Math.min(kx, ky);
			}
			k = Math.min(1f, k);
			return new float[]{cx + dx * k, cy + dy * k};
		}
	}

	// ---- shape: background, terrain, border ----

	static void background(Matrix4f m, View v, int argb) {
		if (alpha(argb) == 0) {
			return;
		}
		BufferBuilder b = colorTris();
		shape(b, m, v, 0, argb);
		drawColor(b);
	}

	/** The terrain texture sampled under the view's rotation (REPEAT-wrapped ring buffer). */
	static void terrain(Matrix4f m, View v) {
		if (!MinimapData.ready()) {
			return;
		}
		// Keep the texture coordinates small: shift the centre by whole multiples of
		// the texture size so float precision holds even 30 million blocks out.
		double baseX = Math.floorMod((long) Math.floor(v.wx), MinimapData.SIZE) + (v.wx - Math.floor(v.wx));
		double baseZ = Math.floorMod((long) Math.floor(v.wz), MinimapData.SIZE) + (v.wz - Math.floor(v.wz));
		BufferBuilder b = Era.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
		if (v.circle) {
			for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
				double a0 = Math.PI * 2 * i / CIRCLE_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
				texVertex(b, m, v, baseX, baseZ, v.cx, v.cy);
				texVertex(b, m, v, baseX, baseZ, v.cx + (float) Math.cos(a0) * v.halfW, v.cy + (float) Math.sin(a0) * v.halfW);
				texVertex(b, m, v, baseX, baseZ, v.cx + (float) Math.cos(a1) * v.halfW, v.cy + (float) Math.sin(a1) * v.halfW);
			}
		} else {
			float x0 = v.cx - v.halfW, x1 = v.cx + v.halfW, y0 = v.cy - v.halfH, y1 = v.cy + v.halfH;
			texVertex(b, m, v, baseX, baseZ, x0, y0);
			texVertex(b, m, v, baseX, baseZ, x0, y1);
			texVertex(b, m, v, baseX, baseZ, x1, y1);
			texVertex(b, m, v, baseX, baseZ, x0, y0);
			texVertex(b, m, v, baseX, baseZ, x1, y1);
			texVertex(b, m, v, baseX, baseZ, x1, y0);
		}
		drawTex(b);
	}

	/**
	 * Terrain over one world-space rectangle only. The full-screen map uses this to
	 * clip to the scanned window: outside it, a ring-buffer slot may still hold some
	 * other place's pixels.
	 */
	static void terrainRect(Matrix4f m, View v, double x0, double z0, double x1, double z1) {
		if (!MinimapData.ready() || x1 <= x0 || z1 <= z0) {
			return;
		}
		double baseX = Math.floorMod((long) Math.floor(v.wx), MinimapData.SIZE) + (v.wx - Math.floor(v.wx));
		double baseZ = Math.floorMod((long) Math.floor(v.wz), MinimapData.SIZE) + (v.wz - Math.floor(v.wz));
		BufferBuilder b = Era.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
		double[][] c = {{x0, z0}, {x0, z1}, {x1, z1}, {x0, z0}, {x1, z1}, {x1, z0}};
		for (double[] p : c) {
			float u = (float) ((baseX + p[0] - v.wx) / MinimapData.SIZE);
			float t = (float) ((baseZ + p[1] - v.wz) / MinimapData.SIZE);
			b.vertex(m, v.sx(p[0], p[1]), v.sy(p[0], p[1]), 0).uv(u, t).color(255, 255, 255, 255).endVertex();
		}
		drawTex(b);
	}

	private static void drawTex(BufferBuilder b) {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, MinimapData.TEXTURE);
		Era.drawBuffer(b);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void texVertex(BufferBuilder b, Matrix4f m, View v, double baseX, double baseZ, float sx, float sy) {
		float dx = sx - v.cx, dy = sy - v.cy;
		double ox = (dx * v.cos + dy * v.sin) / v.ppb;
		double oz = (-dx * v.sin + dy * v.cos) / v.ppb;
		float u = (float) ((baseX + ox) / MinimapData.SIZE);
		float t = (float) ((baseZ + oz) / MinimapData.SIZE);
		b.vertex(m, sx, sy, 0).uv(u, t).color(255, 255, 255, 255).endVertex();
	}

	/** Border drawn INSIDE the map edge, so the element's box is exactly the map. */
	static void border(Matrix4f m, View v, float thickness, int argb) {
		if (alpha(argb) == 0 || thickness <= 0) {
			return;
		}
		BufferBuilder b = colorTris();
		if (v.circle) {
			float ro = v.halfW, ri = Math.max(0, v.halfW - thickness);
			for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
				double a0 = Math.PI * 2 * i / CIRCLE_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
				float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
				quad(b, m, v.cx + c0 * ro, v.cy + s0 * ro, v.cx + c1 * ro, v.cy + s1 * ro,
						v.cx + c1 * ri, v.cy + s1 * ri, v.cx + c0 * ri, v.cy + s0 * ri, argb);
			}
		} else {
			float x0 = v.cx - v.halfW, x1 = v.cx + v.halfW, y0 = v.cy - v.halfH, y1 = v.cy + v.halfH, t = thickness;
			rect(b, m, x0, y0, x1, y0 + t, argb);                 // top + bottom span the full width;
			rect(b, m, x0, y1 - t, x1, y1, argb);
			rect(b, m, x0, y0 + t, x0 + t, y1 - t, argb);         // the sides fill only the gap, so the
			rect(b, m, x1 - t, y0 + t, x1, y1 - t, argb);         // corners are never drawn twice
		}
		drawColor(b);
	}

	/** Fills the map's shape (inset by `inset`) -- used for the background. */
	private static void shape(BufferBuilder b, Matrix4f m, View v, float inset, int argb) {
		if (v.circle) {
			float r = v.halfW - inset;
			for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
				double a0 = Math.PI * 2 * i / CIRCLE_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
				tri(b, m, v.cx, v.cy, v.cx + (float) Math.cos(a0) * r, v.cy + (float) Math.sin(a0) * r,
						v.cx + (float) Math.cos(a1) * r, v.cy + (float) Math.sin(a1) * r, argb);
			}
		} else {
			rect(b, m, v.cx - v.halfW + inset, v.cy - v.halfH + inset, v.cx + v.halfW - inset, v.cy + v.halfH - inset, argb);
		}
	}

	// ---- entity radar ----

	/** Which radar categories to draw, their colours, and marker styling. */
	record Radar(boolean players, boolean heads, boolean hostile, boolean passive, boolean items, boolean other,
				 int playerColor, int hostileColor, int passiveColor, int itemColor, int otherColor,
				 float size, float opacity, boolean shadow, int verticalRange) {
	}

	static void entities(Gfx g, Minecraft mc, View v, Radar r, float partial) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		Matrix4f m = g.pose().last().pose();
		double reach = v.radiusBlocks() + 2;
		double reachSq = reach * reach;
		float half = 0.75f + r.size() * 0.5f;
		int headPx = Math.round(6 + r.size() * 2);
		BufferBuilder b = colorTris();
		boolean any = false;
		List<float[]> heads = new ArrayList<>();
		List<AbstractClientPlayer> headPlayers = new ArrayList<>();
		int count = 0;
		double py = mc.player.getY();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (count >= MAX_ENTITY_MARKERS) {
				break;
			}
			if (e == mc.player) {
				continue;
			}
			double ex = e.getX(), ez = e.getZ();
			double ddx = ex - v.wx, ddz = ez - v.wz;
			if (ddx * ddx + ddz * ddz > reachSq) {
				continue;
			}
			if (r.verticalRange() > 0 && Math.abs(e.getY() - py) > r.verticalRange()) {
				continue;
			}
			int color;
			if (e instanceof Player p) {
				if (!r.players() || p.isSpectator() || p.isInvisibleTo(mc.player)) {
					continue;
				}
				color = r.playerColor();
			} else if (e instanceof Enemy) {
				if (!r.hostile() || e.isInvisibleTo(mc.player)) {
					continue;
				}
				color = r.hostileColor();
			} else if (e instanceof ItemEntity) {
				if (!r.items()) {
					continue;
				}
				color = r.itemColor();
			} else if (e instanceof LivingEntity) {
				if (!r.passive() || e.isInvisibleTo(mc.player)) {
					continue;
				}
				color = r.passiveColor();
			} else {
				if (!r.other()) {
					continue;
				}
				color = r.otherColor();
			}
			// smooth motion between ticks
			double lx = e.xo + (ex - e.xo) * partial, lz = e.zo + (ez - e.zo) * partial;
			float sx = v.sx(lx, lz), sy = v.sy(lx, lz);
			if (r.heads() && e instanceof AbstractClientPlayer acp) {
				if (v.contains(sx, sy, headPx / 2f)) {
					heads.add(new float[]{sx, sy});
					headPlayers.add(acp);
					count++;
				}
				continue;
			}
			if (!v.contains(sx, sy, half)) {
				continue;
			}
			int a = Math.round(alpha(color) * r.opacity());
			if (r.shadow()) {
				rect(b, m, sx - half - 0.6f, sy - half - 0.6f, sx + half + 0.6f, sy + half + 0.6f, (a * 3 / 5) << 24);
			}
			rect(b, m, sx - half, sy - half, sx + half, sy + half, withAlpha(color, a));
			any = true;
			count++;
		}
		if (any) {
			drawColor(b);
		} else {
			discard(b);
		}
		// Player heads: vanilla's own face renderer (face + hat layer), drawn after the
		// dots so a player is never hidden under a mob marker.
		if (heads.isEmpty()) {
			return;
		}
		if (r.shadow()) {
			int a = Math.round(255 * r.opacity());
			BufferBuilder sb = colorTris();
			for (float[] p : heads) {
				float x = Math.round(p[0] - headPx / 2f), y = Math.round(p[1] - headPx / 2f);
				rect(sb, m, x - 1, y - 1, x + headPx + 1, y + headPx + 1, (a * 3 / 5) << 24);
			}
			drawColor(sb);
		}
		for (int i = 0; i < heads.size(); i++) {
			float[] p = heads.get(i);
			int x = Math.round(p[0] - headPx / 2f), y = Math.round(p[1] - headPx / 2f);
			RenderSystem.setShaderColor(1f, 1f, 1f, r.opacity());
			RenderSystem.enableBlend();
			g.face(headPlayers.get(i).getSkinTextureLocation(), x, y, headPx);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	// ---- waypoints ----

	/** Every enabled waypoint in this dimension as the Waypoints mod's diamond, in its
	 *  own colour; ones beyond the edge sit ON the edge (dimmer) if `distant`. */
	static void waypoints(Gfx g, View v, boolean distant, float size) {
		Matrix4f m = g.pose().last().pose();
		String dim = Waypoints.currentDim();
		float r = size;
		BufferBuilder b = colorTris();
		boolean any = false;
		for (Waypoints.Waypoint wp : Waypoints.all()) {
			if (!wp.enabled || !dim.equals(wp.dimension)) {
				continue;
			}
			float sx = v.sx(wp.x + 0.5, wp.z + 0.5), sy = v.sy(wp.x + 0.5, wp.z + 0.5);
			int alpha = 255;
			if (!v.contains(sx, sy, r + 1)) {
				if (!distant) {
					continue;
				}
				float[] e = v.clampToEdge(sx, sy, r + 1);
				sx = e[0];
				sy = e[1];
				alpha = 170;
			}
			diamond(b, m, sx, sy, r + 1, alpha << 24);
			diamond(b, m, sx, sy, r, withAlpha(wp.color, alpha));
			any = true;
		}
		if (any) {
			drawColor(b);
		} else {
			discard(b);
		}
	}

	// ---- the player ----

	/** An arrowhead at the centre pointing where the player faces (screen angle). */
	static void playerArrow(Matrix4f m, float x, float y, float screenAngle, float size, int argb) {
		float len = 1.5f + size * 0.7f;
		BufferBuilder b = colorTris();
		arrow(b, m, x, y, screenAngle, len + 1.1f, (alpha(argb) * 3 / 4) << 24);   // dark outline
		arrow(b, m, x, y, screenAngle, len, argb);
		drawColor(b);
	}

	private static void arrow(BufferBuilder b, Matrix4f m, float x, float y, float ang, float len, int argb) {
		float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
		// local shape pointing up (-y): tip, back-left, notch, back-right
		float[][] p = {{0, -len}, {-len * 0.75f, len * 0.75f}, {0, len * 0.3f}, {len * 0.75f, len * 0.75f}};
		float[][] q = new float[4][2];
		for (int i = 0; i < 4; i++) {
			q[i][0] = x + p[i][0] * c - p[i][1] * s;
			q[i][1] = y + p[i][0] * s + p[i][1] * c;
		}
		tri(b, m, q[0][0], q[0][1], q[1][0], q[1][1], q[2][0], q[2][1], argb);
		tri(b, m, q[0][0], q[0][1], q[2][0], q[2][1], q[3][0], q[3][1], argb);
	}

	/** N / E / S / W just inside the edge, following the map's rotation. */
	static void compass(Gfx g, Minecraft mc, View v, int color, boolean shadow) {
		String[] letters = {"N", "E", "S", "W"};
		double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		for (int i = 0; i < 4; i++) {
			float dx = (float) dirs[i][0], dz = (float) dirs[i][1];
			float sx = dx * v.cos - dz * v.sin, sy = dx * v.sin + dz * v.cos;
			float[] e = v.clampToEdge(v.cx + sx * 10000f, v.cy + sy * 10000f, 6f);
			int w = mc.font.width(letters[i]);
			g.drawString(mc.font, letters[i], Math.round(e[0] - w / 2f), Math.round(e[1] - 4), color, shadow);
		}
	}

	// ---- geometry helpers (POSITION_COLOR triangles) ----

	private static BufferBuilder colorTris() {
		return Era.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
	}

	private static void tri(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, float x2, float y2, int argb) {
		vtx(b, m, x0, y0, argb);
		vtx(b, m, x1, y1, argb);
		vtx(b, m, x2, y2, argb);
	}

	private static void quad(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1,
							 float x2, float y2, float x3, float y3, int argb) {
		tri(b, m, x0, y0, x1, y1, x2, y2, argb);
		tri(b, m, x0, y0, x2, y2, x3, y3, argb);
	}

	private static void rect(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
		quad(b, m, x0, y0, x0, y1, x1, y1, x1, y0, argb);
	}

	private static void diamond(BufferBuilder b, Matrix4f m, float x, float y, float r, int argb) {
		quad(b, m, x, y - r, x - r, y, x, y + r, x + r, y, argb);
	}

	private static void vtx(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
		b.vertex(m, x, y, 0).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, alpha(argb)).endVertex();
	}

	private static void drawColor(BufferBuilder b) {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();   // winding-agnostic: fans and rotated quads face either way
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		Era.drawBuffer(b);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	/** Ends a builder that got no vertices (the uploader skips an empty buffer). */
	private static void discard(BufferBuilder b) {
		Era.drawBuffer(b);
	}

	static int alpha(int argb) {
		return (argb >>> 24) & 0xFF;
	}

	static int withAlpha(int argb, int a) {
		return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF);
	}
}
