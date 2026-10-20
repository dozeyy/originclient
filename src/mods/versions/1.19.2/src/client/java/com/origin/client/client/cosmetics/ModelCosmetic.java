package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import net.minecraft.client.renderer.LightTexture;
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
 * matrix per bone), so the outer-skin-layer fit ({@link Fit}) can bend the
 * item over the hat / jacket layer before it is drawn with the attach pose.
 *
 * Passes: LIT + EMISSIVE quads go into one entityCutoutNoCull buffer (emissive
 * ones at full-bright light); GLOW quads go into a second, additive
 * RenderType.eyes buffer. Asked for strictly one after the other - a buffer
 * source may close a render type's buffer when another is requested.
 *
 * Every failure is swallowed (after one log line per item): a cosmetic never
 * breaks a frame, and a model that threw once is switched off for the session.
 *
 * Pre-1.20 port: the per-bone chain and pointInAttachFrame are plain float
 * maths instead of JOML (1.19.2 / 1.18.2 have no JOML, and their
 * com.mojang.math.Matrix4f starts all zeros), and vertices use this era's
 * matrix-taking vertex(...).endVertex() chain in NEW_ENTITY order.
 */
public final class ModelCosmetic {
	private static final Map<String, ModelCosmetic> CACHE = new HashMap<>();
	/** Items that failed to compile or draw: never retried. */
	private static final Map<String, Boolean> DEAD = new HashMap<>();

	private final String id;
	private final ResourceLocation texture;
	private final ModelGeometry.Compiled geo;
	private final CosmeticDef.Anim anim;
	private final int[] motionBone;
	private final float[] off;   // per bone: rot x y z, pos x y z (scratch, render thread only)
	/** Per bone, bone frame -> attach frame as a row-major 3x4 affine (M_ROW floats; scratch, render thread only). */
	private final float[] boneM;
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
		this.boneM = new float[geo.names.length * M_ROW];
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

