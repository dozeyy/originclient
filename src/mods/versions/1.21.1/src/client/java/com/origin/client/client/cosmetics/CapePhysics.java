package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.IdentityHashMap;

/**
 * Cape physics for Origin capes (Lunar-style): instead of vanilla's stiff
 * angle maths the cape is a short chain of {@link #N} cloth segments, each a
 * damped spring that follows the one above it. Driven by the wearer's real
 * movement (walk/sprint speed, jump and fall, turning, crouching) so the cape
 * lags, overshoots and settles, with a gentle idle sway when standing still.
 *
 * How it plugs in: CapePhysicsMixin cancels vanilla's CapeLayer.render only
 * for players wearing an ORIGIN cape (vanilla/Mojang capes keep vanilla
 * physics) and calls {@link #render}. Everything vanilla decides stays the
 * same: invisible, the "Cape" skin option, elytra (no cape drawn), the
 * chestplate/crouch offsets of the cloth (PlayerModel.setupAnim's cloak.y/z).
 * {@link #render} returns false whenever it can't draw -- the mixin then lets
 * vanilla run, so any failure degrades to the normal cape.
 *
 * Simulation: one step per game tick (20 Hz, 4 sub-steps for stability),
 * state kept per player entity in a weak map (gone with the entity; stale
 * entries are also swept). Drawing interpolates the last two steps with the
 * frame's partial tick, so high FPS is smooth, never steppy.
 *
 * Angle convention = vanilla's: pitch is the X rotation (degrees) that swings
 * the cape's bottom backwards, roll the Z rotation that swings it sideways
 * (positive = towards the wearer's right, as vanilla's `l`). Values are
 * CUMULATIVE down the chain; the renderer turns them into per-joint bends.
 */
public final class CapePhysics {
	private CapePhysics() {
	}

	/** Cloth segments from the shoulders down; each is 16 / N skin pixels tall. */
	static final int N = 4;
	private static final float SEG_PX = 16f / N;
	/** One game tick, split into sub-steps (spring frequency stays well under 1/dt). */
	private static final float TICK_DT = 0.05f;
	private static final int SUBSTEPS = 4;

	// Spring stiffness per segment (top stiffest, so the hem lags most), the pull
	// of each segment towards the one above it, and the damping ratio. ~2.2 Hz at
	// the top down to ~1.4 Hz at the hem; ratio 0.42 = a visible but short overshoot.
	private static final float[] K = {190f, 150f, 115f, 85f};
	private static final float[] KC = {0f, 110f, 90f, 70f};
	private static final float ZETA = 0.42f;
	private static final float[] C = new float[N];

	static {
		for (int i = 0; i < N; i++) {
			C[i] = 2f * ZETA * (float) Math.sqrt(K[i] + KC[i]);
		}
	}

	// Limits: the cape must never swing into the body or legs (cumulative pitch
	// has a floor just off vertical), nor fold over itself (bend per joint).
	private static final float MIN_PITCH = 1.5f;
	private static final float MAX_PITCH = 140f;
	private static final float MAX_BEND = 32f;
	private static final float MAX_ROLL = 26f;

	/** Per-player simulation state. Render thread only. */
	private static final class State {
		boolean init;
		int lastTick;
		long lastSeenMs;
		final float phase;
		final float[] pitch = new float[N], pitchV = new float[N], pitchPrev = new float[N];
		final float[] roll = new float[N], rollV = new float[N], rollPrev = new float[N];
		final float[] tPitch = new float[N], tRoll = new float[N];
		float calm, calmPrev;   // 1 = standing still (idle sway), 0 = moving

		State(AbstractClientPlayer p) {
			// A stable per-player offset so two capes side by side don't sway in unison.
			this.phase = (p.getUUID().hashCode() & 0xFFFF) / 65535f * Mth.TWO_PI;
		}
	}

	// By identity, not Entity.equals (entity id): a menu preview or fake player sharing a
	// real player's id must not share (and snap) that player's cape. sweep() drops unseen ones.
	private static final Map<AbstractClientPlayer, State> STATES = new IdentityHashMap<>();
	private static long lastSweepMs = 0;

