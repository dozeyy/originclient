package com.origin.client.client.minimap;

import com.origin.client.client.render.GuiTris;
import com.origin.client.client.waypoints.Waypoints;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one map view -- terrain, background, border, entity radar, waypoints, the
 * player arrow and compass letters -- for both the HUD minimap and the full-screen
 * map. Everything is immediate-mode geometry (like the Cooldowns dials), so a round
 * map is simply a textured triangle fan: no stencil, no scissor, and rotation is free
 * because only the texture coordinates turn.
 *
 * 1.21.11: the GUI is deferred (GuiGraphicsExtractor records render states; GuiRenderer
 * draws them later), so 1.21.1's immediate Tesselator/BufferUploader draws are
 * GuiTris batches submitted into the frame instead -- coordinates stay in local
 * GUI units and the pose current at submit() places them (the role 1.21.1's
 * Matrix4f argument played). Terrain samples the minimap texture through
 * vanilla's GUI_TEXTURED pipeline with the texture's own REPEAT + NEAREST
 * sampler; the colour shapes use the plain GUI pipeline. Player heads take their
 * opacity as the face renderer's tint (RenderSystem.setShaderColor is gone).
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

	static void background(GuiGraphicsExtractor g, View v, int argb) {
		if (alpha(argb) == 0) {
			return;
		}
		GuiTris b = GuiTris.color();
		shape(b, v, 0, argb);
		b.submit(g);
	}

	/** The terrain texture sampled under the view's rotation (REPEAT-wrapped ring buffer). */
	static void terrain(GuiGraphicsExtractor g, View v) {
		GuiTris b = terrainTris();
		if (b == null) {
			return;
		}
		// Keep the texture coordinates small: shift the centre by whole multiples of
		// the texture size so float precision holds even 30 million blocks out.
		double baseX = Math.floorMod((long) Math.floor(v.wx), MinimapData.SIZE) + (v.wx - Math.floor(v.wx));
		double baseZ = Math.floorMod((long) Math.floor(v.wz), MinimapData.SIZE) + (v.wz - Math.floor(v.wz));
		if (v.circle) {
			for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
				double a0 = Math.PI * 2 * i / CIRCLE_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
				texTri(b, v, baseX, baseZ, v.cx, v.cy,
						v.cx + (float) Math.cos(a0) * v.halfW, v.cy + (float) Math.sin(a0) * v.halfW,
						v.cx + (float) Math.cos(a1) * v.halfW, v.cy + (float) Math.sin(a1) * v.halfW);
			}
		} else {
			float x0 = v.cx - v.halfW, x1 = v.cx + v.halfW, y0 = v.cy - v.halfH, y1 = v.cy + v.halfH;
			texTri(b, v, baseX, baseZ, x0, y0, x0, y1, x1, y1);
			texTri(b, v, baseX, baseZ, x0, y0, x1, y1, x1, y0);
		}
		b.submit(g);
	}

	/** An empty textured batch on the terrain texture, or null when it isn't there. */
	private static GuiTris terrainTris() {
		if (!MinimapData.ready()) {
			return null;
		}
		try {
			AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(MinimapData.TEXTURE);
			return GuiTris.textured(RenderPipelines.GUI_TEXTURED, tex.getTextureView(), tex.getSampler());
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Terrain over one world-space rectangle only. The full-screen map uses this to
	 * clip to the scanned window: outside it, a ring-buffer slot may still hold some
	 * other place's pixels.
	 */
	static void terrainRect(GuiGraphicsExtractor g, View v, double x0, double z0, double x1, double z1) {
		if (x1 <= x0 || z1 <= z0) {
			return;
		}
		GuiTris b = terrainTris();
		if (b == null) {
			return;
		}
		double baseX = Math.floorMod((long) Math.floor(v.wx), MinimapData.SIZE) + (v.wx - Math.floor(v.wx));
		double baseZ = Math.floorMod((long) Math.floor(v.wz), MinimapData.SIZE) + (v.wz - Math.floor(v.wz));
		double[][] c = {{x0, z0}, {x0, z1}, {x1, z1}, {x0, z0}, {x1, z1}, {x1, z0}};
		float[] q = new float[12];
		for (int i = 0; i < 6; i++) {
			double[] p = c[i];
			q[2 * i] = (float) ((baseX + p[0] - v.wx) / MinimapData.SIZE);
			q[2 * i + 1] = (float) ((baseZ + p[1] - v.wz) / MinimapData.SIZE);
		}
		for (int t = 0; t < 2; t++) {
			double[] a = c[3 * t], bb = c[3 * t + 1], cc = c[3 * t + 2];
			int k = 6 * t;
			b.tri(v.sx(a[0], a[1]), v.sy(a[0], a[1]), q[k], q[k + 1],
					v.sx(bb[0], bb[1]), v.sy(bb[0], bb[1]), q[k + 2], q[k + 3],
					v.sx(cc[0], cc[1]), v.sy(cc[0], cc[1]), q[k + 4], q[k + 5], 0xFFFFFFFF);
		}
		b.submit(g);
	}

	// One textured triangle of the map: each corner's UV is the world point under
	// it (the inverse view rotation), in ring-buffer texture space.
	private static void texTri(GuiTris b, View v, double baseX, double baseZ,
							   float x0, float y0, float x1, float y1, float x2, float y2) {
		b.tri(x0, y0, u(v, baseX, x0, y0), t(v, baseZ, x0, y0),
				x1, y1, u(v, baseX, x1, y1), t(v, baseZ, x1, y1),
				x2, y2, u(v, baseX, x2, y2), t(v, baseZ, x2, y2), 0xFFFFFFFF);
	}

	private static float u(View v, double baseX, float sx, float sy) {
		float dx = sx - v.cx, dy = sy - v.cy;
		return (float) ((baseX + (dx * v.cos + dy * v.sin) / v.ppb) / MinimapData.SIZE);
	}

	private static float t(View v, double baseZ, float sx, float sy) {
		float dx = sx - v.cx, dy = sy - v.cy;
		return (float) ((baseZ + (-dx * v.sin + dy * v.cos) / v.ppb) / MinimapData.SIZE);
	}

	/** Border drawn INSIDE the map edge, so the element's box is exactly the map. */
	static void border(GuiGraphicsExtractor g, View v, float thickness, int argb) {
		if (alpha(argb) == 0 || thickness <= 0) {
			return;
		}
		GuiTris b = GuiTris.color();
		if (v.circle) {
			float ro = v.halfW, ri = Math.max(0, v.halfW - thickness);
			for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
				double a0 = Math.PI * 2 * i / CIRCLE_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
				float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
				b.quad(v.cx + c0 * ro, v.cy + s0 * ro, v.cx + c1 * ro, v.cy + s1 * ro,
						v.cx + c1 * ri, v.cy + s1 * ri, v.cx + c0 * ri, v.cy + s0 * ri, argb);
			}
		} else {
			float x0 = v.cx - v.halfW, x1 = v.cx + v.halfW, y0 = v.cy - v.halfH, y1 = v.cy + v.halfH, t = thickness;
			b.rect(x0, y0, x1, y0 + t, argb);                 // top + bottom span the full width;
			b.rect(x0, y1 - t, x1, y1, argb);
			b.rect(x0, y0 + t, x0 + t, y1 - t, argb);         // the sides fill only the gap, so the
			b.rect(x1 - t, y0 + t, x1, y1 - t, argb);         // corners are never drawn twice
		}
		b.submit(g);
	}

	/** Fills the map's shape (inset by `inset`) -- used for the background. */
	private static void shape(GuiTris b, View v, float inset, int argb) {
		if (v.circle) {
			float r = v.halfW - inset;
			for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
				double a0 = Math.PI * 2 * i / CIRCLE_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
				b.tri(v.cx, v.cy, v.cx + (float) Math.cos(a0) * r, v.cy + (float) Math.sin(a0) * r,
						v.cx + (float) Math.cos(a1) * r, v.cy + (float) Math.sin(a1) * r, argb);
			}
		} else {
			b.rect(v.cx - v.halfW + inset, v.cy - v.halfH + inset, v.cx + v.halfW - inset, v.cy + v.halfH - inset, argb);
		}
	}

	// ---- entity radar ----

	/** Which radar categories to draw, their colours, and marker styling. */
	record Radar(boolean players, boolean heads, boolean hostile, boolean passive, boolean items, boolean other,
				 int playerColor, int hostileColor, int passiveColor, int itemColor, int otherColor,
				 float size, float opacity, boolean shadow, int verticalRange) {
	}

	static void entities(GuiGraphicsExtractor g, Minecraft mc, View v, Radar r, float partial) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		double reach = v.radiusBlocks() + 2;
		double reachSq = reach * reach;
		float half = 0.75f + r.size() * 0.5f;
		int headPx = Math.round(6 + r.size() * 2);
		GuiTris b = GuiTris.color();
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
				b.rect(sx - half - 0.6f, sy - half - 0.6f, sx + half + 0.6f, sy + half + 0.6f, (a * 3 / 5) << 24);
			}
			b.rect(sx - half, sy - half, sx + half, sy + half, withAlpha(color, a));
			count++;
		}
		b.submit(g);   // no-op when nothing was added
		// Player heads: vanilla's own face renderer (face + hat layer), drawn after the
		// dots so a player is never hidden under a mob marker.
		if (heads.isEmpty()) {
			return;
		}
		if (r.shadow()) {
			int a = Math.round(255 * r.opacity());
			GuiTris sb = GuiTris.color();
			for (float[] p : heads) {
				float x = Math.round(p[0] - headPx / 2f), y = Math.round(p[1] - headPx / 2f);
				sb.rect(x - 1, y - 1, x + headPx + 1, y + headPx + 1, (a * 3 / 5) << 24);
			}
			sb.submit(g);
		}
		int tint = withAlpha(0xFFFFFF, Math.round(255 * r.opacity()));
		for (int i = 0; i < heads.size(); i++) {
			float[] p = heads.get(i);
			int x = Math.round(p[0] - headPx / 2f), y = Math.round(p[1] - headPx / 2f);
			PlayerFaceExtractor.extractRenderState(g, headPlayers.get(i).getSkin(), x, y, headPx, tint);
		}
	}

	// ---- waypoints ----

	/** Every enabled waypoint in this dimension as the Waypoints mod's diamond, in its
	 *  own colour; ones beyond the edge sit ON the edge (dimmer) if `distant`. */
	static void waypoints(GuiGraphicsExtractor g, View v, boolean distant, float size) {
		String dim = Waypoints.currentDim();
		float r = size;
		GuiTris b = GuiTris.color();
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
			diamond(b, sx, sy, r + 1, alpha << 24);
			diamond(b, sx, sy, r, withAlpha(wp.color, alpha));
		}
		b.submit(g);   // no-op when nothing was added
	}

	// ---- party (Friends & Party, LAN worlds only -- PartyWorld decides) ----

	/** Party members as round-ish dots in the party colour; beyond the edge they sit on it. */
	static void party(GuiGraphicsExtractor g, View v, float size) {
		java.util.List<double[]> pts;
		try {
			pts = com.origin.client.client.social.PartyWorld.mapPoints(net.minecraft.client.Minecraft.getInstance());
		} catch (Throwable t) {
			return;
		}
		if (pts.isEmpty()) {
			return;
		}
		int tint = com.origin.client.client.social.PartyWorld.color();
		GuiTris b = GuiTris.color();
		for (double[] p : pts) {
			float sx = v.sx(p[0], p[1]), sy = v.sy(p[0], p[1]);
			int alpha = 255;
			if (!v.contains(sx, sy, size + 1)) {
				float[] e = v.clampToEdge(sx, sy, size + 1);
				sx = e[0];
				sy = e[1];
				alpha = 170;
			}
			b.rect(sx - size - 1, sy - size - 1, sx + size + 1, sy + size + 1, alpha << 24);
			b.rect(sx - size, sy - size, sx + size, sy + size, withAlpha(tint, alpha));
		}
		b.submit(g);   // no-op when nothing was added
	}

	// ---- the player ----

	/** An arrowhead at the centre pointing where the player faces (screen angle). */
	static void playerArrow(GuiGraphicsExtractor g, float x, float y, float screenAngle, float size, int argb) {
		float len = 1.5f + size * 0.7f;
		GuiTris b = GuiTris.color();
		arrow(b, x, y, screenAngle, len + 1.1f, (alpha(argb) * 3 / 4) << 24);   // dark outline
		arrow(b, x, y, screenAngle, len, argb);
		b.submit(g);
	}

	private static void arrow(GuiTris b, float x, float y, float ang, float len, int argb) {
		float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
		// local shape pointing up (-y): tip, back-left, notch, back-right
		float[][] p = {{0, -len}, {-len * 0.75f, len * 0.75f}, {0, len * 0.3f}, {len * 0.75f, len * 0.75f}};
		float[][] q = new float[4][2];
		for (int i = 0; i < 4; i++) {
			q[i][0] = x + p[i][0] * c - p[i][1] * s;
			q[i][1] = y + p[i][0] * s + p[i][1] * c;
		}
		b.tri(q[0][0], q[0][1], q[1][0], q[1][1], q[2][0], q[2][1], argb);
		b.tri(q[0][0], q[0][1], q[2][0], q[2][1], q[3][0], q[3][1], argb);
	}

	/** N / E / S / W just inside the edge, following the map's rotation. */
	static void compass(GuiGraphicsExtractor g, Minecraft mc, View v, int color, boolean shadow) {
		String[] letters = {"N", "E", "S", "W"};
		double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		for (int i = 0; i < 4; i++) {
			float dx = (float) dirs[i][0], dz = (float) dirs[i][1];
			float sx = dx * v.cos - dz * v.sin, sy = dx * v.sin + dz * v.cos;
			float[] e = v.clampToEdge(v.cx + sx * 10000f, v.cy + sy * 10000f, 6f);
			int w = mc.font.width(letters[i]);
			g.text(mc.font, letters[i], Math.round(e[0] - w / 2f), Math.round(e[1] - 4), color, shadow);
		}
	}

	// ---- geometry helpers ----

	private static void diamond(GuiTris b, float x, float y, float r, int argb) {
		b.quad(x, y - r, x - r, y, x, y + r, x + r, y, argb);
	}

	static int alpha(int argb) {
		return (argb >>> 24) & 0xFF;
	}

	static int withAlpha(int argb, int a) {
		return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF);
	}
}
