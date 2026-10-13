package com.origin.client.client.cosmetics;

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

/**
 * The head cosmetics as geometry, ported 1:1 from the store's 3D models
 * (website/js/cosmetics.js: buildOrbitHalo, buildGoldHalo, buildCrown) so the
 * game matches what was bought.
 *
 * Coordinates are the website's: 1 unit = 1 skin pixel, y up, the neck at
 * y=24, the face towards +z. {@link CosmeticHeadLayer} sets the GL matrix up so
 * these land on the player's head.
 *
 * Drawn in passes, never interleaved: all SOLID parts first (lit by the world
 * like the player, or full-bright where the model says so), then all GLOW
 * parts (additive, unlit, no depth write -- 1.16.5's RenderType.eyes).
 *
 * 1.8.9 (fixed function, no matrix objects): a Tessellator batch is placed by
 * the GL matrix current at DRAW time, so the model's own turns (rings tumbling,
 * crown sides) can't be GL pushes between vertices. The model therefore keeps a
 * small CPU matrix stack ({@link Mat}, the 1.16.5 PoseStack calls 1:1) and emits
 * already-transformed vertices; the head placement stays on the GL stack. The
 * parts are textured with the 1x1 white texture (as on 1.16.5) rather than with
 * texturing off, because OptiFine's shader programs sample the bound texture
 * whatever GL_TEXTURE_2D says; vertex colours are the lighting material
 * (entity lighting's colour material).
 */
final class HeadModels {
	private HeadModels() {
	}

	enum Pass { SOLID, GLOW }

	static final ResourceLocation WHITE = new ResourceLocation("originclient", "textures/cosmetics/white.png");

	// 1.16.5's packed light values, kept as markers: FULL_BRIGHT parts glow, LIT
	// parts take the world's light (when there is one).
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int LIT = -1;
	private static final float TAU = (float) (Math.PI * 2);

