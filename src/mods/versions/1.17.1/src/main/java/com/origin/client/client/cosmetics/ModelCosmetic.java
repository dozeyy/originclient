package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic renderer for every model cosmetic (wings, backpacks, hats,
 * halos, crowns, masks, bandanas): one compiled FORMAT.md model, drawn on the
 * bone the caller already positioned. No item is named here.
 *
 * The caller (CosmeticLayer, MenuPlayerPreview) leaves the pose on the
 * attach bone with `scale(1/16, -1/16, -1/16)` applied, which turns FORMAT
 * space (model px, y up, +z front, +x the player's left) into vanilla's
 * (blocks, y down, -z front). Everything below is in FORMAT space.
 *
 * Every vertex is placed on the CPU in the attach frame (the bone chain as a
 * 3x4 matrix per bone, plain float math: 1.17.1 has no JOML), so the
 * outer-skin-layer fit ({@link Fit}) can bend the item over the hat / jacket
 * layer before it is drawn with the attach pose.
 *
 * Passes: LIT + EMISSIVE quads go into one entityCutoutNoCull buffer (emissive
 * ones at full-bright light); GLOW quads go into a second, additive
 * RenderType.eyes buffer. Asked for strictly one after the other - a buffer
 * source may close a render type's buffer when another is requested.
 *
 * Every failure is swallowed (after one log line per item): a cosmetic never
 * breaks a frame, and a model that threw once is switched off for the session.
 */
public final class ModelCosmetic {
	// Packed full-bright light (FULL_BRIGHT is 1.18+).
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final Map<String, ModelCosmetic> CACHE = new HashMap<>();
	/** Items that failed to compile or draw: never retried. */
	private static final Map<String, Boolean> DEAD = new HashMap<>();

	private final String id;
	private final ResourceLocation texture;
	private final ModelGeometry.Compiled geo;
	private final CosmeticDef.Anim anim;
	private final int[] motionBone;
	private final float[] off;   // per bone: rot x y z, pos x y z (scratch, render thread only)
	private final float[] boneM;   // per bone: bone frame -> attach frame, 12 floats row-major 3x4 (scratch, render thread only)
	private final int frames;
	private final float fps;
	private boolean failed = false;

	private ModelCosmetic(CosmeticDef def) {
		this.id = def.meta.id();
		this.texture = CosmeticCatalog.texture(id);
		this.geo = ModelGeometry.compile(def.model);
		this.anim = def.anim;
		this.motionBone = new int[anim.motion().size()];
		for (int i = 0; i < motionBone.length; i++) {
			motionBone[i] = geo.boneIndex(anim.motion().get(i).bone());
		}
		this.off = new float[geo.names.length * 6];
		this.boneM = new float[geo.names.length * 12];
		this.frames = Math.max(1, anim.frames());
		this.fps = anim.fps();
	}

	/** The renderer for item `id` (compiled once), or null if it has no model or is broken. Render thread. */
	public static ModelCosmetic of(String id) {
		if (id == null || DEAD.containsKey(id)) {
			return null;
		}
		ModelCosmetic m = CACHE.get(id);
		if (m != null) {
			return m.failed ? null : m;
		}
		try {
			CosmeticDef def = CosmeticCatalog.def(id);
			if (def == null || def.model == null) {
				DEAD.put(id, Boolean.TRUE);
				return null;
			}
			m = new ModelCosmetic(def);
			CACHE.put(id, m);
			return m;
		} catch (Throwable t) {
			DEAD.put(id, Boolean.TRUE);
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't build the model of {}", id, t);
			return null;
		}
	}

	/** Forgets every compiled model (resource reload). */
	public static void clear() {
		CACHE.clear();
		DEAD.clear();
	}

	/**
	 * The outer-skin-layer fit (FORMAT.md "Outer skin layer"): when the player
	 * shows the hat (head + face items) or jacket (back items) layer, the item
	 * is bent outward over it instead of clipping into it. Per axis, around the
	 * bone's skin box (centre c, half size h), u = p - c becomes u + sign(u) * g
	 * on or outside the skin and u * (h + g) / h inside it: continuous, so
	 * wraparound items (glasses, bands) wrap the layer and animated parts never
	 * jump. Applied in the attach frame after the item's own animation, before
	 * the player's position tweak. The website preview (rig.js) uses the same rule.
	 */
	public record Fit(float cx, float cy, float cz, float hx, float hy, float hz, float g) {
		/** Head bone: head cube x -4..4, y 0..8, z -4..4; vanilla's hat layer is 0.5 px out. */
		public static final Fit HEAD = new Fit(0f, 4f, 0f, 4f, 4f, 4f, 0.5f);
		/** Body bone: torso x -4..4, y -12..0, z -2..2; vanilla's jacket layer is 0.25 px out. */
		public static final Fit BODY = new Fit(0f, -6f, 0f, 4f, 6f, 2f, 0.25f);

		static float axis(float p, float c, float h, float g) {
			float u = p - c;
			float a = Math.abs(u);
			return c + (a >= h ? u + Math.signum(u) * g : u * (h + g) / h);
		}

		/** `p` = x, y, z in the attach frame, moved in place. */
		void apply(float[] p) {
			p[0] = axis(p[0], cx, hx, g);
			p[1] = axis(p[1], cy, hy, g);
			p[2] = axis(p[2], cz, hz, g);
		}
	}

	/** Draws the model without the outer-layer fit. */
	public void render(PoseStack pose, MultiBufferSource buffers, int light, float t, float walk01, float air01) {
		render(pose, buffers, light, t, walk01, air01, null);
	}

	/**
	 * Draws the model.
	 *
	 * @param t        seconds on the animation clock (game: ticks / 20 + partial)
	 * @param walk01   horizontal speed / sprint speed, 0..1
	 * @param air01    0 on the ground, easing to 1 in the air
	 * @param fit      the outer-layer fit, or null when the player hides that layer
	 */
	public void render(PoseStack pose, MultiBufferSource buffers, int light, float t, float walk01, float air01,
			Fit fit) {
		if (failed) {
			return;
		}
		try {
			motion(t, walk01, air01);
			int frame = frames > 1 ? Math.floorMod((int) Math.floor(t * fps), frames) : 0;
			float vOff = frame * geo.texH;
			float invU = 1f / geo.texW, invV = 1f / (geo.texH * (float) frames);

			for (int root : geo.roots) {
				chain(root, IDENTITY, 0);
			}
			PoseStack.Pose attach = pose.last();
			int bones = geo.names.length;
			VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
			for (int b = 0; b < bones; b++) {
				emit(attach, boneM, b * 12, fit, solid, geo.quads[b][ModelGeometry.LIT], light, vOff, invU, invV);
				emit(attach, boneM, b * 12, fit, solid, geo.quads[b][ModelGeometry.EMISSIVE], FULL_BRIGHT, vOff, invU, invV);
			}
			if (geo.hasGlow) {
				VertexConsumer glow = buffers.getBuffer(com.origin.client.client.render.GlowRenderType.eyes(texture));
				for (int b = 0; b < bones; b++) {
					emit(attach, boneM, b * 12, fit, glow, geo.quads[b][ModelGeometry.GLOW], FULL_BRIGHT, vOff, invU, invV);
				}
			}
		} catch (Throwable t2) {
			failed = true;
			OriginClient.LOGGER.warn("Origin cosmetics: {} failed to draw; switching it off for this session", id, t2);
		}
	}

	/**
	 * FORMAT.md anim.motion: each entry adds sin(2 pi (t / period + phase)) * A to one bone channel, or, for a
	 * "spin" entry, a continuous rotation of speed deg/s (the angle is wrapped to 0..360 in double precision, so
	 * it stays exact however long the session runs).
	 */
	private void motion(float t, float walk01, float air01) {
		java.util.Arrays.fill(off, 0f);
		List<CosmeticDef.Motion> list = anim.motion();
		for (int i = 0; i < motionBone.length; i++) {
			int b = motionBone[i];
			if (b < 0) {
				continue;
			}
			CosmeticDef.Motion m = list.get(i);
			if (m.spin()) {
				off[b * 6 + m.axis()] += (float) ((m.speed() * (double) t) % 360.0);
				continue;
			}
			float a = m.amp() + m.walkAmp() * walk01 + m.airAmp() * air01;
			float v = (float) Math.sin(2.0 * Math.PI * (t / m.period() + m.phase())) * a;
			off[b * 6 + (m.pos() ? 3 : 0) + m.axis()] += v;
		}
	}

	private int[] pathScratch;

	/**
	 * Where a point given in bone `bone`'s own frame (FORMAT.md fx: px relative to
	 * that bone's pivot) is, in the ATTACH frame (the head or body pivot, FORMAT
	 * space), with the animation at (t, walk01, air01). Returns false if the item
	 * has no bone of that name (the caller then uses the attach frame itself).
	 * `out` receives x, y, z (1.17.1 has no JOML; plain float math instead).
	 */
	public boolean pointInAttachFrame(String bone, float x, float y, float z, float t, float walk01, float air01,
			float[] out) {
		if (failed || bone == null) {
			return false;
		}
		int b = geo.boneIndex(bone);
		if (b < 0) {
			return false;
		}
		try {
			motion(t, walk01, air01);
			if (pathScratch == null) {
				pathScratch = new int[geo.names.length];
			}
			int depth = 0;
			for (int i = b; i >= 0 && depth < pathScratch.length; i = geo.parent[i]) {
				pathScratch[depth++] = i;
			}
			// The chain is M = M_root * ... * M_bone with M_i = T(offPos) T(pivot) Rx Ry Rz T(-pivot),
			// so the point goes through the bone's own transform first, then each parent's.
			float[] pb = geo.pivot[b];
			float px = x + pb[0], py = y + pb[1], pz = z + pb[2];
			for (int k = 0; k < depth; k++) {
				int i = pathScratch[k], o = i * 6;
				float[] pv = geo.pivot[i], r = geo.rot[i];
				float vx = px - pv[0], vy = py - pv[1], vz = pz - pv[2];
				// Rz, then Ry, then Rx (the vector is multiplied from the right)
				double az = Math.toRadians(r[2] + off[o + 2]), ay = Math.toRadians(r[1] + off[o + 1]),
						ax = Math.toRadians(r[0] + off[o]);
				float c = (float) Math.cos(az), sn = (float) Math.sin(az);
				float tx = vx * c - vy * sn, ty = vx * sn + vy * c;
				vx = tx;
				vy = ty;
				c = (float) Math.cos(ay);
				sn = (float) Math.sin(ay);
				tx = vx * c + vz * sn;
				float tz = -vx * sn + vz * c;
				vx = tx;
				vz = tz;
				c = (float) Math.cos(ax);
				sn = (float) Math.sin(ax);
				ty = vy * c - vz * sn;
				tz = vy * sn + vz * c;
				vy = ty;
				vz = tz;
				px = vx + pv[0] + off[o + 3];
				py = vy + pv[1] + off[o + 4];
				pz = vz + pv[2] + off[o + 5];
			}
			out[0] = px;
			out[1] = py;
			out[2] = pz;
			return true;
		} catch (Throwable t2) {
			return false;
		}
	}

	private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
	private static final float[] P = new float[3];

	/**
	 * Fills boneM for `b` and its children: parent * T(off) * T(pivot) * (Rx * Ry * Rz) * T(-pivot)
	 * (FORMAT.md order; the same per-axis rotations as pointInAttachFrame). Matrices are row-major 3x4
	 * (rotation in columns 0..2, translation in column 3); `parent` starts at `po`.
	 */
	private void chain(int b, float[] parent, int po) {
		float[] pv = geo.pivot[b], r = geo.rot[b];
		int o = b * 6;
		double ax = Math.toRadians(r[0] + off[o]), ay = Math.toRadians(r[1] + off[o + 1]),
				az = Math.toRadians(r[2] + off[o + 2]);
		float cx = (float) Math.cos(ax), sx = (float) Math.sin(ax);
		float cy = (float) Math.cos(ay), sy = (float) Math.sin(ay);
		float cz = (float) Math.cos(az), sz = (float) Math.sin(az);
		// Ry * Rz, then Rx on the left: rows of R = Rx * Ry * Rz
		float a00 = cy * cz, a01 = -cy * sz, a02 = sy;
		float a10 = sz, a11 = cz, a12 = 0f;
		float a20 = -sy * cz, a21 = sy * sz, a22 = cy;
		float r00 = a00, r01 = a01, r02 = a02;
		float r10 = cx * a10 - sx * a20, r11 = cx * a11 - sx * a21, r12 = cx * a12 - sx * a22;
		float r20 = sx * a10 + cx * a20, r21 = sx * a11 + cx * a21, r22 = sx * a12 + cx * a22;
		// local translation: off + pivot - R * pivot
		float t0 = off[o + 3] + pv[0] - (r00 * pv[0] + r01 * pv[1] + r02 * pv[2]);
		float t1 = off[o + 4] + pv[1] - (r10 * pv[0] + r11 * pv[1] + r12 * pv[2]);
		float t2 = off[o + 5] + pv[2] - (r20 * pv[0] + r21 * pv[1] + r22 * pv[2]);
		float[] m = boneM;
		int k = b * 12;
		for (int row = 0; row < 3; row++) {
			float p0 = parent[po + row * 4], p1 = parent[po + row * 4 + 1], p2 = parent[po + row * 4 + 2],
					p3 = parent[po + row * 4 + 3];
			m[k + row * 4] = p0 * r00 + p1 * r10 + p2 * r20;
			m[k + row * 4 + 1] = p0 * r01 + p1 * r11 + p2 * r21;
			m[k + row * 4 + 2] = p0 * r02 + p1 * r12 + p2 * r22;
			m[k + row * 4 + 3] = p0 * t0 + p1 * t1 + p2 * t2 + p3;
		}
		for (int child : geo.children[b]) {
			chain(child, m, k);
		}
	}

	/** Emits `quads` of one bone: bone frame -> attach frame (`m` at `mo`), the fit, then the attach pose. */
	private static void emit(PoseStack.Pose attach, float[] m, int mo, Fit fit, VertexConsumer vc, float[] quads,
			int light, float vOff, float invU, float invV) {
		for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
			float qx = quads[i + 20], qy = quads[i + 21], qz = quads[i + 22];
			// the normal turns with the bone (rotation part only); it stays unit length
			float nx = m[mo] * qx + m[mo + 1] * qy + m[mo + 2] * qz;
			float ny = m[mo + 4] * qx + m[mo + 5] * qy + m[mo + 6] * qz;
			float nz = m[mo + 8] * qx + m[mo + 9] * qy + m[mo + 10] * qz;
			for (int v = 0; v < 4; v++) {
				int k = i + v * 5;
				float x = quads[k], y = quads[k + 1], z = quads[k + 2];
				P[0] = m[mo] * x + m[mo + 1] * y + m[mo + 2] * z + m[mo + 3];
				P[1] = m[mo + 4] * x + m[mo + 5] * y + m[mo + 6] * z + m[mo + 7];
				P[2] = m[mo + 8] * x + m[mo + 9] * y + m[mo + 10] * z + m[mo + 11];
				if (fit != null) {
					fit.apply(P);
				}
				// 1.17.1 VertexConsumer: the chained builder in the entity format's order
				// (position, colour, uv, overlay, light, normal) closed by endVertex().
				vc.vertex(attach.pose(), P[0], P[1], P[2])
						.color(255, 255, 255, 255)
						.uv(quads[k + 3] * invU, (quads[k + 4] + vOff) * invV)
						.overlayCoords(OverlayTexture.NO_OVERLAY)
						.uv2(light)
						.normal(attach.normal(), nx, ny, nz)
						.endVertex();
			}
		}
	}
}
