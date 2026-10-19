package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
 * Passes: LIT + EMISSIVE quads go into one entityCutoutNoCull node (emissive
 * ones at full-bright light); GLOW quads go into a second, additive
 * RenderTypes.eyes node.
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era): layers no longer get a
 * buffer source. Each pass is handed to the frame's SubmitNodeCollector as one
 * custom-geometry node (submitCustomGeometry(PoseStack, RenderType,
 * (pose, consumer) -> ...), the shape the old head-only renderer used), which the
 * feature renderer replays later with the pose captured at submit time. The
 * replay re-runs the animation for the same clock and emits through that
 * pose, so the mesh code below is 1.21.1's unchanged. Identifier was
 * ResourceLocation; render types live in rendertype.RenderTypes.
 *
 * Every failure is swallowed (after one log line per item): a cosmetic never
 * breaks a frame, and a model that threw once is switched off for the session.
 */
public final class ModelCosmetic {
	private static final Map<String, ModelCosmetic> CACHE = new HashMap<>();
	/** Items that failed to compile or draw: never retried. */
	private static final Map<String, Boolean> DEAD = new HashMap<>();

	private final String id;
	private final Identifier texture;
	private final ModelGeometry.Compiled geo;
	private final CosmeticDef.Anim anim;
	private final int[] motionBone;
	private final float[] off;   // per bone: rot x y z, pos x y z (scratch, render thread only)
	private final Matrix4f[] boneM;   // per bone: bone frame -> attach frame (scratch, render thread only)
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
		this.boneM = new Matrix4f[geo.names.length];
		for (int i = 0; i < boneM.length; i++) {
			boneM[i] = new Matrix4f();
		}
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

