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
 * Because every vertex is already in the attach frame on the CPU, the
 * outer-skin-layer fit ({@link Fit}) bends it over the hat / jacket layer
 * right before it is written (1.21.1 moved to this same CPU path for it).
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
	// Reused matrices (render thread only, like `off`): one per model, never shared between threads.
	private final Mat poseScratch = new Mat();
	private final Mat pointScratch = new Mat();

	// ---- still bones -> compiled display lists (Phase 6.3: the cosmetics bench measured +13 ms p50 for 16
	// wearers, +24 ms with a shader pack's shadow pass, almost all of it re-posing the same vertices on the
	// CPU every frame). A bone with no motion entry on itself or any ancestor ("still") always lands in the
	// same place, the outer-layer fit included, so its quads for one (pass, fit, texture frame) are emitted
	// ONCE into a GL display list -- vanilla ModelRenderer's own method -- and replayed with glCallList.
	// Moving bones keep the per-frame CPU path. Inside a pass the still quads now draw before the moving
	// ones; order does not matter there (the cutout passes are depth-tested, the glow pass is additive).
	// Lists are rebuilt when an OptiFine shader pack turns on or off (a list keeps the vertex attributes it
	// was compiled with). Render thread only, like everything here.
	private static final int ALL = 0, STILL = 1, MOVING = 2;
	private final boolean[] still;          // per bone
	private final boolean[] stillBelow;     // the bone and its whole subtree are still
	private final int[] stillVerts = new int[3], movingVerts = new int[3];   // per pass kind
	private int[] lists;                    // [(kind * 3 + fitSlot) * frames + frame] -> list id, 0 = not built
	private boolean listsForShaders;
	private boolean listsBroken;            // glGenLists failed once: per-frame path only from then on

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
		int n = geo.names.length;
		boolean[] moves = new boolean[n];
		for (int b : motionBone) {
			if (b >= 0) {
				moves[b] = true;
			}
		}
		this.still = new boolean[n];
		for (int b = 0; b < n; b++) {
			boolean s = true;
			for (int i = b, guard = 0; i >= 0 && guard <= n; i = geo.parent[i], guard++) {
				s &= !moves[i];
			}
			still[b] = s;
			for (int kind = 0; kind < 3; kind++) {
				int verts = geo.quads[b][kind].length / ModelGeometry.QUAD * 4;
				if (s) {
					stillVerts[kind] += verts;
				} else {
					movingVerts[kind] += verts;
				}
			}
		}
		this.stillBelow = new boolean[n];
		for (int r : geo.roots) {
			markStillBelow(r, 0);
		}
	}

	private boolean markStillBelow(int b, int depth) {
		boolean s = still[b];
		if (depth <= still.length) {
			for (int c : geo.children[b]) {
				s &= markStillBelow(c, depth + 1);
			}
		}
		stillBelow[b] = s;
		return s;
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
		for (ModelCosmetic m : CACHE.values()) {
			m.freeLists();   // render thread, like every caller here
		}
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
	// Java 8 has no records: a final value class with 1.21.1's record components.
	public static final class Fit {
		/** Head bone: head cube x -4..4, y 0..8, z -4..4; vanilla's hat layer is 0.5 px out. */
		public static final Fit HEAD = new Fit(0f, 4f, 0f, 4f, 4f, 4f, 0.5f);
		/** Body bone: torso x -4..4, y -12..0, z -2..2; vanilla's jacket layer is 0.25 px out. */
		public static final Fit BODY = new Fit(0f, -6f, 0f, 4f, 6f, 2f, 0.25f);

		final float cx, cy, cz, hx, hy, hz, g;

		public Fit(float cx, float cy, float cz, float hx, float hy, float hz, float g) {
			this.cx = cx;
			this.cy = cy;
			this.cz = cz;
			this.hx = hx;
			this.hy = hy;
			this.hz = hz;
			this.g = g;
		}

		static float axis(float p, float c, float h, float g) {
			float u = p - c;
			float a = Math.abs(u);
			return c + (a >= h ? u + Math.signum(u) * g : u * (h + g) / h);
		}
	}

	/** Draws the model without the outer-layer fit. */
	public void render(float t, float walk01, float air01) {
		render(t, walk01, air01, null);
	}

	/**
	 * Draws the model.
	 *
	 * @param t        seconds on the animation clock (game: ticks / 20 + partial)
	 * @param walk01   horizontal speed / sprint speed, 0..1
	 * @param air01    0 on the ground, easing to 1 in the air
	 * @param fit      the outer-layer fit, or null when the player hides that layer
	 */
	public void render(float t, float walk01, float air01, Fit fit) {
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
			pass(ModelGeometry.LIT, fit, frame, vOff, invU, invV);

			GlStateManager.disableLighting();      // full-bright: no shading, sky+block light 15
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
			pass(ModelGeometry.EMISSIVE, fit, frame, vOff, invU, invV);

			if (geo.hasGlow) {
				// 1.8.9: LayerSpiderEyes' state is RenderType.eyes here: additive, no alpha test, no depth write.
				GlStateManager.enableBlend();
				GlStateManager.disableAlpha();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);   // alpha-weighted additive: a radial-gradient glow (halo-orbit centre) stays see-through; opaque texels add exactly as ONE,ONE did
				GlStateManager.depthMask(false);
				pass(ModelGeometry.GLOW, fit, frame, vOff, invU, invV);
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

	/**
	 * Every bone's quads of `kind`: the still bones from their compiled list (built on first use), the
	 * moving ones as one Tessellator batch through the bone chain. Without a list (an unknown Fit, or GL
	 * refused one) everything goes through the batch, as before.
	 */
	private void pass(int kind, Fit fit, int frame, float vOff, float invU, float invV) {
		int stillN = stillVerts[kind], movingN = movingVerts[kind];
		if (stillN == 0 && movingN == 0) {
			return;
		}
		int list = stillN > 0 ? stillList(kind, fit, frame, vOff, invU, invV) : 0;
		if (list != 0) {
			GL11.glCallList(list);
		}
		int cpu = list != 0 ? movingN : stillN + movingN;
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countCosmeticVertices(cpu, list != 0 ? stillN : 0);
		}
		if (cpu > 0) {
			batch(kind, fit, vOff, invU, invV, list != 0 ? MOVING : ALL);
		}
	}

	/** One Tessellator batch of `kind` quads of the bones `mode` selects (ALL, STILL or MOVING). */
	private void batch(int kind, Fit fit, float vOff, float invU, float invV, int mode) {
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
		Mat pose = poseScratch;   // reused, not new: Phase 6 pick 1 (no per-pass garbage)
		pose.reset();
		for (int root : geo.roots) {
			drawBone(pose, wr, root, kind, fit, vOff, invU, invV, mode);
		}
		tess.draw();
	}

	/** Which compiled-list slot a fit uses; -1 = a fit nobody passes today (drawn per frame instead). */
	private static int fitSlot(Fit fit) {
		return fit == null ? 0 : fit == Fit.HEAD ? 1 : fit == Fit.BODY ? 2 : -1;
	}

	/**
	 * The display list holding the still bones' `kind` quads for this fit and texture frame, compiled
	 * the first time it is asked for with exactly the per-frame emit (same pose maths, same fit, same
	 * UVs), so the replay draws the same vertices. 0 = draw them per frame instead.
	 */
	private int stillList(int kind, Fit fit, int frame, float vOff, float invU, float invV) {
		int fs = fitSlot(fit);
		if (fs < 0 || listsBroken) {
			return 0;
		}
		boolean shaders = com.origin.client.client.shaders.IrisBridge.shadersActive();
		if (lists == null || shaders != listsForShaders) {
			freeLists();
			lists = new int[3 * 3 * frames];
			listsForShaders = shaders;
		}
		int k = (kind * 3 + fs) * frames + frame;
		if (lists[k] != 0) {
			return lists[k];
		}
		int id = GL11.glGenLists(1);
		if (id == 0) {
			listsBroken = true;
			return 0;
		}
		boolean ok = false;
		GL11.glNewList(id, GL11.GL_COMPILE);
		try {
			// off[] is zero for every still bone (motion() only writes moving ones), so this is the
			// pose every frame of this texture frame would have.
			batch(kind, fit, vOff, invU, invV, STILL);
			ok = true;
		} finally {
			GL11.glEndList();
			if (!ok) {
				GL11.glDeleteLists(id, 1);
			}
		}
		lists[k] = id;
		return id;
	}

	private void freeLists() {
		if (lists != null) {
			for (int id : lists) {
				if (id != 0) {
					GL11.glDeleteLists(id, 1);
				}
			}
			lists = null;
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

	/** mode: ALL = every bone; STILL = only still bones (a moving bone's subtree is all moving);
	 *  MOVING = only moving bones (an all-still subtree is skipped whole, its quads are in the list). */
	private void drawBone(Mat pose, WorldRenderer wr, int b, int kind, Fit fit, float vOff, float invU, float invV,
			int mode) {
		if ((mode == STILL && !still[b]) || (mode == MOVING && stillBelow[b])) {
			return;
		}
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
			if (mode != MOVING || !still[b]) {
				emit(pose, wr, geo.quads[b][kind], fit, vOff, invU, invV);
			}
			for (int child : geo.children[b]) {
				drawBone(pose, wr, child, kind, fit, vOff, invU, invV, mode);
			}
		} finally {
			pose.pop();
		}
	}

	// 1.8.9 WorldRenderer: elements in POSITION_TEX_COLOR_NORMAL's order, the
	// position already through the CPU pose and the fit (the GL matrix adds the
	// attach bone, normals included: the fit only nudges positions, as on 1.21.1).
	private static void emit(Mat pose, WorldRenderer wr, float[] quads, Fit fit, float vOff, float invU, float invV) {
		for (int i = 0; i < quads.length; i += ModelGeometry.QUAD) {
			float nx = quads[i + 20], ny = quads[i + 21], nz = quads[i + 22];
			float tnx = pose.nx(nx, ny, nz), tny = pose.ny(nx, ny, nz), tnz = pose.nz(nx, ny, nz);
			for (int v = 0; v < 4; v++) {
				int k = i + v * 5;
				float x = quads[k], y = quads[k + 1], z = quads[k + 2];
				float px = pose.x(x, y, z), py = pose.y(x, y, z), pz = pose.z(x, y, z);
				if (fit != null) {
					px = Fit.axis(px, fit.cx, fit.hx, fit.g);
					py = Fit.axis(py, fit.cy, fit.hy, fit.g);
					pz = Fit.axis(pz, fit.cz, fit.hz, fit.g);
				}
				wr.pos(px, py, pz)
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
	 *
	 * Allocation-free (Phase 6 pick 1: this used to build a float[16] per
	 * translate / rotate / multiply plus a clone per push, about 16 KB of garbage
	 * per cosmetic wearer per frame). One Mat is built once and reused: the stack
	 * is a fixed array of 16-float slots that push / pop copy into, and every
	 * translate / rotate edits the matrix in place. Each edit is the old full
	 * multiply with its zero and one factor terms dropped, so the numbers match
	 * the old code (the sums keep the old order). NOT thread-safe: one Mat is only
	 * ever used from the render thread (see the owners).
	 */
	static final class Mat {
		private static final int START_DEPTH = 16;   // a model's bone chain is shorter; grows once if a deeper one ever shows up
		private final float[] m = new float[16];
		private float[][] stack = new float[START_DEPTH][16];
		private int sp = 0;

		Mat() {
			reset();
		}

		/** Back to the identity with an empty stack: a reused Mat starts every pass from here. */
		void reset() {
			m[0] = 1; m[1] = 0; m[2] = 0; m[3] = 0;
			m[4] = 0; m[5] = 1; m[6] = 0; m[7] = 0;
			m[8] = 0; m[9] = 0; m[10] = 1; m[11] = 0;
			m[12] = 0; m[13] = 0; m[14] = 0; m[15] = 1;
			sp = 0;
		}

		void push() {
			if (sp == stack.length) {
				float[][] bigger = new float[sp * 2][];
				System.arraycopy(stack, 0, bigger, 0, sp);
				for (int i = sp; i < bigger.length; i++) {
					bigger[i] = new float[16];
				}
				stack = bigger;
			}
			System.arraycopy(m, 0, stack[sp++], 0, 16);
		}

		/** Copies the current matrix into `dst` (e.g. CapePhysics' preallocated joint frames); no stack. */
		void copyInto(Mat dst) {
			System.arraycopy(m, 0, dst.m, 0, 16);
		}

		void pop() {
			if (sp > 0) {
				System.arraycopy(stack[--sp], 0, m, 0, 16);
			}
		}

		/** this = this * T(x, y, z): only the last column changes. */
		void translate(float x, float y, float z) {
			if (x != 0f || y != 0f || z != 0f) {
				m[3] = m[0] * x + m[1] * y + m[2] * z + m[3];
				m[7] = m[4] * x + m[5] * y + m[6] * z + m[7];
				m[11] = m[8] * x + m[9] * y + m[10] * z + m[11];
				m[15] = m[12] * x + m[13] * y + m[14] * z + m[15];
			}
		}

		/** this = this * Rx(a): only columns 1 and 2 change. */
		void rotateX(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			for (int i = 0; i < 16; i += 4) {
				float b = m[i + 1], d = m[i + 2];
				m[i + 1] = b * c + d * s;
				m[i + 2] = b * -s + d * c;
			}
		}

		/** this = this * Ry(a): only columns 0 and 2 change. */
		void rotateY(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			for (int i = 0; i < 16; i += 4) {
				float b = m[i], d = m[i + 2];
				m[i] = b * c + d * -s;
				m[i + 2] = b * s + d * c;
			}
		}

		/** this = this * Rz(a): only columns 0 and 1 change. */
		void rotateZ(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			for (int i = 0; i < 16; i += 4) {
				float b = m[i], d = m[i + 1];
				m[i] = b * c + d * s;
				m[i + 1] = b * -s + d * c;
			}
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
