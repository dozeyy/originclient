package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.Era;
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
 * Passes: LIT + EMISSIVE quads go into one entityCutoutNoCull buffer (emissive
 * ones at full-bright light); GLOW quads go into a second, additive
 * RenderType.eyes buffer. Asked for strictly one after the other - a buffer
 * source may close a render type's buffer when another is requested.
 *
 * Every failure is swallowed (after one log line per item): a cosmetic never
 * breaks a frame, and a model that threw once is switched off for the session.
 *
 * Pre-1.20 port: bone rotations go through Era.rotX/rotY/rotZ (this module's
 * one rotation shim), pointInAttachFrame is plain float maths instead of a
 * JOML chain, and vertices use this era's matrix-taking vertex(...)
 * .endVertex() chain in NEW_ENTITY order.
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
	 * Draws the model.
	 *
	 * @param t        seconds on the animation clock (game: ticks / 20 + partial)
	 * @param walk01   horizontal speed / sprint speed, 0..1
	 * @param air01    0 on the ground, easing to 1 in the air
	 */
	public void render(PoseStack pose, MultiBufferSource buffers, int light, float t, float walk01, float air01) {
		if (failed) {
			return;
		}
		try {
			motion(t, walk01, air01);
			int frame = frames > 1 ? Math.floorMod((int) Math.floor(t * fps), frames) : 0;
			float vOff = frame * geo.texH;
			float invU = 1f / geo.texW, invV = 1f / (geo.texH * (float) frames);

			VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
			for (int root : geo.roots) {
				drawBone(pose, solid, root, ModelGeometry.LIT, ModelGeometry.EMISSIVE, light, vOff, invU, invV);
			}
			if (geo.hasGlow) {
				VertexConsumer glow = buffers.getBuffer(RenderType.eyes(texture));
				for (int root : geo.roots) {
					drawBone(pose, glow, root, ModelGeometry.GLOW, ModelGeometry.GLOW, light, vOff, invU, invV);
				}
			}
		} catch (Throwable t2) {
			failed = true;
			OriginClient.LOGGER.warn("Origin cosmetics: {} failed to draw; switching it off for this session", id, t2);
		}
	}

	/** FORMAT.md anim.motion: each entry adds sin(2 pi (t / period + phase)) * A to one bone channel. */
	private void motion(float t, float walk01, float air01) {
		java.util.Arrays.fill(off, 0f);
		List<CosmeticDef.Motion> list = anim.motion();
		for (int i = 0; i < motionBone.length; i++) {
			int b = motionBone[i];
			if (b < 0) {
				continue;
			}
			CosmeticDef.Motion m = list.get(i);
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

	private void drawBone(PoseStack pose, VertexConsumer vc, int b, int kindA, int kindB, int light,
			float vOff, float invU, float invV) {
		pose.pushPose();
		try {
			float[] pv = geo.pivot[b], r = geo.rot[b];
			int o = b * 6;
			pose.translate(off[o + 3], off[o + 4], off[o + 5]);
			pose.translate(pv[0], pv[1], pv[2]);
			float rx = r[0] + off[o], ry = r[1] + off[o + 1], rz = r[2] + off[o + 2];
			if (rx != 0f || ry != 0f || rz != 0f) {
				// Z first, then Y, then X (FORMAT.md): R = Rx * Ry * Rz
				Era.rotX(pose, rx);
				Era.rotY(pose, ry);
				Era.rotZ(pose, rz);
			}
			pose.translate(-pv[0], -pv[1], -pv[2]);
			emit(pose, vc, geo.quads[b][kindA], kindA == ModelGeometry.GLOW ? LightTexture.FULL_BRIGHT : light, vOff, invU, invV);
			if (kindB != kindA) {
				emit(pose, vc, geo.quads[b][kindB], LightTexture.FULL_BRIGHT, vOff, invU, invV);
			}
			for (int child : geo.children[b]) {
				drawBone(pose, vc, child, kindA, kindB, light, vOff, invU, invV);
			}
		} finally {
			pose.popPose();
		}
	}

	private static void emit(PoseStack pose, VertexConsumer vc, float[] quads, int light, float vOff, float invU, float invV) {
		if (quads.length == 0) {
			return;
		}
		PoseStack.Pose p = pose.last();
		for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
			float nx = quads[i + 20], ny = quads[i + 21], nz = quads[i + 22];
			for (int v = 0; v < 4; v++) {
				int k = i + v * 5;
				vc.vertex(p.pose(), quads[k], quads[k + 1], quads[k + 2])
						.color(255, 255, 255, 255)
						.uv(quads[k + 3] * invU, (quads[k + 4] + vOff) * invV)
						.overlayCoords(OverlayTexture.NO_OVERLAY)
						.uv2(light)
						.normal(p.normal(), nx, ny, nz)
						.endVertex();
			}
		}
	}
}