		void apply(Vector3f p) {
			p.set(axis(p.x, cx, hx, g), axis(p.y, cy, hy, g), axis(p.z, cz, hz, g));
		}
	}

	/** Draws the model without the outer-layer fit. */
	public void render(PoseStack pose, SubmitNodeCollector collector, int light, float t, float walk01, float air01) {
		render(pose, collector, light, t, walk01, air01, null);
	}

	/**
	 * Draws the model.
	 *
	 * @param t        seconds on the animation clock (game: ticks / 20 + partial)
	 * @param walk01   horizontal speed / sprint speed, 0..1
	 * @param air01    0 on the ground, easing to 1 in the air
	 * @param fit      the outer-layer fit, or null when the player hides that layer
	 */
	public void render(PoseStack pose, SubmitNodeCollector collector, int light, float t, float walk01, float air01,
			Fit fit) {
		if (failed) {
			return;
		}
		try {
			int frame = frames > 1 ? Math.floorMod((int) Math.floor(t * fps), frames) : 0;
			float vOff = frame * geo.texH;
			float invU = 1f / geo.texW, invV = 1f / (geo.texH * (float) frames);

			collector.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(texture),
					(p, vc) -> replay(p, vc, ModelGeometry.LIT, ModelGeometry.EMISSIVE, light, t, walk01, air01, fit, vOff, invU, invV));
			if (geo.hasGlow) {
				collector.submitCustomGeometry(pose, RenderTypes.eyes(texture),
						(p, vc) -> replay(p, vc, ModelGeometry.GLOW, ModelGeometry.GLOW, light, t, walk01, air01, fit, vOff, invU, invV));
			}
		} catch (Throwable t2) {
			failed = true;
			OriginClient.LOGGER.warn("Origin cosmetics: {} failed to draw; switching it off for this session", id, t2);
		}
	}

	/**
	 * One pass, replayed by the feature renderer on the pose captured at submit
	 * time. The animation and bone chain run again here (not at submit) because
	 * `off` and `boneM` are shared scratch: another player's submit of the same
	 * item may have overwritten them in between.
	 */
	private void replay(PoseStack.Pose p, VertexConsumer vc, int kindA, int kindB, int light, float t,
			float walk01, float air01, Fit fit, float vOff, float invU, float invV) {
		if (failed) {
			return;
		}
		try {
			motion(t, walk01, air01);
			for (int root : geo.roots) {
				chain(root, IDENTITY);
			}
			for (int b = 0; b < boneM.length; b++) {
				emit(p, boneM[b], fit, vc, geo.quads[b][kindA], kindA == ModelGeometry.GLOW ? LightTexture.FULL_BRIGHT : light,
						vOff, invU, invV);
				if (kindB != kindA) {
					emit(p, boneM[b], fit, vc, geo.quads[b][kindB], LightTexture.FULL_BRIGHT, vOff, invU, invV);
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

	private static final Matrix4f CHAIN = new Matrix4f();
	private int[] pathScratch;

	/**
	 * Where a point given in bone `bone`'s own frame (FORMAT.md fx: px relative to
	 * that bone's pivot) is, in the ATTACH frame (the head or body pivot, FORMAT
	 * space), with the animation at (t, walk01, air01). Returns false if the item
	 * has no bone of that name (the caller then uses the attach frame itself).
	 */
	public boolean pointInAttachFrame(String bone, float x, float y, float z, float t, float walk01, float air01,
			Vector3f out) {
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
			Matrix4f m = CHAIN.identity();
			for (int k = depth - 1; k >= 0; k--) {
				int i = pathScratch[k], o = i * 6;
				float[] pv = geo.pivot[i], r = geo.rot[i];
				m.translate(off[o + 3], off[o + 4], off[o + 5]).translate(pv[0], pv[1], pv[2])
						.rotateX((float) Math.toRadians(r[0] + off[o]))
						.rotateY((float) Math.toRadians(r[1] + off[o + 1]))
						.rotateZ((float) Math.toRadians(r[2] + off[o + 2]))
						.translate(-pv[0], -pv[1], -pv[2]);
			}
			float[] pb = geo.pivot[b];
			out.set(x + pb[0], y + pb[1], z + pb[2]);
			m.transformPosition(out);
			return true;
		} catch (Throwable t2) {
			return false;
		}
	}

	/** One quad for {@link #collect}: 4 corners (x, y, z after the matrix), their UVs (0..1), the turned normal, its kind. */
	interface QuadSink {
		void quad(float[] xyz, float[] uv, float nx, float ny, float nz, int kind);
	}

	/**
	 * Hands every quad of the model, posed at clock `t` (standing still), to
	 * `sink` with its corners transformed by `m` (from FORMAT space). For the
	 * Cosmetics screen's thumbnails (CosmeticThumbs), which project the model
	 * themselves. False if the model is broken.
	 */
	boolean collect(Matrix4f m, float t, QuadSink sink) {
		if (failed) {
			return false;
		}
		try {
			motion(t, 0f, 0f);
			int frame = frames > 1 ? Math.floorMod((int) Math.floor(t * fps), frames) : 0;
			float vOff = frame * geo.texH;
			float invU = 1f / geo.texW, invV = 1f / (geo.texH * (float) frames);
			float[] xyz = new float[12], uv = new float[8];
			Vector3f n = new Vector3f(), c = new Vector3f();
			for (int root : geo.roots) {
				collectBone(new Matrix4f(m), root, sink, vOff, invU, invV, xyz, uv, n, c);
			}
			return true;
		} catch (Throwable t2) {
			return false;
		}
	}

	private void collectBone(Matrix4f m, int b, QuadSink sink, float vOff, float invU, float invV,
			float[] xyz, float[] uv, Vector3f n, Vector3f c) {
		float[] pv = geo.pivot[b], r = geo.rot[b];
		int o = b * 6;
		// chain's transforms, on a matrix
		m.translate(off[o + 3], off[o + 4], off[o + 5]).translate(pv[0], pv[1], pv[2])
				.rotateX((float) Math.toRadians(r[0] + off[o]))
				.rotateY((float) Math.toRadians(r[1] + off[o + 1]))
				.rotateZ((float) Math.toRadians(r[2] + off[o + 2]))
				.translate(-pv[0], -pv[1], -pv[2]);
		for (int kind = 0; kind < geo.quads[b].length; kind++) {
			float[] quads = geo.quads[b][kind];
			for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
				for (int v = 0; v < 4; v++) {
					int k = i + v * 5;
					m.transformPosition(c.set(quads[k], quads[k + 1], quads[k + 2]));
					xyz[v * 3] = c.x;
					xyz[v * 3 + 1] = c.y;
					xyz[v * 3 + 2] = c.z;
					uv[v * 2] = quads[k + 3] * invU;
					uv[v * 2 + 1] = (quads[k + 4] + vOff) * invV;
				}
				m.transformDirection(n.set(quads[i + 20], quads[i + 21], quads[i + 22]));
				if (n.lengthSquared() > 0f) {
					n.normalize();
				}
				sink.quad(xyz, uv, n.x, n.y, n.z, kind);
			}
		}
		for (int child : geo.children[b]) {
			collectBone(new Matrix4f(m), child, sink, vOff, invU, invV, xyz, uv, n, c);
		}
	}

	private static final Matrix4f IDENTITY = new Matrix4f();
	private static final Vector3f P = new Vector3f(), N = new Vector3f();

	/** Fills boneM for `b` and its children: parent * offset * pivot * (Rx * Ry * Rz) * -pivot (FORMAT.md order). */
	private void chain(int b, Matrix4f parent) {
		float[] pv = geo.pivot[b], r = geo.rot[b];
		int o = b * 6;
		boneM[b].set(parent)
				.translate(off[o + 3], off[o + 4], off[o + 5])
				.translate(pv[0], pv[1], pv[2])
				.rotateX((float) Math.toRadians(r[0] + off[o]))
				.rotateY((float) Math.toRadians(r[1] + off[o + 1]))
				.rotateZ((float) Math.toRadians(r[2] + off[o + 2]))
				.translate(-pv[0], -pv[1], -pv[2]);
		for (int child : geo.children[b]) {
			chain(child, boneM[b]);
		}
	}

	/** Emits `quads` of one bone: bone frame -> attach frame (`m`), the fit, then the attach pose. */
	private static void emit(PoseStack.Pose attach, Matrix4f m, Fit fit, VertexConsumer vc, float[] quads, int light,
			float vOff, float invU, float invV) {
		for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
			m.transformDirection(N.set(quads[i + 20], quads[i + 21], quads[i + 22]));
			for (int v = 0; v < 4; v++) {
				int k = i + v * 5;
				m.transformPosition(P.set(quads[k], quads[k + 1], quads[k + 2]));
				if (fit != null) {
					fit.apply(P);
				}
				vc.addVertex(attach, P.x, P.y, P.z)
						.setColor(-1)
						.setUv(quads[k + 3] * invU, (quads[k + 4] + vOff) * invV)
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(light)
						.setNormal(attach, N.x, N.y, N.z);
			}
		}
	}
}
