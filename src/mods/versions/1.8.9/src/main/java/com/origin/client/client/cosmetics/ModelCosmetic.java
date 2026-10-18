package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic renderer for every model cosmetic (wings, backpacks, hats,
 * halos, crowns, masks, bandanas): one compiled FORMAT.md model, drawn on the
 * bone the caller already positioned. No item is named here.
 *
 * The caller (CosmeticLayer, MenuPlayerPreview) leaves the GL matrix on the
 * attach bone with `scale(1/16, -1/16, -1/16)` applied, which turns FORMAT
 * space (model px, y up, +z front, +x the player's left) into vanilla's
 * (blocks, y down, -z front). Everything below is in FORMAT space.
 *
 * Passes, strictly one after the other (1.21.1: entityCutoutNoCull, then eyes):
 * LIT quads under the caller's lighting (the entity's light in a world, the
 * preview's own in a menu), EMISSIVE quads full-bright, then GLOW quads
 * additive with no depth write.
 *
 * 1.8.9 (fixed function, no matrix objects): a Tessellator batch is placed by
 * the GL matrix current at DRAW time, so the per-bone turns can't be GL pushes
 * between vertices. The bones therefore run on a small CPU matrix stack
 * ({@link Mat}, the PoseStack calls 1:1) and the vertices go out already
 * transformed; the attach-bone placement stays on the GL stack. The GL state
 * each pass needs is set and restored here (as the old HeadModels did): cull
 * off, alpha test for the cutout passes, LayerSpiderEyes' state for the glow.
 *
 * Every failure is swallowed (after one log line per item): a cosmetic never
 * breaks a frame, and a model that threw once is switched off for the session.
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
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't build the model of " + id, t);
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
	public void render(float t, float walk01, float air01) {
		if (failed) {
			return;
		}
		// 1.8.9: no RenderType to carry the state, so it is read here and put back in finally.
		boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
		float lx = OpenGlHelper.lastBrightnessX, ly = OpenGlHelper.lastBrightnessY;
		try {
			motion(t, walk01, air01);
			int frame = frames > 1 ? Math.floorMod((int) Math.floor(t * fps), frames) : 0;
			float vOff = frame * geo.texH;
			float invU = 1f / geo.texW, invV = 1f / (geo.texH * (float) frames);

			Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
			GlStateManager.color(1f, 1f, 1f, 1f);
			GlStateManager.disableCull();          // entityCutoutNoCull
			GlStateManager.disableBlend();         // cutout, not translucent
			GlStateManager.enableAlpha();
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
			pass(ModelGeometry.LIT, vOff, invU, invV);

			GlStateManager.disableLighting();      // full-bright: no shading, sky+block light 15
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
			pass(ModelGeometry.EMISSIVE, vOff, invU, invV);

			if (geo.hasGlow) {
				// 1.8.9: LayerSpiderEyes' state is RenderType.eyes here: additive, no alpha test, no depth write.
				GlStateManager.enableBlend();
				GlStateManager.disableAlpha();
				GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
				GlStateManager.depthMask(false);
				pass(ModelGeometry.GLOW, vOff, invU, invV);
			}
		} catch (Throwable t2) {
			failed = true;
			OriginClient.LOGGER.warn("Origin cosmetics: " + id + " failed to draw; switching it off for this session", t2);
		} finally {
			GlStateManager.depthMask(true);
			GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			if (blend) {
				GlStateManager.enableBlend();
			} else {
				GlStateManager.disableBlend();
			}
			if (alpha) {
				GlStateManager.enableAlpha();
			} else {
				GlStateManager.disableAlpha();
			}
			if (lighting) {
				GlStateManager.enableLighting();
			}
			if (cull) {
				GlStateManager.enableCull();
			}
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lx, ly);
			GlStateManager.color(1f, 1f, 1f, 1f);
		}
	}

	/** One Tessellator batch: every bone's quads of `kind`, through the bone chain. */
	private void pass(int kind, float vOff, float invU, float invV) {
		boolean any = false;
		for (int b = 0; b < geo.quads.length && !any; b++) {
			any = geo.quads[b][kind].length > 0;
		}
		if (!any) {
			return;
		}
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
		Mat pose = new Mat();
		for (int root : geo.roots) {
			drawBone(pose, wr, root, kind, vOff, invU, invV);
		}
		tess.draw();
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
	 *
	 * 1.8.9: no joml Vector3f, `out` is a float[3].
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
			Mat m = new Mat();
			for (int k = depth - 1; k >= 0; k--) {
				int i = pathScratch[k], o = i * 6;
				float[] pv = geo.pivot[i], r = geo.rot[i];
				m.translate(off[o + 3], off[o + 4], off[o + 5]);
				m.translate(pv[0], pv[1], pv[2]);
				m.rotateX((float) Math.toRadians(r[0] + off[o]));
				m.rotateY((float) Math.toRadians(r[1] + off[o + 1]));
				m.rotateZ((float) Math.toRadians(r[2] + off[o + 2]));
				m.translate(-pv[0], -pv[1], -pv[2]);
			}
			float[] pb = geo.pivot[b];
			float px = x + pb[0], py = y + pb[1], pz = z + pb[2];
			out[0] = m.x(px, py, pz);
			out[1] = m.y(px, py, pz);
			out[2] = m.z(px, py, pz);
			return true;
		} catch (Throwable t2) {
			return false;
		}
	}

	private void drawBone(Mat pose, WorldRenderer wr, int b, int kind, float vOff, float invU, float invV) {
		pose.push();
		try {
			float[] pv = geo.pivot[b], r = geo.rot[b];
			int o = b * 6;
			pose.translate(off[o + 3], off[o + 4], off[o + 5]);
			pose.translate(pv[0], pv[1], pv[2]);
			float rx = r[0] + off[o], ry = r[1] + off[o + 1], rz = r[2] + off[o + 2];
			if (rx != 0f || ry != 0f || rz != 0f) {
				// Z first, then Y, then X (FORMAT.md): R = Rx * Ry * Rz, post-multiplied in that order
				pose.rotateX((float) Math.toRadians(rx));
				pose.rotateY((float) Math.toRadians(ry));
				pose.rotateZ((float) Math.toRadians(rz));
			}
			pose.translate(-pv[0], -pv[1], -pv[2]);
			emit(pose, wr, geo.quads[b][kind], vOff, invU, invV);
			for (int child : geo.children[b]) {
				drawBone(pose, wr, child, kind, vOff, invU, invV);
			}
		} finally {
			pose.pop();
		}
	}

	// 1.8.9 WorldRenderer: elements in POSITION_TEX_COLOR_NORMAL's order, the
	// position already through the CPU pose (the GL matrix adds the attach bone).
	private static void emit(Mat pose, WorldRenderer wr, float[] quads, float vOff, float invU, float invV) {
		for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
			float nx = quads[i + 20], ny = quads[i + 21], nz = quads[i + 22];
			float tnx = pose.nx(nx, ny, nz), tny = pose.ny(nx, ny, nz), tnz = pose.nz(nx, ny, nz);
			for (int v = 0; v < 4; v++) {
				int k = i + v * 5;
				float x = quads[k], y = quads[k + 1], z = quads[k + 2];
				wr.pos(pose.x(x, y, z), pose.y(x, y, z), pose.z(x, y, z))
						.tex(quads[k + 3] * invU, (quads[k + 4] + vOff) * invV)
						.color(255, 255, 255, 255)
						.normal(tnx, tny, tnz)
						.endVertex();
			}
		}
	}

	/**
	 * 1.8.9: the PoseStack the bones need, on the CPU (row-major 4x4; moved here
	 * from the old HeadModels). Only rotations and translations are used, so
	 * normals take the 3x3 part.
	 */
	static final class Mat {
		private float[] m = identity();
		private final Deque<float[]> stack = new ArrayDeque<>();

		private static float[] identity() {
			return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		}

		void push() {
			stack.push(m.clone());
		}

		/** A snapshot of the current matrix (no stack), e.g. CapePhysics' joint frames. */
		Mat copy() {
			Mat c = new Mat();
			c.m = m.clone();
			return c;
		}

		void pop() {
			if (!stack.isEmpty()) {
				m = stack.pop();
			}
		}

		void translate(float x, float y, float z) {
			if (x != 0f || y != 0f || z != 0f) {
				mul(new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1});
			}
		}

		void rotateX(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			mul(new float[] {1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1});
		}

		void rotateY(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			mul(new float[] {c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1});
		}

		void rotateZ(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			mul(new float[] {c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
		}

		/** this = this * r (post-multiply, like PoseStack.mulPose / translate). */
		private void mul(float[] r) {
			float[] o = new float[16];
			for (int i = 0; i < 4; i++) {
				for (int j = 0; j < 4; j++) {
					o[i * 4 + j] = m[i * 4] * r[j] + m[i * 4 + 1] * r[4 + j] + m[i * 4 + 2] * r[8 + j] + m[i * 4 + 3] * r[12 + j];
				}
			}
			m = o;
		}

		float x(float x, float y, float z) {
			return m[0] * x + m[1] * y + m[2] * z + m[3];
		}

		float y(float x, float y, float z) {
			return m[4] * x + m[5] * y + m[6] * z + m[7];
		}

		float z(float x, float y, float z) {
			return m[8] * x + m[9] * y + m[10] * z + m[11];
		}

		float nx(float x, float y, float z) {
			return m[0] * x + m[1] * y + m[2] * z;
		}

		float ny(float x, float y, float z) {
			return m[4] * x + m[5] * y + m[6] * z;
		}

		float nz(float x, float y, float z) {
			return m[8] * x + m[9] * y + m[10] * z;
		}
	}
}