	// Joint frames for the current draw, reused (render thread only): joint j's
	// position matrix and normal matrix, with that joint's twist already applied.
	private static final Matrix4f[] JOINT = new Matrix4f[N + 1];
	private static final Matrix3f[] JOINT_N = new Matrix3f[N + 1];
	private static final Vector3f TMP = new Vector3f();

	static {
		for (int j = 0; j <= N; j++) {
			JOINT[j] = new Matrix4f();
			JOINT_N[j] = new Matrix3f();
		}
	}

	/**
	 * Draws `p`'s Origin cape with physics, replacing vanilla's CapeLayer.render.
	 *
	 * @return true if the cape was drawn (the mixin cancels vanilla); false to
	 *         let vanilla draw (physics off, not an Origin cape, no cape to
	 *         show, far away, or anything went wrong).
	 */
	public static boolean render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer p,
			float partialTick, float ageInTicks) {
		if (!Mods.bool(Mods.GENERAL_ID, "capePhysics")) {
			return false;
		}
		if (p.isInvisible() || !p.isModelPartShown(PlayerModelPart.CAPE)) {
			return false;
		}
		CosmeticsService.Look look = CosmeticsService.lookOf(p.getUUID());
		String id = look.cape();
		if (id == null) {
			return false;   // vanilla / Mojang cape: untouched
		}
		if (look.hidesCape()) {
			return true;    // wings / a backpack replace the cape: draw nothing, and keep vanilla from drawing it
		}
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (chest.is(Items.ELYTRA)) {
			return false;   // vanilla draws nothing; so do we
		}
		ResourceLocation tex = p.getSkin().capeTexture();
		if (tex == null) {
			return false;
		}

		State s = STATES.computeIfAbsent(p, State::new);
		long now = System.currentTimeMillis();
		s.lastSeenMs = now;
		advance(s, p);
		sweep(now);

		boolean crouch = p.isCrouching();
		// PlayerModel.setupAnim's cloak offset (model pixels, applied in the cape's own frame).
		float cy;
		float cz;
		if (chest.isEmpty()) {
			cy = crouch ? 1.85f : 0f;
			cz = crouch ? 1.4f : 0f;
		} else {
			cy = crouch ? 0.8f : -0.85f;
			cz = crouch ? 0.3f : -1.1f;
		}

		float t = ageInTicks / 20f;
		float calm = Mth.lerp(partialTick, s.calmPrev, s.calm);
		float[] relP = new float[N];
		float[] relR = new float[N];
		float[] cumR = new float[N];
		float prevP = 0f;
		float prevR = 0f;
		for (int i = 0; i < N; i++) {
			float cumP = Mth.lerp(partialTick, s.pitchPrev[i], s.pitch[i])
					+ calm * 1.4f * (1f + 0.35f * i) * Mth.sin(t * 1.9f + s.phase - i * 0.9f);
			float r = Mth.lerp(partialTick, s.rollPrev[i], s.roll[i])
					+ calm * 1.2f * (1f + 0.5f * i) * Mth.sin(t * 1.3f + s.phase * 1.7f - i * 0.7f);
			cumP = Mth.clamp(cumP, MIN_PITCH, MAX_PITCH);
			r = Mth.clamp(r, -MAX_ROLL, MAX_ROLL);
			if (i == 0) {
				relP[i] = cumP;
				relR[i] = r;
			} else {
				// Keep the joint from folding: limit the bend, and carry the limit down the chain.
				relP[i] = Mth.clamp(cumP - prevP, -MAX_BEND, MAX_BEND);
				relR[i] = Mth.clamp(r - prevR, -MAX_BEND, MAX_BEND);
			}
			prevP = i == 0 ? cumP : prevP + relP[i];
			prevR = i == 0 ? r : prevR + relR[i];
			cumR[i] = prevR;
		}

		// Walk the chain once to find every joint's frame, then draw the cloth as ONE
		// continuous surface through those joints. (It used to be N separate boxes,
		// one per band: at every bend they split apart and showed their inner top and
		// bottom faces as dark slices across the cape - Will, 2026-10-18.) An inner
		// joint takes HALF its bend, so the bands above and below meet on the bisector
		// with no gap; the cape's Y twist (vanilla's 180 - roll) is blended the same way.
		pose.pushPose();
		try {
			pose.translate(0f, 0f, 0.125f + look.capeOffset() / 16f);   // vanilla: 2 px behind the body (+ clearance for worn items)
			pose.translate(0f, -look.adjust(Cosmetics.Slot.CAPE) / 16f, 0f);   // the player's own height tweak (model y is down)
			for (int i = 0; i < N; i++) {
				if (i > 0) {
					pose.translate(0f, SEG_PX / 16f, 0f);   // down to this joint
					joint(pose, i, relP[i] * 0.5f, relR[i] * 0.5f, 180f - (cumR[i - 1] + cumR[i]) * 0.5f);
				}
				pose.mulPose(Axis.XP.rotationDegrees(relP[i]));
				pose.mulPose(Axis.ZP.rotationDegrees(relR[i]));
				if (i == 0) {
					// Vanilla applies the cloak offset after its rotations, in the cape's
					// own (Y-flipped) frame, so z is mirrored here, before the flip.
					pose.translate(0f, cy / 16f, -cz / 16f);
					joint(pose, 0, 0f, 0f, 180f - cumR[0]);
				}
			}
			pose.translate(0f, SEG_PX / 16f, 0f);
			joint(pose, N, 0f, 0f, 180f - cumR[N - 1]);
		} finally {
			pose.popPose();
		}
		drawCloth(buffers.getBuffer(RenderType.entitySolid(tex)), light);
		return true;
	}

	/** Records joint j's frame: the current pose turned by the half-bend and the twist. */
	private static void joint(PoseStack pose, int j, float bendP, float bendR, float twist) {
		pose.pushPose();
		try {
			pose.mulPose(Axis.XP.rotationDegrees(bendP));
			pose.mulPose(Axis.ZP.rotationDegrees(bendR));
			pose.mulPose(Axis.YP.rotationDegrees(twist));
			JOINT[j].set(pose.last().pose());
			JOINT_N[j].set(pose.last().normal());
		} finally {
			pose.popPose();
		}
	}

	/**
	 * The cloth through the recorded joints: vanilla's cloak box (x -5..5, z -1..0
	 * in skin px, 16 px tall) with the exact face layout and UVs ModelPart gives a
	 * 10 x 16 x 1 box at texOffs 0,0 on the 64 x 32 cape texture, except that the
	 * long faces are cut into N bands whose edges are shared at the joints. Band j
	 * spans texture rows 1 + SEG_PX * j .. 1 + SEG_PX * (j + 1).
	 */
	private static void drawCloth(VertexConsumer vc, int light) {
		for (int j = 0; j < N; j++) {
			int a = j;
			int b = j + 1;
			float v0 = 1f + SEG_PX * j;
			float v1 = v0 + SEG_PX;
			// outside (north, z = -1): the cape's picture
			vert(vc, light, a, 5f, -1f, 11f, v0, 0f, 0f, -1f);
			vert(vc, light, a, -5f, -1f, 1f, v0, 0f, 0f, -1f);
			vert(vc, light, b, -5f, -1f, 1f, v1, 0f, 0f, -1f);
			vert(vc, light, b, 5f, -1f, 11f, v1, 0f, 0f, -1f);
			// inside (south, z = 0)
			vert(vc, light, a, -5f, 0f, 22f, v0, 0f, 0f, 1f);
			vert(vc, light, a, 5f, 0f, 12f, v0, 0f, 0f, 1f);
			vert(vc, light, b, 5f, 0f, 12f, v1, 0f, 0f, 1f);
			vert(vc, light, b, -5f, 0f, 22f, v1, 0f, 0f, 1f);
			// edges (west x = -5, east x = 5)
			vert(vc, light, a, -5f, -1f, 1f, v0, -1f, 0f, 0f);
			vert(vc, light, a, -5f, 0f, 0f, v0, -1f, 0f, 0f);
			vert(vc, light, b, -5f, 0f, 0f, v1, -1f, 0f, 0f);
			vert(vc, light, b, -5f, -1f, 1f, v1, -1f, 0f, 0f);
			vert(vc, light, a, 5f, 0f, 12f, v0, 1f, 0f, 0f);
			vert(vc, light, a, 5f, -1f, 11f, v0, 1f, 0f, 0f);
			vert(vc, light, b, 5f, -1f, 11f, v1, 1f, 0f, 0f);
			vert(vc, light, b, 5f, 0f, 12f, v1, 1f, 0f, 0f);
		}
		// top strip at the shoulders (vanilla's DOWN face) and the hem (its UP face)
		vert(vc, light, 0, 5f, 0f, 11f, 0f, 0f, -1f, 0f);
		vert(vc, light, 0, -5f, 0f, 1f, 0f, 0f, -1f, 0f);
		vert(vc, light, 0, -5f, -1f, 1f, 1f, 0f, -1f, 0f);
		vert(vc, light, 0, 5f, -1f, 11f, 1f, 0f, -1f, 0f);
		vert(vc, light, N, 5f, -1f, 21f, 1f, 0f, 1f, 0f);
		vert(vc, light, N, -5f, -1f, 11f, 1f, 0f, 1f, 0f);
		vert(vc, light, N, -5f, 0f, 11f, 0f, 0f, 1f, 0f);
		vert(vc, light, N, 5f, 0f, 21f, 0f, 0f, 1f, 0f);
	}

	/** One vertex at joint j: (x, z) skin px across the cloth, (u, v) cape-texture px, a model-space normal. */
	private static void vert(VertexConsumer vc, int light, int j, float x, float z, float u, float v,
			float nx, float ny, float nz) {
		Vector3f p = JOINT[j].transformPosition(x / 16f, 0f, z / 16f, TMP);
		float px = p.x;
		float py = p.y;
		float pz = p.z;
		Vector3f n = JOINT_N[j].transform(nx, ny, nz, TMP).normalize();
		vc.addVertex(px, py, pz, 0xFFFFFFFF, u / 64f, v / 32f, OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
	}

	// ------------------------------------------------------------------ the sim

	/** Brings the state up to this game tick (a no-op when already there). */
	private static void advance(State s, AbstractClientPlayer p) {
		int tick = p.tickCount;
		if (!s.init) {
			targets(s, p);
			snap(s);
			s.init = true;
			s.lastTick = tick;
			return;
		}
		if (tick == s.lastTick) {
			return;
		}
		int n = tick - s.lastTick;
		s.lastTick = tick;
		if (n < 0 || n > 40) {
			targets(s, p);
			snap(s);   // long gap (out of range, or teleported): start settled
			return;
		}
		targets(s, p);
		System.arraycopy(s.pitch, 0, s.pitchPrev, 0, N);
		System.arraycopy(s.roll, 0, s.rollPrev, 0, N);
		s.calmPrev = s.calm;
		// Frames can skip ticks on a slow machine: replay up to 3 with the latest input.
		int steps = Math.min(n, 3);
		for (int k = 0; k < steps; k++) {
			integrate(s.pitch, s.pitchV, s.tPitch, 170f);
			integrate(s.roll, s.rollV, s.tRoll, 40f);
		}
	}

	private static void snap(State s) {
		for (int i = 0; i < N; i++) {
			s.pitch[i] = s.pitchPrev[i] = s.tPitch[i];
			s.roll[i] = s.rollPrev[i] = s.tRoll[i];
			s.pitchV[i] = 0f;
			s.rollV[i] = 0f;
		}
		s.calm = s.calmPrev = s.calm;
	}

	/** Where the cape would settle given what the player is doing this tick. */
	private static void targets(State s, AbstractClientPlayer p) {
		double vx = p.getX() - p.xo;
		double vy = p.getY() - p.yo;
		double vz = p.getZ() - p.zo;
		if (vx * vx + vz * vz > 9.0 || Math.abs(vy) > 3.0) {
			vx = 0;
			vy = 0;
			vz = 0;   // a teleport, not a run
		}
		float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
		float sin = Mth.sin(yaw);
		float cos = Mth.cos(yaw);
		// Vanilla's cloak lags the body by ~3 ticks of travel (xCloak += 0.25 * gap);
		// x300 = its x100 on that lag. These are vanilla's own k / l / j terms.
		float forward = (float) (-vx * sin + vz * cos) * 300f;
		float lateral = (float) (vx * cos + vz * sin) * 300f;
		float rise = Mth.clamp((float) -vy * 30f, -6f, 32f);
		float speedAngle = Mth.clamp(forward, 0f, 150f) * 0.5f;
		float sway = Mth.clamp(lateral, -20f, 20f) * 0.5f;
		// Turning: the hem swings to the side you turn towards (it stays where it was
		// while the body rotates away from it).
		float turn = Mth.wrapDegrees(p.yBodyRot - p.yBodyRotO);
		sway += Mth.clamp(turn * 0.25f, -5f, 5f);

		float base = 6f + speedAngle + rise + (p.isCrouching() ? 25f : 0f);
		float flare = (speedAngle + Math.max(rise, 0f)) * 0.10f;   // the hem streams out a little more
		for (int i = 0; i < N; i++) {
			s.tPitch[i] = Mth.clamp(base + flare * i, MIN_PITCH, MAX_PITCH);
			s.tRoll[i] = Mth.clamp(sway * (1f + 0.35f * i), -MAX_ROLL, MAX_ROLL);
		}
		float speed = (float) Math.sqrt(vx * vx + vz * vz);
		float calmGoal = speed < 0.02f && Math.abs(vy) < 0.05f && Math.abs(turn) < 2f ? 1f : 0f;
		s.calm += (calmGoal - s.calm) * 0.1f;
	}

	/** One game tick of a damped spring chain (angles in degrees). */
	private static void integrate(float[] x, float[] v, float[] target, float limit) {
		float h = TICK_DT / SUBSTEPS;
		for (int sub = 0; sub < SUBSTEPS; sub++) {
			for (int i = 0; i < N; i++) {
				float a = -K[i] * (x[i] - target[i]) - C[i] * v[i];
				if (i > 0) {
					a += KC[i] * (x[i - 1] - x[i]);
				}
				v[i] = Mth.clamp(v[i] + a * h, -900f, 900f);
			}
			for (int i = 0; i < N; i++) {
				x[i] = Mth.clamp(x[i] + v[i] * h, -limit, limit);
			}
		}
	}

	/** Drops state for players not drawn for a while (left, died, far away). */
	private static void sweep(long now) {
		if (now - lastSweepMs < 10_000L) {
			return;
		}
		lastSweepMs = now;
		Iterator<State> it = STATES.values().iterator();
		while (it.hasNext()) {
			if (now - it.next().lastSeenMs > 15_000L) {
				it.remove();
			}
		}
	}

	/** Forget everything (leaving a world). */
	public static void clear() {
		STATES.clear();
	}

	// -------------------------------------------------------- for the sparkles

	/**
	 * The latest simulated cape shape of `p`, for placing sparks on the cloth:
	 * fills `out` with the cumulative pitch (degrees) of each segment and
	 * returns true; false if this player has no live cape state (use a hanging
	 * cape then).
	 */
	static boolean shape(AbstractClientPlayer p, float[] out) {
		State s = STATES.get(p);
		if (s == null || !s.init || out.length < N || System.currentTimeMillis() - s.lastSeenMs > 1000L) {
			return false;
		}
		for (int i = 0; i < N; i++) {
			out[i] = Mth.clamp(s.pitch[i], MIN_PITCH, MAX_PITCH);
		}
		return true;
	}

	/** Segment height in skin pixels (for {@link #shape} users). */
	static float segmentPx() {
		return SEG_PX;
	}
}