		/** Fits the point in p[0..2] in place. */
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
				chain(root, -1);
			}
			PoseStack.Pose attach = pose.last();
			int bones = geo.names.length;
			VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
			for (int b = 0; b < bones; b++) {
				emit(attach, b, fit, solid, geo.quads[b][ModelGeometry.LIT], light, vOff, invU, invV);
				emit(attach, b, fit, solid, geo.quads[b][ModelGeometry.EMISSIVE], LightTexture.FULL_BRIGHT, vOff, invU, invV);
			}
			if (geo.hasGlow) {
				VertexConsumer glow = buffers.getBuffer(com.origin.client.client.render.GlowRenderType.eyes(texture));
				for (int b = 0; b < bones; b++) {
					emit(attach, b, fit, glow, geo.quads[b][ModelGeometry.GLOW], LightTexture.FULL_BRIGHT, vOff, invU, invV);
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
	 * space), with the animation at (t, walk01, air01), written to out[0..2].
	 * Returns false if the item has no bone of that name (the caller then uses
	 * the attach frame itself).
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
			float[] pb = geo.pivot[b];
			float px = x + pb[0], py = y + pb[1], pz = z + pb[2];
			// The same chain drawBone builds, applied to the point from the bone up to the
			// root: per bone, about its pivot, Rz then Ry then Rx (R = Rx * Ry * Rz), then
			// its animated offset.
			for (int k = 0; k < depth; k++) {
				int i = pathScratch[k], o = i * 6;
				float[] pv = geo.pivot[i], r = geo.rot[i];
				float ax = px - pv[0], ay = py - pv[1], az = pz - pv[2];
				float a = (float) Math.toRadians(r[2] + off[o + 2]);
				float c = (float) Math.cos(a), sn = (float) Math.sin(a);
				float tx = ax * c - ay * sn;
				ay = ax * sn + ay * c;
				ax = tx;
				a = (float) Math.toRadians(r[1] + off[o + 1]);
				c = (float) Math.cos(a);
				sn = (float) Math.sin(a);
				tx = ax * c + az * sn;
				az = -ax * sn + az * c;
				ax = tx;
				a = (float) Math.toRadians(r[0] + off[o]);
				c = (float) Math.cos(a);
				sn = (float) Math.sin(a);
				float ty = ay * c - az * sn;
				az = ay * sn + az * c;
				ay = ty;
				px = ax + pv[0] + off[o + 3];
				py = ay + pv[1] + off[o + 4];
				pz = az + pv[2] + off[o + 5];
			}
			out[0] = px;
			out[1] = py;
			out[2] = pz;
			return true;
		} catch (Throwable t2) {
			return false;
		}
	}

	/** Floats per bone in boneM: rows r00 r01 r02 t0 / r10 r11 r12 t1 / r20 r21 r22 t2. */
	private static final int M_ROW = 12;
	private static final float[] P = new float[3], N = new float[3], R = new float[9], RT = new float[9];

	/**
	 * Fills boneM for `b` and its children: parent * offset * pivot * (Rx * Ry * Rz) * -pivot
	 * (FORMAT.md order, the same chain pointInAttachFrame walks). `parent` = the parent's bone
	 * index, or -1 for a root (identity).
	 */
	private void chain(int b, int parent) {
		float[] pv = geo.pivot[b], r = geo.rot[b];
		int o = b * 6;
		// local rotation L = Rx * Ry * Rz (right-handed, angles in degrees)
		rotation(r[0] + off[o], r[1] + off[o + 1], r[2] + off[o + 2], R);
		// local translation: offset + pivot - L * pivot
		float lx = off[o + 3] + pv[0] - (R[0] * pv[0] + R[1] * pv[1] + R[2] * pv[2]);
		float ly = off[o + 4] + pv[1] - (R[3] * pv[0] + R[4] * pv[1] + R[5] * pv[2]);
		float lz = off[o + 5] + pv[2] - (R[6] * pv[0] + R[7] * pv[1] + R[8] * pv[2]);
		int m = b * M_ROW;
		if (parent < 0) {
			for (int row = 0; row < 3; row++) {
				boneM[m + row * 4] = R[row * 3];
				boneM[m + row * 4 + 1] = R[row * 3 + 1];
				boneM[m + row * 4 + 2] = R[row * 3 + 2];
			}
			boneM[m + 3] = lx;
			boneM[m + 7] = ly;
			boneM[m + 11] = lz;
		} else {
			// M = Parent * L: rotation Pr * Lr, translation Pr * Lt + Pt
			int q = parent * M_ROW;
			for (int row = 0; row < 3; row++) {
				float p0 = boneM[q + row * 4], p1 = boneM[q + row * 4 + 1], p2 = boneM[q + row * 4 + 2];
				for (int col = 0; col < 3; col++) {
					boneM[m + row * 4 + col] = p0 * R[col] + p1 * R[3 + col] + p2 * R[6 + col];
				}
				boneM[m + row * 4 + 3] = p0 * lx + p1 * ly + p2 * lz + boneM[q + row * 4 + 3];
			}
		}
		for (int child : geo.children[b]) {
			chain(child, b);
		}
	}

	/** out (row-major 3x3) = Rx(ax) * Ry(ay) * Rz(az), degrees; Z is applied to a point first. */
	private static void rotation(float axDeg, float ayDeg, float azDeg, float[] out) {
		float ax = (float) Math.toRadians(axDeg), ay = (float) Math.toRadians(ayDeg), az = (float) Math.toRadians(azDeg);
		float cx = (float) Math.cos(ax), sx = (float) Math.sin(ax);
		float cy = (float) Math.cos(ay), sy = (float) Math.sin(ay);
		float cz = (float) Math.cos(az), sz = (float) Math.sin(az);
		// Ry * Rz first (RT), then Rx * RT
		RT[0] = cy * cz;  RT[1] = -cy * sz; RT[2] = sy;
		RT[3] = sz;       RT[4] = cz;       RT[5] = 0f;
		RT[6] = -sy * cz; RT[7] = sy * sz;  RT[8] = cy;
		out[0] = RT[0];
		out[1] = RT[1];
		out[2] = RT[2];
		out[3] = cx * RT[3] - sx * RT[6];
		out[4] = cx * RT[4] - sx * RT[7];
		out[5] = cx * RT[5] - sx * RT[8];
		out[6] = sx * RT[3] + cx * RT[6];
		out[7] = sx * RT[4] + cx * RT[7];
		out[8] = sx * RT[5] + cx * RT[8];
	}

	/** Emits `quads` of bone `b`: bone frame -> attach frame (boneM), the fit, then the attach pose. */
	private void emit(PoseStack.Pose attach, int b, Fit fit, VertexConsumer vc, float[] quads, int light,
			float vOff, float invU, float invV) {
		int m = b * M_ROW;
		float[] bm = boneM;
		for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
			// normals take the rotation only (the chain is rotations + translations, so no rescale)
			float qx = quads[i + 20], qy = quads[i + 21], qz = quads[i + 22];
			N[0] = bm[m] * qx + bm[m + 1] * qy + bm[m + 2] * qz;
			N[1] = bm[m + 4] * qx + bm[m + 5] * qy + bm[m + 6] * qz;
			N[2] = bm[m + 8] * qx + bm[m + 9] * qy + bm[m + 10] * qz;
			for (int v = 0; v < 4; v++) {
				int k = i + v * 5;
				float x = quads[k], y = quads[k + 1], z = quads[k + 2];
				P[0] = bm[m] * x + bm[m + 1] * y + bm[m + 2] * z + bm[m + 3];
				P[1] = bm[m + 4] * x + bm[m + 5] * y + bm[m + 6] * z + bm[m + 7];
				P[2] = bm[m + 8] * x + bm[m + 9] * y + bm[m + 10] * z + bm[m + 11];
				if (fit != null) {
					fit.apply(P);
				}
				vc.vertex(attach.pose(), P[0], P[1], P[2])
						.color(255, 255, 255, 255)
						.uv(quads[k + 3] * invU, (quads[k + 4] + vOff) * invV)
						.overlayCoords(OverlayTexture.NO_OVERLAY)
						.uv2(light)
						.normal(attach.normal(), N[0], N[1], N[2])
						.endVertex();
			}
		}
	}
}
