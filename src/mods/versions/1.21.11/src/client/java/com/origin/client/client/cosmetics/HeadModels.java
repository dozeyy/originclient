package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * The head cosmetics as geometry, ported 1:1 from the store's 3D models
 * (website/js/cosmetics.js: buildOrbitHalo, buildGoldHalo, buildCrown) so the
 * game matches what was bought.
 *
 * Coordinates are the website's: 1 unit = 1 skin pixel, y up, the neck at
 * y=24, the face towards +z. {@link CosmeticHeadLayer} sets the pose up so
 * these land on the player's head.
 *
 * Drawn in two passes: all SOLID parts, then all GLOW parts (additive,
 * unlit) - never interleaved.
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era): entity layers no longer
 * get a buffer source to write into. Each pass is handed to the frame's
 * SubmitNodeCollector as one custom-geometry node
 * (submitCustomGeometry(PoseStack, RenderType, (pose, consumer) -> ...)),
 * which the feature renderer replays later with the pose captured at submit
 * time; the mesh code below then runs unchanged on a PoseStack rebuilt from
 * that pose. Render types live in rendertype.RenderTypes; Identifier
 * was ResourceLocation.
 */
final class HeadModels {
	private HeadModels() {
	}

	enum Pass { SOLID, GLOW }

	static final Identifier WHITE = Identifier.fromNamespaceAndPath("originclient", "textures/cosmetics/white.png");
	private static final float TAU = (float) (Math.PI * 2);

	/** t = seconds (animation clock). light = the entity's packed light. */
	static void render(String id, PoseStack pose, SubmitNodeCollector collector, int light, float t) {
		if (!id.equals("halo-orbit") && !id.equals("halo-gold") && !id.equals("crown-pixel")) {
			return;   // nothing this build can draw
		}
		for (Pass pass : Pass.values()) {
			if (pass == Pass.GLOW && id.equals("crown-pixel")) {
				continue;   // the crown has no glow parts: don't submit an empty node
			}
			collector.submitCustomGeometry(pose, pass == Pass.SOLID
					? RenderTypes.entityCutoutNoCull(WHITE)
					: RenderTypes.eyes(WHITE), (p, vc) -> {
				// Replayed later by the feature renderer: rebuild a stack on the
				// pose captured at submit time, then emit exactly as 1.21.1 does.
				PoseStack stack = new PoseStack();
				stack.last().set(p);
				emit(id, new Mesh(vc, stack, pass), light, t);
			});
		}
	}

	private static void emit(String id, Mesh m, int light, float t) {
		switch (id) {
			case "halo-orbit" -> orbitHalo(m, t);
			case "halo-gold" -> goldHalo(m, t);
			case "crown-pixel" -> crown(m, light);
			default -> {
			}
		}
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
		m.pose.pushPose();
		m.pose.translate(0, 37, 0);

		// nucleus
		m.pose.pushPose();
		m.pose.mulPose(Axis.XP.rotation(w * t));
		m.pose.mulPose(Axis.YP.rotation(2 * w * t));
		float pulse = 1 + 0.15f * (float) Math.sin(4 * w * t);
		m.box(0, 0, 0, 0.9f * pulse, 0.9f * pulse, 0.9f * pulse, 0xFFFFFFFF, LightTexture.FULL_BRIGHT, Pass.SOLID);
		m.box(0, 0, 0, 2f * pulse, 2f * pulse, 2f * pulse, WHITE_GLOW, LightTexture.FULL_BRIGHT, Pass.GLOW);
		m.pose.popPose();

		for (float[] o : ORBIT_RINGS) {
			float r = o[0], dir = Math.signum(o[4]);
			m.pose.pushPose();
			m.pose.mulPose(Axis.YP.rotation(o[5] + o[2] * w * t));
			m.pose.mulPose(Axis.XP.rotation(o[1] + 0.12f * (float) Math.sin(o[3] * w * t)));
			m.ring(r, 0.26f, MINT, LightTexture.FULL_BRIGHT, Pass.SOLID);
			m.ring(r, 0.68f, MINT_GLOW, LightTexture.FULL_BRIGHT, Pass.GLOW);
			float spin = o[4] * w * t;
			for (int k = 0; k < BEADS.length; k++) {
				float a = spin - dir * k * 0.2f, s = BEADS[k];
				float x = r * (float) Math.cos(a), z = -r * (float) Math.sin(a);
				if (k == 0) {
					m.box(x, 0, z, s, s, s, 0xFFFFFFFF, LightTexture.FULL_BRIGHT, Pass.SOLID);
					m.box(x, 0, z, s * 2.2f, s * 2.2f, s * 2.2f, MINT_GLOW, LightTexture.FULL_BRIGHT, Pass.GLOW);
				} else {
					int fade = 0xFF & (int) (0x83 * (1 - k * 0.18f));
					m.box(x, 0, z, s, s, s, 0xFF000000 | (fade / 3) << 16 | fade << 8 | (fade * 3 / 4), LightTexture.FULL_BRIGHT, Pass.GLOW);
				}
			}
			m.pose.popPose();
		}
		m.pose.popPose();
	}

