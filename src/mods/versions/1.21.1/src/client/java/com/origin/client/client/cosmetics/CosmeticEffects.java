package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import com.origin.client.client.cosmetics.CosmeticEffectDefs.Anchor;
import com.origin.client.client.cosmetics.CosmeticEffectDefs.Spec;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
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
 * In-game sparkles for cosmetics, matching the store's previews (the recipes
 * live in {@link CosmeticEffectDefs}, one table; this class knows no item).
 *
 * Purely cosmetic and client-side: small camera-facing squares (with a faint
 * halo) drawn into the world on Origin's own overlay quad surface
 * ({@link OriginRenderTypes}, depth-tested so walls hide them), never
 * anything that changes what a player sees of the game. Cheap by design:
 *  - at most {@link #PER_PLAYER_CAP}-ish particles per player and
 *    {@link #GLOBAL_CAP} in total, nearer players first;
 *  - nothing for players beyond {@link #MAX_DISTANCE} blocks, invisible or
 *    spectating players, players swimming/gliding/sleeping, an elytra'd or
 *    hidden cape, F1 (hidden HUD), or your OWN cosmetics in first person;
 *  - no per-particle objects beyond one small record; a particle's position is
 *    a closed-form function of its age, so there is no per-tick simulation and
 *    the motion is exact at any frame rate.
 *
 * Setting: SETTINGS > General > Cosmetic Effects ("cosmeticEffects", default on).
 * Everything is fail-soft: an error in a frame just skips the sparkles.
 */
public final class CosmeticEffects {
	private CosmeticEffects() {
	}

	/** Players farther than this get no sparkles (they'd be a speck anyway). */
	private static final double MAX_DISTANCE = 40.0;
	private static final int GLOBAL_CAP = 260;
	private static final float PER_PLAYER_SLACK = 1.25f;
	/** The website sizes particles for a 16-px-wide cape; in game a little bigger reads better. */
	private static final float GAME_SIZE_SCALE = 1.5f;

	/** One live particle. Position = origin + velocity * age + sway * sin(life phase). */
	private static final class Particle {
		double ox, oy, oz;     // where it was born (world)
		float vx, vy, vz;      // drift, blocks per tick
		float sx, sz;          // sway amplitude (blocks) along the horizontal axes
		float phase;           // sway phase offset
		double born;           // game time (ticks, with partial) at birth
		float life;            // ticks
		float r, g, b;         // colour 0..1
		float size;            // blocks, side of the square
		float glow;            // halo strength (recipe's glow)
	}

	private static final class Emitter {
		final List<Particle> live = new ArrayList<>();
		float accCape, accHead;   // fractional spawns carried between frames
		long frame;               // last frame this player was handled
		boolean emitting;         // wearing something that sparkles right now
		int colorCursor;
	}

	private static final Map<UUID, Emitter> EMITTERS = new HashMap<>();
	private static ClientLevel lastLevel;
	private static double lastTime = Double.NaN;
	private static long frameNo = 0;

	// Reused per frame: no allocation in the draw loop.
	private static final Vector3f CAM_RIGHT = new Vector3f();
	private static final Vector3f CAM_UP = new Vector3f();
	private static final float[] SHAPE = new float[CapePhysics.N];
	private static boolean warned = false;

	/** Hooks the world-render pass. Called once from OriginClientMod. */
	public static void register() {
		WorldRenderEvents.LAST.register(ctx -> {
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
		PoseStack stack = ctx.matrixStack();
		if (stack == null || !Mods.bool(Mods.GENERAL_ID, "cosmeticEffects")) {
			if (!EMITTERS.isEmpty()) {
				EMITTERS.clear();
			}
			return;
		}
		if (mc.options.hideGui) {
			return;   // F1: nothing drawn (and nothing born), existing sparks just age out
		}

		float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
		double now = level.getGameTime() + partial;
		double dt = Double.isNaN(lastTime) ? 0.0 : now - lastTime;
		if (dt < 0.0 || dt > 10.0) {
			dt = 0.0;   // paused, loaded a world, or a long hitch
		}
		lastTime = now;
		frameNo++;

		Camera cam = ctx.camera();
		Vec3 cp = cam.getPosition();
		Quaternionf rot = cam.rotation();
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
			Spec capeSpec = capeVisible(p) ? CosmeticEffectDefs.get(look.cape()) : null;
			Spec headSpec = CosmeticEffectDefs.get(look.head());
			Emitter em = EMITTERS.get(p.getUUID());
			if (capeSpec == null && headSpec == null) {
				if (em != null) {
					em.frame = frameNo;   // took the item off: let what is left age out
					em.emitting = false;
				}
				continue;
			}
			if (em == null) {
				em = new Emitter();
				EMITTERS.put(p.getUUID(), em);
			}
			em.frame = frameNo;
			em.emitting = true;
			if (capeSpec != null) {
				total += emit(em, p, capeSpec, Anchor.CAPE, dt, now, partial, total);
			}
			if (headSpec != null) {
				total += emit(em, p, headSpec, Anchor.HEAD, dt, now, partial, total);
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
		OriginRenderTypes.draw();
	}

	/** Spawns this frame's share of one recipe; returns how many it made. */
	private static int emit(Emitter em, AbstractClientPlayer p, Spec spec, Anchor anchor, double dt, double now,
			float partial, int totalNow) {
		float acc = (anchor == Anchor.CAPE ? em.accCape : em.accHead) + (float) dt * spec.perSecond() / 20f;
		int mine = em.live.size();
		int cap = Math.round(spec.count() * PER_PLAYER_SLACK);
		int made = 0;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		while (acc >= 1f) {
			acc -= 1f;
			if (mine + made >= cap || totalNow + made >= GLOBAL_CAP) {
				acc = Math.min(acc, 1f);   // full: don't bank a burst
				continue;
			}
			Particle pt = spawn(p, spec, anchor, now, partial, em.colorCursor++, rnd);
			if (pt != null) {
				em.live.add(pt);
				made++;
			}
		}
		if (anchor == Anchor.CAPE) {
			em.accCape = acc;
		} else {
			em.accHead = acc;
		}
		return made;
	}

	private static Particle spawn(AbstractClientPlayer p, Spec spec, Anchor anchor, double now, float partial,
			int colorIndex, ThreadLocalRandom rnd) {
		// The player as drawn this frame (interpolated), in blocks.
		double px = Mth.lerp(partial, p.xo, p.getX());
		double py = Mth.lerp(partial, p.yo, p.getY());
		double pz = Mth.lerp(partial, p.zo, p.getZ());
		float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot) * Mth.DEG_TO_RAD;
		// Body axes on the ground plane: forward (where it faces), right (its right hand).
		float fx = -Mth.sin(bodyYaw), fz = Mth.cos(bodyYaw);
		float rx = -Mth.cos(bodyYaw), rz = -Mth.sin(bodyYaw);
		boolean crouch = p.getPose() == Pose.CROUCHING;
		float shoulder = (crouch ? 24f - 3.2f : 24f) / 16f;   // blocks above the feet

		double x;
		double y;
		double z;
		float vx;
		float vz;
		float swayX;
		float swayZ;
		float life = (spec.lifeMinSec() + rnd.nextFloat() * (spec.lifeMaxSec() - spec.lifeMinSec())) * 20f;
		float lifeSafe = Math.max(4f, life);

		if (anchor == Anchor.CAPE) {
			float across = (rnd.nextFloat() * 2f - 1f) * spec.widthPx();
			float along = spec.fromPx() + rnd.nextFloat() * (spec.toPx() - spec.fromPx());
			// Walk down the cape's current shape (a hanging cape if it isn't simulated).
			boolean sim = CapePhysics.shape(p, SHAPE);
			float seg = CapePhysics.segmentPx();
			float back = 2f;      // px behind the body centre at the shoulders
			float down = 0f;      // px below the shoulders
			float left = along;
			float nB = 1f, nU = 0f;   // outward normal: (backwards, upwards)
			for (int i = 0; i < CapePhysics.N && left > 0f; i++) {
				float ang = (sim ? SHAPE[i] : 6f) * Mth.DEG_TO_RAD;
				float use = Math.min(seg, left);
				back += Mth.sin(ang) * use;
				down += Mth.cos(ang) * use;
				left -= use;
				nB = Mth.cos(ang);
				nU = -Mth.sin(ang);
			}
			back += nB * spec.standoffPx();
			down -= nU * spec.standoffPx();
			float backBlocks = back / 16f;
			x = px - fx * backBlocks + rx * (across / 16f);
			z = pz - fz * backBlocks + rz * (across / 16f);
			y = py + shoulder - down / 16f;
			float away = spec.driftBackPx() / 16f / lifeSafe;
			vx = -fx * away;
			vz = -fz * away;
			swayX = rx * spec.wobblePx() / 16f;
			swayZ = rz * spec.wobblePx() / 16f;
		} else {
			// A point on the square ring (half-side widthPx) at a height between from/to.
			float side = rnd.nextInt(4) * (Mth.PI * 0.5f);
			float t = (rnd.nextFloat() - 0.5f) * 2f * spec.widthPx() * 0.9f;
			float lx = t * Mth.cos(side) + spec.widthPx() * Mth.sin(side);
			float lz = spec.widthPx() * Mth.cos(side) - t * Mth.sin(side);
			float headYaw = Mth.rotLerp(partial, p.yHeadRotO, p.yHeadRot) * Mth.DEG_TO_RAD;
			float hs = Mth.sin(headYaw), hc = Mth.cos(headYaw);
			double wx = (lx * hc - lz * hs) / 16f;
			double wz = (lx * hs + lz * hc) / 16f;
			float h = spec.fromPx() + rnd.nextFloat() * (spec.toPx() - spec.fromPx());
			x = px + wx;
			z = pz + wz;
			y = py + (h - (crouch ? 3.2f : 0f)) / 16f;
			float away = spec.driftBackPx() / 16f / lifeSafe;
			vx = -fx * away;
			vz = -fz * away;
			swayX = rx * spec.wobblePx() / 16f;
			swayZ = rz * spec.wobblePx() / 16f;
		}

		Particle pt = new Particle();
		pt.ox = x;
		pt.oy = y;
		pt.oz = z;
		pt.vx = vx;
		pt.vy = spec.driftUpPx() / 16f / lifeSafe;
		pt.vz = vz;
		pt.sx = swayX;
		pt.sz = swayZ;
		pt.phase = rnd.nextFloat() * Mth.TWO_PI;
		pt.born = now;
		pt.life = lifeSafe;
		int rgb = spec.colors()[Math.floorMod(colorIndex, spec.colors().length)];
		pt.r = ((rgb >> 16) & 0xFF) / 255f;
		pt.g = ((rgb >> 8) & 0xFF) / 255f;
		pt.b = (rgb & 0xFF) / 255f;
		pt.glow = spec.glow();
		pt.size = spec.sizePx() / 16f * GAME_SIZE_SCALE * (0.85f + rnd.nextFloat() * 0.3f);
		return pt;
	}

	/** Draws one particle: a bright square and a faint halo behind it. */
	private static void draw(VertexConsumer q, PoseStack.Pose pose, Particle pt, float age, Vec3 cam) {
		float frac = age / pt.life;
		float k = Mth.sin(frac * Mth.PI);   // fade in, fade out (website: sin(life * pi))
		if (k < 0.04f) {
			return;
		}
		float sway = Mth.sin(frac * Mth.TWO_PI + pt.phase);
		float wx = (float) (pt.ox + pt.vx * age + pt.sx * sway - cam.x);
		float wy = (float) (pt.oy + pt.vy * age - cam.y);
		float wz = (float) (pt.oz + pt.vz * age + pt.sz * sway - cam.z);
		float h = pt.size * 0.5f;
		quad(q, pose, wx, wy, wz, h, pt.r, pt.g, pt.b, 0.95f * k);
		if (pt.glow > 0f) {
			quad(q, pose, wx, wy, wz, h * 2.4f, pt.r, pt.g, pt.b, pt.glow * k);
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
	private static boolean capeVisible(AbstractClientPlayer p) {
		return p.isModelPartShown(PlayerModelPart.CAPE) && !p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
	}

	private static void reset() {
		EMITTERS.clear();
		lastTime = Double.NaN;
	}
}
