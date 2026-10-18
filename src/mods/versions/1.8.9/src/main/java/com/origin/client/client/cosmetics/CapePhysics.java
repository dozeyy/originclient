package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

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
 * How it plugs in: CapePhysicsMixin cancels vanilla's LayerCape.doRenderLayer
 * only for players wearing an ORIGIN cape (vanilla/Mojang/OptiFine capes keep
 * vanilla physics) and calls {@link #render}. Everything vanilla decides stays
 * the same: player info, invisible, the "Cape" skin option, the crouch offset
 * of the cloth (1.8.9: ModelPlayer.setRotationAngles' bipedCape.rotationPointY;
 * there is no elytra and no chestplate shift on 1.8.9).
 * {@link #render} returns false whenever it can't draw -- the mixin then lets
 * vanilla run, so any failure degrades to the normal cape.
 *
 * Simulation: one step per game tick (20 Hz, 4 sub-steps for stability),
 * state kept per player entity in an identity map (stale entries are swept). Drawing interpolates the last two steps with the
 * frame's partial tick, so high FPS is smooth, never steppy.
 *
 * Angle convention = vanilla's: pitch is the X rotation (degrees) that swings
 * the cape's bottom backwards, roll the Z rotation that swings it sideways
 * (positive = towards the wearer's right, as vanilla's `l`). Values are
 * CUMULATIVE down the chain; the renderer turns them into per-joint bends.
 *
 * 1.8.9: the same sim as 1.21.1; drawing is the GL matrix stack (GlStateManager
 * translate/rotate in LayerCape's own order) and N ModelRenderer bands on the
 * texture LayerCape would bind (getLocationCape, which CosmeticCapeMixin points
 * at the Origin cape). The maths helpers are MathHelper's / plain Math.
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
			this.phase = (p.getUniqueID().hashCode() & 0xFFFF) / 65535f * (float) (Math.PI * 2);
		}
	}

	// By identity, not Entity.equals (entity id): a menu preview or fake player sharing a
	// real player's id must not share (and snap) that player's cape. sweep() drops unseen ones.
	private static final Map<AbstractClientPlayer, State> STATES = new IdentityHashMap<>();
	private static long lastSweepMs = 0;

	// The N segment models, built once. null = couldn't be built: physics stays
	// off for the session and vanilla's cape draws.
	private static ModelRenderer[] segments;
	private static boolean segmentsTried = false;

	private static boolean ensureSegments() {
		if (segments != null) {
			return true;
		}
		if (segmentsTried) {
			return false;
		}
		segmentsTried = true;
		try {
			// Same layout as vanilla's cloak (a 10 x 16 x 1 box on the 64 x 32 cape
			// texture) cut into N horizontal bands. Band i takes texture rows
			// SEG_PX*i .. +SEG_PX of the outside face: a box's side faces start one
			// row below its texture offset v (the top/bottom strip comes first), so v = SEG_PX*i.
			// 1.8.9: ModelRenderer(ModelBase, u, v) + addBox is the cube builder; the
			// owner model only carries the 64 x 32 texture size (ModelBase's default).
			ModelBase owner = new ModelBase() {
			};
			ModelRenderer[] built = new ModelRenderer[N];
			for (int i = 0; i < N; i++) {
				built[i] = new ModelRenderer(owner, 0, Math.round(SEG_PX * i)).setTextureSize(64, 32);
				built[i].addBox(-5f, 0f, -1f, 10, Math.round(SEG_PX), 1);
			}
			segments = built;
			return true;
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin cosmetics: cape physics model failed to build; using vanilla capes", t);
			return false;
		}
	}

	/**
	 * Draws `p`'s Origin cape with physics, replacing vanilla's LayerCape.doRenderLayer.
	 *
	 * @return true if the cape was drawn (the mixin cancels vanilla); false to
	 *         let vanilla draw (physics off, not an Origin cape, no cape to
	 *         show, far away, or anything went wrong).
	 */
	public static boolean render(AbstractClientPlayer p, float partialTick, float ageInTicks) {
		if (!Mods.bool(Mods.GENERAL_ID, "capePhysics")) {
			return false;
		}
		// 1.8.9: LayerCape's own gate (player info, invisible, the "Cape" skin option)
		if (!p.hasPlayerInfo() || p.isInvisible() || !p.isWearing(EnumPlayerModelParts.CAPE)) {
			return false;
		}
		CosmeticsService.Look look = CosmeticsService.lookOf(p.getUniqueID());
		String id = look.cape();
		if (id == null) {
			return false;   // vanilla / Mojang / OptiFine cape: untouched
		}
		if (look.hidesCape()) {
			return true;    // wings / a backpack replace the cape: draw nothing, and keep vanilla from drawing it
		}
		ResourceLocation tex = p.getLocationCape();
		if (tex == null || !ensureSegments()) {
			return false;
		}

		State s = STATES.get(p);
		if (s == null) {
			s = new State(p);
			STATES.put(p, s);
		}
		long now = System.currentTimeMillis();
		s.lastSeenMs = now;
		advance(s, p);
		sweep(now);

		// 1.8.9: ModelPlayer.setRotationAngles' cloak offset is rotationPointY = 2 px when
		// sneaking, nothing else (no chestplate shift); applied in the cape's own frame.
		float cy = p.isSneaking() ? 2f : 0f;
		float cz = 0f;

		float t = ageInTicks / 20f;
		float calm = lerp(partialTick, s.calmPrev, s.calm);
		Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
		GlStateManager.color(1f, 1f, 1f, 1f);

		GlStateManager.pushMatrix();
		try {
			GlStateManager.translate(0f, 0f, 0.125f + look.capeOffset() / 16f);   // vanilla: 2 px behind the body (+ clearance for worn items)
			float prevP = 0f;
			float prevR = 0f;
			for (int i = 0; i < N; i++) {
				float cumP = lerp(partialTick, s.pitchPrev[i], s.pitch[i])
						+ calm * 1.4f * (1f + 0.35f * i) * MathHelper.sin(t * 1.9f + s.phase - i * 0.9f);
				float cumR = lerp(partialTick, s.rollPrev[i], s.roll[i])
						+ calm * 1.2f * (1f + 0.5f * i) * MathHelper.sin(t * 1.3f + s.phase * 1.7f - i * 0.7f);
				cumP = clamp(cumP, MIN_PITCH, MAX_PITCH);
				cumR = clamp(cumR, -MAX_ROLL, MAX_ROLL);
				float relP;
				float relR;
				if (i == 0) {
					relP = cumP;
					relR = cumR;
				} else {
					// Keep the joint from folding: limit the bend, and carry the limit down the chain.
					relP = clamp(cumP - prevP, -MAX_BEND, MAX_BEND);
					relR = clamp(cumR - prevR, -MAX_BEND, MAX_BEND);
					cumP = prevP + relP;
					cumR = prevR + relR;
				}
				if (i > 0) {
					GlStateManager.translate(0f, SEG_PX / 16f, 0f);   // down to this joint
				}
				GlStateManager.rotate(relP, 1f, 0f, 0f);
				GlStateManager.rotate(relR, 0f, 0f, 1f);
				if (i == 0) {
					// Vanilla applies the cloak offset after its rotations, in the cape's
					// own (Y-flipped) frame, so z is mirrored here, before the flip.
					GlStateManager.translate(0f, cy / 16f, -cz / 16f);
				}
				GlStateManager.pushMatrix();
				try {
					GlStateManager.rotate(180f - cumR, 0f, 1f, 0f);
					segments[i].render(0.0625F);
				} finally {
					GlStateManager.popMatrix();   // the stack must balance even if a segment throws
				}
				prevP = cumP;
				prevR = cumR;
			}
		} finally {
			GlStateManager.popMatrix();
		}
		return true;
	}

	// ------------------------------------------------------------------ the sim

	/** Brings the state up to this game tick (a no-op when already there). */
	private static void advance(State s, AbstractClientPlayer p) {
		int tick = p.ticksExisted;
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
		// 1.8.9: posX - prevPosX is 1.21.1's getX() - xo
		double vx = p.posX - p.prevPosX;
		double vy = p.posY - p.prevPosY;
		double vz = p.posZ - p.prevPosZ;
		if (vx * vx + vz * vz > 9.0 || Math.abs(vy) > 3.0) {
			vx = 0;
			vy = 0;
			vz = 0;   // a teleport, not a run
		}
		float yaw = (float) Math.toRadians(p.renderYawOffset);
		float sin = MathHelper.sin(yaw);
		float cos = MathHelper.cos(yaw);
		// Vanilla's cloak lags the body by ~3 ticks of travel (xCloak += 0.25 * gap);
		// x300 = its x100 on that lag. These are vanilla's own k / l / j terms.
		float forward = (float) (-vx * sin + vz * cos) * 300f;
		float lateral = (float) (vx * cos + vz * sin) * 300f;
		float rise = clamp((float) -vy * 30f, -6f, 32f);
		float speedAngle = clamp(forward, 0f, 150f) * 0.5f;
		float sway = clamp(lateral, -20f, 20f) * 0.5f;
		// Turning: the hem swings to the side you turn towards (it stays where it was
		// while the body rotates away from it).
		float turn = MathHelper.wrapAngleTo180_float(p.renderYawOffset - p.prevRenderYawOffset);
		sway += clamp(turn * 0.25f, -5f, 5f);

		float base = 6f + speedAngle + rise + (p.isSneaking() ? 25f : 0f);
		float flare = (speedAngle + Math.max(rise, 0f)) * 0.10f;   // the hem streams out a little more
		for (int i = 0; i < N; i++) {
			s.tPitch[i] = clamp(base + flare * i, MIN_PITCH, MAX_PITCH);
			s.tRoll[i] = clamp(sway * (1f + 0.35f * i), -MAX_ROLL, MAX_ROLL);
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
				v[i] = clamp(v[i] + a * h, -900f, 900f);
			}
			for (int i = 0; i < N; i++) {
				x[i] = clamp(x[i] + v[i] * h, -limit, limit);
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

	/**
	 * True between CapePhysicsMixin's HEAD push (vanilla cape drawn with an extra
	 * offset) and its matching RETURN pop. Render thread only.
	 * 1.8.9: kept here, not as a mutable static inside the mixin (Mixin 0.7.11).
	 */
	public static boolean offsetPushed = false;

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
			out[i] = clamp(s.pitch[i], MIN_PITCH, MAX_PITCH);
		}
		return true;
	}

	/** Segment height in skin pixels (for {@link #shape} users). */
	static float segmentPx() {
		return SEG_PX;
	}

	// 1.8.9: MathHelper has no float lerp (and spells clamp clamp_float); one obvious local pair.
	private static float lerp(float k, float a, float b) {
		return a + k * (b - a);
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}
}