	// ----------------------------------------------------------- Golden Halo
	private static void goldHalo(Mesh m, float t) {
		m.pose.pushPose();
		m.pose.translate(0, 35.2f + 0.35f * (float) Math.sin(t * 2), 0);
		m.ring(4.4f, 0.5f, 0xFFFFD56B, LightTexture.FULL_BRIGHT, Pass.SOLID);
		m.ring(4.4f, 1.3f, 0xFF4A3410, LightTexture.FULL_BRIGHT, Pass.GLOW);
		m.pose.popPose();
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
			m.pose.pushPose();
			m.pose.mulPose(Axis.YP.rotation(side * TAU / 4));
			m.box(0, 31.8f, R, 2 * R + 0.6f, 2.4f, 0.6f, side % 2 == 1 ? DEEP : GOLD, light, Pass.SOLID);  // band
			m.box(0, 30.8f, R, 2 * R + 0.9f, 0.4f, 0.9f, DEEP, light, Pass.SOLID);                        // rims
			m.box(0, 32.9f, R, 2 * R + 0.9f, 0.4f, 0.9f, DEEP, light, Pass.SOLID);
			m.box(0, 31.8f, R + 0.4f, 1.9f, 1.9f, 0.3f, DEEP, light, Pass.SOLID);                         // gem setting
			m.box(0, 31.8f, R + 0.55f, 1.2f, 1.2f, 0.5f, GEMS[side], light, Pass.SOLID);                  // gem
			m.box(-0.3f, 32.1f, R + 0.85f, 0.4f, 0.4f, 0.1f, 0xFFFFFFFF, LightTexture.FULL_BRIGHT, Pass.SOLID);
			for (float x : new float[]{-2.6f, 2.6f}) {
				m.box(x, 31.8f, R + 0.4f, 0.6f, 0.6f, 0.3f, PEARL, light, Pass.SOLID);
			}
			for (float x : new float[]{-2.5f, 2.5f}) {
				m.box(x, 33.9f, R, 0.8f, 1.6f, 0.6f, GOLD, light, Pass.SOLID);
				m.box(x, 35f, R, 0.7f, 0.7f, 0.7f, PEARL, light, Pass.SOLID);
			}
			m.box(0, 34.5f, R, 1f, 2.8f, 0.6f, GOLD, light, Pass.SOLID);                                  // centre point
			m.pose.pushPose();
			m.pose.translate(0, 36.3f, R);
			m.pose.mulPose(Axis.ZP.rotation(TAU / 8));
			m.box(0, 0, 0, 0.8f, 0.8f, 0.8f, GEMS[side], light, Pass.SOLID);
			m.pose.popPose();
			m.pose.popPose();
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

	// ----------------------------------------------------------------- mesh
	/** Emits boxes and rings into one pass; parts for the other pass are skipped. */
	private static final class Mesh {
		final VertexConsumer vc;
		final PoseStack pose;
		final Pass pass;

		Mesh(VertexConsumer vc, PoseStack pose, Pass pass) {
			this.vc = vc;
			this.pose = pose;
			this.pass = pass;
		}

		/** Axis-aligned box centred on (cx, cy, cz). */
		void box(float cx, float cy, float cz, float w, float h, float d, int argb, int light, Pass in) {
			if (in != pass) {
				return;
			}
			float x0 = cx - w / 2, x1 = cx + w / 2, y0 = cy - h / 2, y1 = cy + h / 2, z0 = cz - d / 2, z1 = cz + d / 2;
			PoseStack.Pose p = pose.last();
			quad(p, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, argb, light);
			quad(p, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, argb, light);
			quad(p, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, argb, light);
			quad(p, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, argb, light);
			quad(p, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, argb, light);
			quad(p, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, argb, light);
		}

		/** A flat (horizontal) torus of radius r and tube thickness `tube`. */
		void ring(float r, float tube, int argb, int light, Pass in) {
			if (in != pass) {
				return;
			}
			final int segs = 48, sides = 6;
			PoseStack.Pose p = pose.last();
			for (int i = 0; i < segs; i++) {
				float a0 = TAU * i / segs, a1 = TAU * (i + 1) / segs;
				for (int j = 0; j < sides; j++) {
					float b0 = TAU * j / sides, b1 = TAU * (j + 1) / sides;
					float[] v00 = torus(r, tube, a0, b0), v10 = torus(r, tube, a1, b0);
					float[] v11 = torus(r, tube, a1, b1), v01 = torus(r, tube, a0, b1);
					float bm = (b0 + b1) / 2, am = (a0 + a1) / 2;
					float nx = (float) (Math.cos(bm) * Math.cos(am)), ny = (float) Math.sin(bm), nz = (float) (Math.cos(bm) * Math.sin(am));
					quad(p, v00[0], v00[1], v00[2], v10[0], v10[1], v10[2], v11[0], v11[1], v11[2], v01[0], v01[1], v01[2],
							nx, ny, nz, argb, light);
				}
			}
		}

		private static float[] torus(float r, float tube, float a, float b) {
			float ring = r + tube * (float) Math.cos(b);
			return new float[]{ring * (float) Math.cos(a), tube * (float) Math.sin(b), ring * (float) Math.sin(a)};
		}

		private void quad(PoseStack.Pose p, float ax, float ay, float az, float bx, float by, float bz,
				float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz, int argb, int light) {
			vertex(p, ax, ay, az, 0, 1, nx, ny, nz, argb, light);
			vertex(p, bx, by, bz, 1, 1, nx, ny, nz, argb, light);
			vertex(p, cx, cy, cz, 1, 0, nx, ny, nz, argb, light);
			vertex(p, dx, dy, dz, 0, 0, nx, ny, nz, argb, light);
		}

		private void vertex(PoseStack.Pose p, float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb, int light) {
			vc.addVertex(p, x, y, z)
					.setColor(argb)
					.setUv(u, v)
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light)
					.setNormal(p, nx, ny, nz);
		}
	}
}
