package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-game sparkles for cosmetics, matching the store's previews. The recipes
 * are each item's `fx` list in its item.json (FORMAT.md "Particles"; model px
 * and seconds); this class knows no item by name.
 *
 * An fx entry lives in the frame of a model bone (`bone`), which rides the
 * head (hats, masks) or the body (capes, wings, backpacks). For a cape, which
 * has no model, `bone` is just the body frame. `at` and `spread` are px in that
 * bone's frame, `rise` is px/s straight up, `drift` is px/s in the attach
 * frame (+z = the player's front), as in website/js/rig.js.
 *
 * Purely cosmetic and client-side: small camera-facing squares (with a faint
 * halo for `glow`) drawn into the world on Origin's own overlay quad surface
 * ({@link OriginRenderTypes}, depth-tested so walls hide them), never
 * anything that changes what a player sees of the game. Cheap by design:
 *  - at most a few dozen particles per player and {@link #GLOBAL_CAP} in
 *    total;
 *  - nothing for players beyond {@link #MAX_DISTANCE} blocks, invisible or
 *    spectating players, players swimming/gliding/sleeping, an elytra'd or
 *    hidden cape, F1 (hidden HUD), or your OWN cosmetics in first person;
 *  - no per-particle objects beyond one small record; a particle's position is
 *    a closed-form function of its age, so there is no per-tick simulation and
 *    the motion is exact at any frame rate. It is born where the bone is and
 *    then stays in the world, so a moving player leaves a trail.
 *
 * Setting: SETTINGS > General > Cosmetic Effects ("cosmeticEffects", default on).
 * Everything is fail-soft: an error in a frame just skips the sparkles.
 *
 * PER-VERSION DELTA (1.21.11, 1.21.9+ era): the world pass is Fabric's
 * WorldRenderEvents.END_MAIN (there is no LAST), drawing with ctx.matrices()
 * (identity; the model-view already holds the camera rotation, as CuiRenderer
 * notes) and the camera position + orientation from
 * worldState().cameraRenderState (Camera.getPosition()/rotation() are gone);
 * the partial tick comes from the DeltaTracker.
 */
public final class CosmeticEffects {
	private CosmeticEffects() {
	}

	/** Players farther than this get no sparkles (they'd be a speck anyway). */
	private static final double MAX_DISTANCE = 40.0;
	private static final int GLOBAL_CAP = 260;
	/** The website sizes particles for a 16-px-wide cape; in game a little bigger reads better. */
	private static final float GAME_SIZE_SCALE = 1.5f;

	/** One live particle. Position = origin + velocity * age. */
	private static final class Particle {
		double ox, oy, oz;     // where it was born (world)
		float vx, vy, vz;      // blocks per tick
		double born;           // game time (ticks, with partial) at birth
		float life;            // ticks
		float r, g, b;         // colour 0..1
		float size;            // blocks, side of the square
		boolean glow;          // faint halo behind it
	}

	private static final int FX_SLOTS = 8;   // CosmeticDef.MAX_FX

	private static final class Emitter {
		final List<Particle> live = new ArrayList<>();
		/** fractional spawns carried between frames, per (slot, fx index) */
		final float[] acc = new float[Cosmetics.Slot.values().length * FX_SLOTS];
		long frame;               // last frame this player was handled
		boolean emitting;         // wearing something that sparkles right now
	}

	private static final Map<UUID, Emitter> EMITTERS = new HashMap<>();
	private static ClientLevel lastLevel;
	private static double lastTime = Double.NaN;
	private static long frameNo = 0;

	// Reused per frame: no allocation in the draw loop.
	private static final Vector3f CAM_RIGHT = new Vector3f();
	private static final Vector3f CAM_UP = new Vector3f();
	private static final Vector3f TMP = new Vector3f();
	private static boolean warned = false;

	/** Hooks the world-render pass. Called once from OriginClientMod. */
	public static void register() {
		WorldRenderEvents.END_MAIN.register(ctx -> {
			try {
				render(ctx);
			} catch (Throwable t) {
				if (!warned) {
					warned = true;
					OriginClient.LOGGER.warn("Origin cosmetics: sparkles failed to draw; skipping them", t);
				}
			}
		});
	}

	private static void render(WorldRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			reset();
			return;
		}
		if (level != lastLevel) {
			reset();
			lastLevel = level;
			CapePhysics.clear();
		}
		PoseStack stack = ctx.matrices();
		if (stack == null || !Mods.bool(Mods.GENERAL_ID, "cosmeticEffects")) {
			if (!EMITTERS.isEmpty()) {
				EMITTERS.clear();
			}
			return;
		}
		if (mc.options.hideGui) {
			return;   // F1: nothing drawn (and nothing born), existing sparks just age out
		}

		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double now = level.getGameTime() + partial;
		double dt = Double.isNaN(lastTime) ? 0.0 : now - lastTime;
		if (dt < 0.0 || dt > 10.0) {
			dt = 0.0;   // paused, loaded a world, or a long hitch
		}
		lastTime = now;
		frameNo++;

		var camState = ctx.worldState().cameraRenderState;
		Vec3 cp = camState.pos;
		Quaternionf rot = new Quaternionf(camState.orientation);
		CAM_RIGHT.set(1f, 0f, 0f).rotate(rot);
		CAM_UP.set(0f, 1f, 0f).rotate(rot);

		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		double maxD2 = MAX_DISTANCE * MAX_DISTANCE;

		// Count what is alive so the global cap holds, nearest players served first by list order only.
		int total = 0;
		for (Emitter e : EMITTERS.values()) {
			total += e.live.size();
		}

		for (AbstractClientPlayer p : level.players()) {
			if (!eligible(p, mc, cp, maxD2, firstPerson)) {
				continue;   // its emitter (if any) is not touched this frame, so it is swept below
			}
			CosmeticsService.Look look = CosmeticsService.lookOf(p.getUUID());
			boolean elytra = p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
			boolean capeShown = capeVisible(p, elytra) && !look.hidesCape();
			Emitter em = EMITTERS.get(p.getUUID());
			int sparkling = 0;
			for (Cosmetics.Slot slot : SPARKLY_SLOTS) {
				if ((slot == Cosmetics.Slot.CAPE && !capeShown) || (slot == Cosmetics.Slot.BACK && elytra)) {
					continue;
				}
				CosmeticDef def = CosmeticCatalog.def(look.get(slot));
				if (def != null && !def.fx.isEmpty()) {
					sparkling++;
					if (em == null) {
						em = new Emitter();
						EMITTERS.put(p.getUUID(), em);
					}
					em.frame = frameNo;
					em.emitting = true;
					total += emit(em, p, slot, def, dt, now, partial, total);
				}
			}
			if (sparkling == 0 && em != null) {
				em.frame = frameNo;   // took the item off: let what is left age out
				em.emitting = false;
			}
		}

		// Forget emitters of players who left the level / weren't handled this frame.
		for (Iterator<Emitter> it = EMITTERS.values().iterator(); it.hasNext();) {
			if (it.next().frame != frameNo) {
				it.remove();
			}
		}
		if (EMITTERS.isEmpty()) {
			return;
		}

		PoseStack.Pose pose = stack.last();
		VertexConsumer q = OriginRenderTypes.begin();
		try {
			for (Iterator<Emitter> eit = EMITTERS.values().iterator(); eit.hasNext();) {
				Emitter em = eit.next();
				for (Iterator<Particle> it = em.live.iterator(); it.hasNext();) {
					Particle pt = it.next();
					float age = (float) (now - pt.born);
					if (age >= pt.life) {
						it.remove();
					} else if (age >= 0f) {
						draw(q, pose, pt, age, cp);
					}
				}
				if (em.live.isEmpty() && !em.emitting) {
					eit.remove();
				}
			}
		} finally {
			OriginRenderTypes.draw();   // never leave half-built quads to flush into the next frame
		}
	}

	/** The slots that can carry fx (themes have no body). */
	private static final Cosmetics.Slot[] SPARKLY_SLOTS = {
			Cosmetics.Slot.CAPE, Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.BACK };

	/** Spawns this frame's share of one item's fx entries; returns how many it made. */
	private static int emit(Emitter em, AbstractClientPlayer p, Cosmetics.Slot slot, CosmeticDef def, double dt,
			double now, float partial, int totalNow) {
		boolean head = slot == Cosmetics.Slot.HEAD || slot == Cosmetics.Slot.FACE;
		Basis basis = null;
		ModelCosmetic model = null;
		float t = 0f, walk = 0f, air = 0f, shiftX = 0f, shiftY = 0f, shiftZ = 0f;
		int made = 0;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < def.fx.size() && i < FX_SLOTS; i++) {
			CosmeticDef.Fx fx = def.fx.get(i);
			int k = slot.ordinal() * FX_SLOTS + i;
			float acc = em.acc[k] + (float) dt * fx.perSecond() / 20f;
			// A steady state holds about perSecond * life particles; allow a little slack.
			int cap = Math.max(4, Math.round(fx.perSecond() * fx.lifeMax() * 1.25f));
			while (acc >= 1f) {
				acc -= 1f;
				if (em.live.size() >= Math.min(cap * 2, 160) || totalNow + made >= GLOBAL_CAP) {
					acc = Math.min(acc, 1f);   // full: don't bank a burst
					continue;
				}
				if (basis == null) {
					basis = basisOf(p, head, partial);
					CosmeticsService.Look look = CosmeticsService.lookOf(p.getUUID());
					model = ModelCosmetic.of(look.get(slot));
					// sparks follow the item when the player moved it (the outer-layer fit's half px is left out)
					shiftX = look.adjust(slot, CosmeticsService.Adjust.Axis.X);
					shiftY = look.adjust(slot);
					shiftZ = look.adjust(slot, CosmeticsService.Adjust.Axis.Z);
					CosmeticLayer.Feel feel = CosmeticLayer.feelOf(p);
					t = (p.tickCount + partial) / 20f;
					walk = feel.walk;
					air = feel.air;
				}
				Particle pt = spawn(fx, basis, model, t, walk, air, shiftX, shiftY, shiftZ, now, rnd);
				if (pt != null) {
					em.live.add(pt);
					made++;
				}
			}
			em.acc[k] = acc;
		}
		return made;
	}

	/**
	 * A player's attach frame in the world: origin (blocks), the horizontal
	 * unit vectors for the player's left (+x of FORMAT) and front (+z), and the
	 * tilt of the frame about its left axis (head pitch / crouch lean).
	 */
	private static final class Basis {
		double ox, oy, oz;
		float leftX, leftZ, fwdX, fwdZ;
		float cosT, sinT;
	}

	private static Basis basisOf(AbstractClientPlayer p, boolean head, float partial) {
		Basis b = new Basis();
		b.ox = Mth.lerp(partial, p.xo, p.getX());
		double py = Mth.lerp(partial, p.yo, p.getY());
		b.oz = Mth.lerp(partial, p.zo, p.getZ());
		boolean crouch = p.getPose() == Pose.CROUCHING;
		// Pivot heights in px above the feet: the neck and the top of the torso (vanilla: head.y 4.2 and
		// body.y 3.2 lower while crouching, plus the renderer's own 2 px drop).
		b.oy = py + (24f - (crouch ? (head ? 6.2f : 5.2f) : 0f)) / 16f;
		float yaw = (head ? Mth.rotLerp(partial, p.yHeadRotO, p.yHeadRot) : Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot))
				* Mth.DEG_TO_RAD;
		b.fwdX = -Mth.sin(yaw);
		b.fwdZ = Mth.cos(yaw);
		b.leftX = Mth.cos(yaw);
		b.leftZ = Mth.sin(yaw);
		float tilt = head ? Mth.lerp(partial, p.xRotO, p.getXRot()) * Mth.DEG_TO_RAD : (crouch ? 0.5f : 0f);
		b.cosT = Mth.cos(tilt);
		b.sinT = Mth.sin(tilt);
		return b;
	}

	/** FORMAT vector (px) in the attach frame -> world offset, in blocks. */
	private static void toWorld(Basis b, float x, float y, float z, double[] out) {
		float up = y * b.cosT - z * b.sinT;
		float fwd = y * b.sinT + z * b.cosT;
		out[0] = (b.leftX * x + b.fwdX * fwd) / 16.0;
		out[1] = up / 16.0;
		out[2] = (b.leftZ * x + b.fwdZ * fwd) / 16.0;
	}

	private static final double[] W = new double[3];

	private static Particle spawn(CosmeticDef.Fx fx, Basis basis, ModelCosmetic model, float t, float walk, float air,
			float shiftX, float shiftY, float shiftZ, double now, ThreadLocalRandom rnd) {
		float lx = fx.at()[0] + (rnd.nextFloat() - 0.5f) * fx.spread()[0];
		float ly = fx.at()[1] + (rnd.nextFloat() - 0.5f) * fx.spread()[1];
		float lz = fx.at()[2] + (rnd.nextFloat() - 0.5f) * fx.spread()[2];
		if (model != null && model.pointInAttachFrame(fx.bone(), lx, ly, lz, t, walk, air, TMP)) {
			lx = TMP.x;
			ly = TMP.y;
			lz = TMP.z;
		}
		lx += shiftX;
		ly += shiftY;
		lz += shiftZ;
		toWorld(basis, lx, ly, lz, W);
		Particle pt = new Particle();
		pt.ox = basis.ox + W[0];
		pt.oy = basis.oy + W[1];
		pt.oz = basis.oz + W[2];
		// drift: px/s in the attach frame; rise: px/s straight up. Both -> blocks per tick.
		toWorld(basis, fx.drift()[0], fx.drift()[1], fx.drift()[2], W);
		float perTick = 1f / 20f;
		pt.vx = (float) W[0] * perTick;
		pt.vy = (float) W[1] * perTick + fx.rise() / 16f * perTick;
		pt.vz = (float) W[2] * perTick;
		pt.born = now;
		pt.life = Math.max(2f, (fx.lifeMin() + rnd.nextFloat() * (fx.lifeMax() - fx.lifeMin())) * 20f);
		float mix = rnd.nextFloat();
		int c1 = fx.color(), c2 = fx.color2();
		pt.r = Mth.lerp(mix, ((c1 >> 16) & 0xFF) / 255f, ((c2 >> 16) & 0xFF) / 255f);
		pt.g = Mth.lerp(mix, ((c1 >> 8) & 0xFF) / 255f, ((c2 >> 8) & 0xFF) / 255f);
		pt.b = Mth.lerp(mix, (c1 & 0xFF) / 255f, (c2 & 0xFF) / 255f);
		pt.glow = fx.glow();
		pt.size = fx.size() * (0.7f + 0.6f * rnd.nextFloat()) / 16f * GAME_SIZE_SCALE;
		return pt;
	}

	/** Draws one particle: a bright square and, for glow, a faint halo behind it. */
	private static void draw(VertexConsumer q, PoseStack.Pose pose, Particle pt, float age, Vec3 cam) {
		// website/js/rig.js: fade = min(1, age / 0.15 s) * (1 - age / life)
		float k = Math.min(1f, age / 3f) * (1f - age / pt.life);
		if (k < 0.04f) {
			return;
		}
		float wx = (float) (pt.ox + pt.vx * age - cam.x);
		float wy = (float) (pt.oy + pt.vy * age - cam.y);
		float wz = (float) (pt.oz + pt.vz * age - cam.z);
		float h = pt.size * 0.5f;
		quad(q, pose, wx, wy, wz, h, pt.r, pt.g, pt.b, 0.95f * k);
		if (pt.glow) {
			quad(q, pose, wx, wy, wz, h * 2.4f, pt.r, pt.g, pt.b, 0.25f * k);
		}
	}

	private static void quad(VertexConsumer q, PoseStack.Pose pose, float cx, float cy, float cz, float h,
			float r, float g, float b, float a) {
		float rx = CAM_RIGHT.x * h, ry = CAM_RIGHT.y * h, rz = CAM_RIGHT.z * h;
		float ux = CAM_UP.x * h, uy = CAM_UP.y * h, uz = CAM_UP.z * h;
		q.addVertex(pose, cx - rx - ux, cy - ry - uy, cz - rz - uz).setColor(r, g, b, a);
		q.addVertex(pose, cx - rx + ux, cy - ry + uy, cz - rz + uz).setColor(r, g, b, a);
		q.addVertex(pose, cx + rx + ux, cy + ry + uy, cz + rz + uz).setColor(r, g, b, a);
		q.addVertex(pose, cx + rx - ux, cy + ry - uy, cz + rz - uz).setColor(r, g, b, a);
	}

	// ---------------------------------------------------------------- rules

	/** Whether `p` may give off sparkles right now (distance, visibility, pose, first person). */
	private static boolean eligible(AbstractClientPlayer p, Minecraft mc, Vec3 cam, double maxD2, boolean firstPerson) {
		if (p.isRemoved() || p.isInvisible() || p.isSpectator()) {
			return false;
		}
		if (p == mc.player && firstPerson) {
			return false;   // you can't see your own back from inside your head
		}
		Pose pose = p.getPose();
		if (pose != Pose.STANDING && pose != Pose.CROUCHING) {
			return false;
		}
		double dx = p.getX() - cam.x, dy = p.getY() - cam.y, dz = p.getZ() - cam.z;
		return dx * dx + dy * dy + dz * dz <= maxD2;
	}

	/** Vanilla's cape rules: shown only with the Cape skin option on and no elytra. */
	static boolean capeVisible(AbstractClientPlayer p, boolean elytra) {
		return p.isModelPartShown(PlayerModelPart.CAPE) && !elytra;
	}

	private static void reset() {
		EMITTERS.clear();
		lastTime = Double.NaN;
	}
}