	/**
	 * t = seconds (animation clock). `worldLit` = drawn on a player in a world
	 * (keep the entity's lighting for LIT parts); false = the menu preview, where
	 * everything is full-bright as 1.16.5's preview passes full-bright light.
	 */
	static void render(String id, boolean worldLit, float t) {
		boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		float lx = OpenGlHelper.lastBrightnessX, ly = OpenGlHelper.lastBrightnessY;
		try {
			Minecraft.getMinecraft().getTextureManager().bindTexture(WHITE);
			GlStateManager.disableCull();   // RenderType.entityCutoutNoCull
			// SOLID, world-lit parts: the entity's own light and lighting state
			if (worldLit) {
				draw(id, t, Pass.SOLID, false, true);
			}
			// SOLID, full-bright parts (and everything in the menu preview)
			GlStateManager.disableLighting();
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
			draw(id, t, Pass.SOLID, true, worldLit);
			// GLOW: additive, unlit, translucent (no depth write)
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
			GlStateManager.depthMask(false);
			draw(id, t, Pass.GLOW, true, worldLit);
		} finally {
			GlStateManager.depthMask(true);
			GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			if (!blend) {
				GlStateManager.disableBlend();
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

	/** One batch: the parts of `pass` that are full-bright (`bright`) or world-lit. */
	private static void draw(String id, float t, Pass pass, boolean bright, boolean worldLit) {
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
		Mesh m = new Mesh(wr, pass, bright, worldLit);
		switch (id) {
			case "halo-orbit":
				orbitHalo(m, t);
				break;
			case "halo-gold":
				goldHalo(m, t);
				break;
			case "crown-pixel":
				crown(m, LIT);
				break;
			default: {
				break;
			}
		}
		tess.draw();   // an empty batch draws nothing
	}

	// ------------------------------------------------------------ Orbit Halo
	// An atom over the head: a nucleus and three rings, each tumbling at its
	// own speed with a bright bead (and fading trail) lapping it. Whole-number
	// turns per ORBIT_LOOP, matching the website's loop.
	private static final float ORBIT_LOOP = 7.2f;
	private static final float[][] ORBIT_RINGS = {
			// radius, tilt, precession / wobble / bead turns per loop, start angle
			{4.8f, 0.40f, 1, 2, 4, 0},
			{4.1f, 0.85f, -2, 3, -5, TAU / 3},
			{3.4f, 1.20f, 3, 1, 6, 2 * TAU / 3},
	};
	private static final int MINT = 0xFF83E2B5, MINT_GLOW = 0xFF1E5A48, WHITE_GLOW = 0xFF4BC8AE;
	private static final float[] BEADS = {0.95f, 0.75f, 0.6f, 0.45f, 0.32f};

	private static void orbitHalo(Mesh m, float t) {
		float w = TAU / ORBIT_LOOP;
		m.pose.push();
		m.pose.translate(0, 37, 0);

		// nucleus
		m.pose.push();
		m.pose.rotateX(w * t);
		m.pose.rotateY(2 * w * t);
		float pulse = 1 + 0.15f * (float) Math.sin(4 * w * t);
		m.box(0, 0, 0, 0.9f * pulse, 0.9f * pulse, 0.9f * pulse, 0xFFFFFFFF, FULL_BRIGHT, Pass.SOLID);
		m.box(0, 0, 0, 2f * pulse, 2f * pulse, 2f * pulse, WHITE_GLOW, FULL_BRIGHT, Pass.GLOW);
		m.pose.pop();

		for (float[] o : ORBIT_RINGS) {
			float r = o[0], dir = Math.signum(o[4]);
			m.pose.push();
			m.pose.rotateY(o[5] + o[2] * w * t);
			m.pose.rotateX(o[1] + 0.12f * (float) Math.sin(o[3] * w * t));
			m.ring(r, 0.26f, MINT, FULL_BRIGHT, Pass.SOLID);
			m.ring(r, 0.68f, MINT_GLOW, FULL_BRIGHT, Pass.GLOW);
			float spin = o[4] * w * t;
			for (int k = 0; k < BEADS.length; k++) {
				float a = spin - dir * k * 0.2f, s = BEADS[k];
				float x = r * (float) Math.cos(a), z = -r * (float) Math.sin(a);
				if (k == 0) {
					m.box(x, 0, z, s, s, s, 0xFFFFFFFF, FULL_BRIGHT, Pass.SOLID);
					m.box(x, 0, z, s * 2.2f, s * 2.2f, s * 2.2f, MINT_GLOW, FULL_BRIGHT, Pass.GLOW);
				} else {
					int fade = 0xFF & (int) (0x83 * (1 - k * 0.18f));
					m.box(x, 0, z, s, s, s, 0xFF000000 | (fade / 3) << 16 | fade << 8 | (fade * 3 / 4), FULL_BRIGHT, Pass.GLOW);
				}
			}
			m.pose.pop();
		}
		m.pose.pop();
	}

	// ----------------------------------------------------------- Golden Halo
	private static void goldHalo(Mesh m, float t) {
		m.pose.push();
		m.pose.translate(0, 35.2f + 0.35f * (float) Math.sin(t * 2), 0);
		m.ring(4.4f, 0.5f, 0xFFFFD56B, FULL_BRIGHT, Pass.SOLID);
		m.ring(4.4f, 1.3f, 0xFF4A3410, FULL_BRIGHT, Pass.GLOW);
		m.pose.pop();
	}

	// ----------------------------------------------------------- Pixel Crown
	// A jewelled gold band worn level and centred on the head, clear of the
	// skin's hat layer (which reaches 4.5), with points, pearls, a velvet cap
	// and an orb and cross on top. Lit by the world like the player.
	private static final float R = 4.85f;
	private static final int GOLD = 0xFFF2C14E, DEEP = 0xFFC9932C, PEARL = 0xFFF4EFE4, VELVET = 0xFFA3203A;
	private static final int[] GEMS = {0xFFE0485B, 0xFF5B8BE0, 0xFF5FD09B, 0xFF5B8BE0};   // front, left, back, right

	private static void crown(Mesh m, int light) {
		if (m.pass != Pass.SOLID) {
			return;
		}
		for (int side = 0; side < 4; side++) {
			m.pose.push();
			m.pose.rotateY(side * TAU / 4);
			m.box(0, 31.8f, R, 2 * R + 0.6f, 2.4f, 0.6f, side % 2 == 1 ? DEEP : GOLD, light, Pass.SOLID);  // band
			m.box(0, 30.8f, R, 2 * R + 0.9f, 0.4f, 0.9f, DEEP, light, Pass.SOLID);                        // rims
			m.box(0, 32.9f, R, 2 * R + 0.9f, 0.4f, 0.9f, DEEP, light, Pass.SOLID);
			m.box(0, 31.8f, R + 0.4f, 1.9f, 1.9f, 0.3f, DEEP, light, Pass.SOLID);                         // gem setting
			m.box(0, 31.8f, R + 0.55f, 1.2f, 1.2f, 0.5f, GEMS[side], light, Pass.SOLID);                  // gem
			m.box(-0.3f, 32.1f, R + 0.85f, 0.4f, 0.4f, 0.1f, 0xFFFFFFFF, FULL_BRIGHT, Pass.SOLID);
			for (float x : new float[]{-2.6f, 2.6f}) {
				m.box(x, 31.8f, R + 0.4f, 0.6f, 0.6f, 0.3f, PEARL, light, Pass.SOLID);
			}
			for (float x : new float[]{-2.5f, 2.5f}) {
				m.box(x, 33.9f, R, 0.8f, 1.6f, 0.6f, GOLD, light, Pass.SOLID);
				m.box(x, 35f, R, 0.7f, 0.7f, 0.7f, PEARL, light, Pass.SOLID);
			}
			m.box(0, 34.5f, R, 1f, 2.8f, 0.6f, GOLD, light, Pass.SOLID);                                  // centre point
			m.pose.push();
			m.pose.translate(0, 36.3f, R);
			m.pose.rotateZ(TAU / 8);
			m.box(0, 0, 0, 0.8f, 0.8f, 0.8f, GEMS[side], light, Pass.SOLID);
			m.pose.pop();
			m.pose.pop();
		}
		for (float[] c : new float[][]{{R, R}, {-R, R}, {R, -R}, {-R, -R}}) {                              // corner points
			m.box(c[0], 34.1f, c[1], 1f, 2.2f, 1f, GOLD, light, Pass.SOLID);
			m.box(c[0], 35.6f, c[1], 0.8f, 0.8f, 0.8f, PEARL, light, Pass.SOLID);
		}
		m.box(0, 33.4f, 0, 2 * R - 0.6f, 1.3f, 2 * R - 0.6f, VELVET, light, Pass.SOLID);                 // velvet cap
		m.box(0, 34.5f, 0, 6.2f, 1f, 6.2f, VELVET, light, Pass.SOLID);
		m.box(0, 35.7f, 0, 1.4f, 1.4f, 1.4f, GOLD, light, Pass.SOLID);                                   // orb
		m.box(0, 37.3f, 0, 0.5f, 1.8f, 0.5f, GOLD, light, Pass.SOLID);                                   // cross
		m.box(0, 37.5f, 0, 1.4f, 0.5f, 0.5f, GOLD, light, Pass.SOLID);
	}

	// ----------------------------------------------------------------- matrix
	/**
	 * 1.8.9: the PoseStack the model code needs, on the CPU (row-major 4x4).
	 * Only rotations and translations are used, so normals take the 3x3 part.
	 */
	static final class Mat {
		private float[] m = identity();
		private final Deque<float[]> stack = new ArrayDeque<>();

		private static float[] identity() {
			return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		}

		void push() {
			stack.push(m.clone());
		}

		void pop() {
			if (!stack.isEmpty()) {
				m = stack.pop();
			}
		}

		void translate(float x, float y, float z) {
			mul(new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1});
		}

		void rotateX(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			mul(new float[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1});
		}

		void rotateY(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			mul(new float[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1});
		}

		void rotateZ(float a) {
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			mul(new float[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
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

	// ----------------------------------------------------------------- mesh
	/** Emits boxes and rings into one batch; parts for other batches are skipped. */
	private static final class Mesh {
		final WorldRenderer wr;
		final Mat pose = new Mat();
		final Pass pass;
		final boolean bright;
		final boolean worldLit;

		Mesh(WorldRenderer wr, Pass pass, boolean bright, boolean worldLit) {
			this.wr = wr;
			this.pass = pass;
			this.bright = bright;
			this.worldLit = worldLit;
		}

		/** Is a part of `in` with this light value in this batch? */
		private boolean mine(Pass in, int light) {
			if (in != pass) {
				return false;
			}
			// without a world everything is full-bright (1.16.5's preview light)
			boolean partBright = light == FULL_BRIGHT || !worldLit;
			return partBright == bright;
		}

		/** Axis-aligned box centred on (cx, cy, cz). */
		void box(float cx, float cy, float cz, float w, float h, float d, int argb, int light, Pass in) {
			if (!mine(in, light)) {
				return;
			}
			float x0 = cx - w / 2, x1 = cx + w / 2, y0 = cy - h / 2, y1 = cy + h / 2, z0 = cz - d / 2, z1 = cz + d / 2;
			quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, argb);
			quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, argb);
			quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, argb);
			quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, argb);
			quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, argb);
			quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, argb);
		}

		/** A flat (horizontal) torus of radius r and tube thickness `tube`. */
		void ring(float r, float tube, int argb, int light, Pass in) {
			if (!mine(in, light)) {
				return;
			}
			final int segs = 48, sides = 6;
			for (int i = 0; i < segs; i++) {
				float a0 = TAU * i / segs, a1 = TAU * (i + 1) / segs;
				for (int j = 0; j < sides; j++) {
					float b0 = TAU * j / sides, b1 = TAU * (j + 1) / sides;
					float[] v00 = torus(r, tube, a0, b0), v10 = torus(r, tube, a1, b0);
					float[] v11 = torus(r, tube, a1, b1), v01 = torus(r, tube, a0, b1);
					float bm = (b0 + b1) / 2, am = (a0 + a1) / 2;
					float nx = (float) (Math.cos(bm) * Math.cos(am)), ny = (float) Math.sin(bm), nz = (float) (Math.cos(bm) * Math.sin(am));
					quad(v00[0], v00[1], v00[2], v10[0], v10[1], v10[2], v11[0], v11[1], v11[2], v01[0], v01[1], v01[2],
							nx, ny, nz, argb);
				}
			}
		}

		private static float[] torus(float r, float tube, float a, float b) {
			float ring = r + tube * (float) Math.cos(b);
			return new float[]{ring * (float) Math.cos(a), tube * (float) Math.sin(b), ring * (float) Math.sin(a)};
		}

		private void quad(float ax, float ay, float az, float bx, float by, float bz,
				float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz, int argb) {
			float tnx = pose.nx(nx, ny, nz), tny = pose.ny(nx, ny, nz), tnz = pose.nz(nx, ny, nz);
			vertex(ax, ay, az, 0, 1, tnx, tny, tnz, argb);
			vertex(bx, by, bz, 1, 1, tnx, tny, tnz, argb);
			vertex(cx, cy, cz, 1, 0, tnx, tny, tnz, argb);
			vertex(dx, dy, dz, 0, 0, tnx, tny, tnz, argb);
		}

		// 1.8.9 WorldRenderer: elements in POSITION_TEX_COLOR_NORMAL's order, the
		// position already through the CPU pose (the GL matrix adds the head).
		private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb) {
			wr.pos(pose.x(x, y, z), pose.y(x, y, z), pose.z(x, y, z))
					.tex(u, v)
					.color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24)
					.normal(nx, ny, nz)
					.endVertex();
		}
	}
}
