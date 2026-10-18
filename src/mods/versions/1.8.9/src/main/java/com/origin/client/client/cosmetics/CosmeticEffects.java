package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MathHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
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
 * ({@link OriginRenderTypes}, depth-tested so walls hide them). Cheap by design:
 *  - at most a few dozen particles per player and {@link #GLOBAL_CAP} in total;
 *  - nothing for players beyond {@link #MAX_DISTANCE} blocks, invisible or
 *    spectating or sleeping players, a hidden cape, F1, or your OWN cosmetics
 *    in first person;
 *  - a particle's position is a closed-form function of its age, so there is
 *    no per-tick simulation and the motion is exact at any frame rate.
 *
 * Setting: SETTINGS > General > Cosmetic Effects ("cosmeticEffects", default on).
 * Everything is fail-soft: an error in a frame just skips the sparkles.
 *
 * 1.8.9: no Fabric WorldRenderEvents; {@link #renderWorld} is called from
 * SocialHooks' RenderWorldLastEvent, where the GL matrix is the camera's and
 * world coordinates are drawn relative to RenderManager.viewerPosX/Y/Z. There
 * is no elytra, swimming or gliding on 1.8.9, so those rules fall away.
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
		double born;           // world time (ticks, with partial) at birth
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
	private static WorldClient lastWorld;
	private static double lastTime = Double.NaN;
	private static long frameNo = 0;

	// Reused per frame: no allocation in the draw loop.
	// 1.8.9: no joml / Camera.rotation(); the camera's right and up axes are read
	// off the GL model-view matrix (as TeamView does), which also follows view bobbing.
	private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
	private static float rightX, rightY, rightZ, upX, upY, upZ;
	private static final float[] TMP = new float[3];
	private static boolean warned = false;

	/** Draws this frame's sparkles. Called from SocialHooks' RenderWorldLastEvent; never throws. */
	public static void renderWorld(float partialTicks) {
		try {
			render(partialTicks);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: sparkles failed to draw; skipping them", t);
			}
		}
	}

	private static void render(float partial) {
		Minecraft mc = Minecraft.getMinecraft();
		WorldClient world = mc.theWorld;
		if (world == null || mc.thePlayer == null) {
			reset();
			lastWorld = null;
			return;
		}
		if (world != lastWorld) {
			reset();
			lastWorld = world;
			CapePhysics.clear();
		}
		if (!Mods.bool(Mods.GENERAL_ID, "cosmeticEffects")) {
			if (!EMITTERS.isEmpty()) {
				EMITTERS.clear();
			}
			return;
		}
		if (mc.gameSettings.hideGUI) {
			return;   // F1: nothing drawn (and nothing born), existing sparks just age out
		}

		double now = world.getTotalWorldTime() + partial;
		double dt = Double.isNaN(lastTime) ? 0.0 : now - lastTime;
		if (dt < 0.0 || dt > 10.0) {
			dt = 0.0;   // paused, loaded a world, or a long hitch
		}
		lastTime = now;
		frameNo++;

		RenderManager rm = mc.getRenderManager();
		double camX = rm.viewerPosX, camY = rm.viewerPosY, camZ = rm.viewerPosZ;
		boolean firstPerson = mc.gameSettings.thirdPersonView == 0;
		double maxD2 = MAX_DISTANCE * MAX_DISTANCE;

		int total = 0;
		for (Emitter e : EMITTERS.values()) {
			total += e.live.size();
		}

		for (EntityPlayer ep : world.playerEntities) {
			if (!(ep instanceof AbstractClientPlayer)) {
				continue;
			}
			AbstractClientPlayer p = (AbstractClientPlayer) ep;
			if (!eligible(p, mc, camX, camY, camZ, maxD2, firstPerson)) {
				continue;   // its emitter (if any) is not touched this frame, so it is swept below
			}
			CosmeticsService.Look look = CosmeticsService.lookOf(p.getUniqueID());
			boolean capeShown = capeVisible(p) && !look.hidesCape();
			Emitter em = EMITTERS.get(p.getUniqueID());
			int sparkling = 0;
			for (Cosmetics.Slot slot : SPARKLY_SLOTS) {
				if (slot == Cosmetics.Slot.CAPE && !capeShown) {
					continue;
				}
				CosmeticDef def = CosmeticCatalog.def(look.get(slot));
				if (def != null && !def.fx.isEmpty()) {
					sparkling++;
					if (em == null) {
						em = new Emitter();
						EMITTERS.put(p.getUniqueID(), em);
					}
					em.frame = frameNo;
					em.emitting = true;
					total += emit(em, p, look, slot, def, dt, now, partial, total);
				}
			}
			if (sparkling == 0 && em != null) {
				em.frame = frameNo;   // took the item off: let what is left age out
				em.emitting = false;
			}
		}

		// Forget emitters of players who left the world / weren't handled this frame.
		for (Iterator<Emitter> it = EMITTERS.values().iterator(); it.hasNext();) {
			if (it.next().frame != frameNo) {
				it.remove();
			}
		}
		if (EMITTERS.isEmpty()) {
			return;
		}

		cameraAxes();
		WorldRenderer q = OriginRenderTypes.begin();
		// 1.8.9: the quad surface leaves the alpha test on (GREATER 0.1), which would cut
		// the faint halos and fade-outs; off here, and draw() then leaves it off, so we
		// turn it back on after.
		GlStateManager.disableAlpha();
		try {
			for (Iterator<Emitter> eit = EMITTERS.values().iterator(); eit.hasNext();) {
				Emitter em = eit.next();
				for (Iterator<Particle> it = em.live.iterator(); it.hasNext();) {
					Particle pt = it.next();
					float age = (float) (now - pt.born);
					if (age >= pt.life) {
						it.remove();
					} else if (age >= 0f) {
						draw(q, pt, age, camX, camY, camZ);
					}
				}
				if (em.live.isEmpty() && !em.emitting) {
					eit.remove();
				}
			}
		} finally {
			OriginRenderTypes.draw();   // never leave half-built quads to flush into the next frame
			GlStateManager.enableAlpha();
		}
	}

	/** The camera's right and up axes in world space: rows of the model-view rotation. */
	private static void cameraAxes() {
		MODELVIEW.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
		// column-major: row 0 = (m0, m4, m8), row 1 = (m1, m5, m9)
		rightX = MODELVIEW.get(0);
		rightY = MODELVIEW.get(4);
		rightZ = MODELVIEW.get(8);
		upX = MODELVIEW.get(1);
		upY = MODELVIEW.get(5);
		upZ = MODELVIEW.get(9);
	}

	/** The slots that can carry fx (themes have no body). */
	private static final Cosmetics.Slot[] SPARKLY_SLOTS = {
			Cosmetics.Slot.CAPE, Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.BACK };

	/** Spawns this frame's share of one item's fx entries; returns how many it made. */
	private static int emit(Emitter em, AbstractClientPlayer p, CosmeticsService.Look look, Cosmetics.Slot slot,
			CosmeticDef def, double dt, double now, float partial, int totalNow) {
		boolean head = slot == Cosmetics.Slot.HEAD || slot == Cosmetics.Slot.FACE;
		Basis basis = null;
		ModelCosmetic model = null;
		float t = 0f, walk = 0f, air = 0f;
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
					model = ModelCosmetic.of(look.get(slot));
					CosmeticLayer.Feel feel = CosmeticLayer.feelOf(p);
					t = (p.ticksExisted + partial) / 20f;
					walk = feel.walk;
					air = feel.air;
				}
				Particle pt = spawn(fx, basis, model, t, walk, air, now, rnd);
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
		// 1.8.9: lastTickPosX -> posX is 1.21.1's xo -> getX() (the render interpolation)
		b.ox = p.lastTickPosX + (p.posX - p.lastTickPosX) * partial;
		double py = p.lastTickPosY + (p.posY - p.lastTickPosY) * partial;
		b.oz = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partial;
		boolean crouch = p.isSneaking();
		// Pivot heights in px above the feet: the neck and the top of the torso.
		// 1.8.9: sneaking drops the whole model 3.2 px (ModelBiped's 0.2 translate) and
		// the head 1 px more (rotationPointY 1); RenderPlayer drops OTHER players another
		// 2 px (0.125 block), never you -- so 6.2 / 5.2 for others, 4.2 / 3.2 for you.
		float drop = crouch ? (head ? 4.2f : 3.2f) + (p instanceof EntityPlayerSP ? 0f : 2f) : 0f;
		b.oy = py + (24f - drop) / 16f;
		float yaw = (head ? rotLerp(partial, p.prevRotationYawHead, p.rotationYawHead)
				: rotLerp(partial, p.prevRenderYawOffset, p.renderYawOffset)) * DEG_TO_RAD;
		b.fwdX = -MathHelper.sin(yaw);
		b.fwdZ = MathHelper.cos(yaw);
		b.leftX = MathHelper.cos(yaw);
		b.leftZ = MathHelper.sin(yaw);
		float tilt = head ? lerp(partial, p.prevRotationPitch, p.rotationPitch) * DEG_TO_RAD : (crouch ? 0.5f : 0f);
		b.cosT = MathHelper.cos(tilt);
		b.sinT = MathHelper.sin(tilt);
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
			double now, ThreadLocalRandom rnd) {
		float lx = fx.at()[0] + (rnd.nextFloat() - 0.5f) * fx.spread()[0];
		float ly = fx.at()[1] + (rnd.nextFloat() - 0.5f) * fx.spread()[1];
		float lz = fx.at()[2] + (rnd.nextFloat() - 0.5f) * fx.spread()[2];
		if (model != null && model.pointInAttachFrame(fx.bone(), lx, ly, lz, t, walk, air, TMP)) {
			lx = TMP[0];
			ly = TMP[1];
			lz = TMP[2];
		}
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
		pt.r = lerp(mix, ((c1 >> 16) & 0xFF) / 255f, ((c2 >> 16) & 0xFF) / 255f);
		pt.g = lerp(mix, ((c1 >> 8) & 0xFF) / 255f, ((c2 >> 8) & 0xFF) / 255f);
		pt.b = lerp(mix, (c1 & 0xFF) / 255f, (c2 & 0xFF) / 255f);
		pt.glow = fx.glow();
		pt.size = fx.size() * (0.7f + 0.6f * rnd.nextFloat()) / 16f * GAME_SIZE_SCALE;
		return pt;
	}

	/** Draws one particle: a bright square and, for glow, a faint halo behind it. */
	private static void draw(WorldRenderer q, Particle pt, float age, double camX, double camY, double camZ) {
		// website/js/rig.js: fade = min(1, age / 0.15 s) * (1 - age / life)
		float k = Math.min(1f, age / 3f) * (1f - age / pt.life);
		if (k < 0.04f) {
			return;
		}
		double wx = pt.ox + pt.vx * age - camX;
		double wy = pt.oy + pt.vy * age - camY;
		double wz = pt.oz + pt.vz * age - camZ;
		float h = pt.size * 0.5f;
		quad(q, wx, wy, wz, h, pt.r, pt.g, pt.b, 0.95f * k);
		if (pt.glow) {
			quad(q, wx, wy, wz, h * 2.4f, pt.r, pt.g, pt.b, 0.25f * k);
		}
	}

	private static void quad(WorldRenderer q, double cx, double cy, double cz, float h, float r, float g, float b,
			float a) {
		float rx = rightX * h, ry = rightY * h, rz = rightZ * h;
		float ux = upX * h, uy = upY * h, uz = upZ * h;
		q.pos(cx - rx - ux, cy - ry - uy, cz - rz - uz).color(r, g, b, a).endVertex();
		q.pos(cx - rx + ux, cy - ry + uy, cz - rz + uz).color(r, g, b, a).endVertex();
		q.pos(cx + rx + ux, cy + ry + uy, cz + rz + uz).color(r, g, b, a).endVertex();
		q.pos(cx + rx - ux, cy + ry - uy, cz + rz - uz).color(r, g, b, a).endVertex();
	}

	// ---------------------------------------------------------------- rules

	/** Whether `p` may give off sparkles right now (distance, visibility, sleeping, first person). */
	private static boolean eligible(AbstractClientPlayer p, Minecraft mc, double camX, double camY, double camZ,
			double maxD2, boolean firstPerson) {
		if (p.isDead || p.isInvisible() || p.isSpectator() || p.isPlayerSleeping()) {
			return false;
		}
		if (p == mc.thePlayer && firstPerson) {
			return false;   // you can't see your own back from inside your head
		}
		double dx = p.posX - camX, dy = p.posY - camY, dz = p.posZ - camZ;
		return dx * dx + dy * dy + dz * dz <= maxD2;
	}

	/** Vanilla's cape rule: shown only with the Cape skin option on (1.8.9 has no elytra). */
	static boolean capeVisible(AbstractClientPlayer p) {
		return p.isWearing(EnumPlayerModelParts.CAPE);
	}

	private static void reset() {
		EMITTERS.clear();
		lastTime = Double.NaN;
	}

	// 1.8.9: MathHelper has no float lerp / rotLerp / DEG_TO_RAD; local copies.
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

	private static float lerp(float k, float a, float b) {
		return a + k * (b - a);
	}

	private static float rotLerp(float k, float a, float b) {
		return a + k * MathHelper.wrapAngleTo180_float(b - a);
	}
}
