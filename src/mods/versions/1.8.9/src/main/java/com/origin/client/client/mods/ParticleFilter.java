package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumParticleTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every Particle Changer rule in one place — asked once per spawn, from
 * {@code ParticleSpawnMixin} (EffectRenderer.spawnEffectParticle).
 *
 * The per-type rows and the global sliders all resolve here:
 *   row toggle / Hide Particle      -> drop the spawn
 *   Show on Self / Players / Entities -> drop when the spawn is on that kind of
 *                                      thing (proximity at spawn time — a
 *                                      particle carries no source entity)
 *   Multiplier (global x per-type)  -> &lt;1 drops a share of spawns, &gt;1 adds one
 *   Scale (per-type)                -> applied ONCE at spawn (EntityFX.multipleParticleScaleBy)
 *                                      (the global Scale slider stays a live
 *                                      per-frame multiply in ParticleScaleMixin
 *                                      so it previews while you drag it)
 *   Play Sound                      -> a short, rate-limited ding at the spawn
 *
 * 1.8.9: particles are int ids (EnumParticleTypes) with OLD names ("magicCrit",
 * "largeexplode", "iconcrack_"...). typePath() maps them to the modern id whose
 * "p_<id>" rows configure them (Mods.LEGACY_PARTICLE_IDS, the foundation's table),
 * so a player's per-particle settings mean the same thing on every version.
 * Particles with no modern counterpart follow only the global rows.
 *
 * Deliberately NOT a mixin class: mixin classes can't be referenced from normal
 * code, and several hooks need to call this.
 */
public final class ParticleFilter {

	/** How close a spawn must be to count as "on" a player/entity. */
	private static final double CONTEXT_RADIUS = 1.25;
	/** Floor between two dings of the same particle type — stops any machine-gunning. */
	private static final long SOUND_GAP_MS = 110L;

	private static final Map<String, Long> LAST_SOUND = new HashMap<>();

	/** Set while a Multiplier&gt;1 extra copy is being spawned, so it can't multiply again. */
	private static boolean spawningExtra;

	private ParticleFilter() {
	}

	// ---- schema-safe reads: unknown particle types have no rows, and must fall
	// back to "show, unchanged" rather than to Mods' zero defaults ----

	private static boolean flag(String key, boolean def) {
		return Mods.hasOption("particles", key) ? Mods.bool("particles", key) : def;
	}

	private static double slider(String key, double def) {
		if (!Mods.hasOption("particles", key)) {
			return def;
		}
		double v = Mods.num("particles", key);
		return v > 0 ? v : def;
	}

	// ---- per-config-epoch snapshot ----
	// Every spawn used to re-read ~12 config keys (string concat + monitor + map walks each); the settings only
	// change when the config epoch (ModsConfig.rev) moves, so they are resolved once per epoch. The same reads in
	// the same order feed the same checks, so behaviour is identical -- only the repeated lookups are gone.

	/** Everything the filter needs for one particle type, resolved once per epoch. */
	private static final class TypeInfo {
		final boolean hide;          // row toggle off, its Hide flag, or the block-break flag
		final boolean ctxAll;        // Show on Self/Players/Entities all ON: no entity sweep
		final boolean self, players, entities;
		final boolean reducedHides;  // dropped by mode "Reduced"
		final double multiplier;
		final double scale;          // per-type Scale (1 = untouched)
		final boolean sound;

		TypeInfo(String path) {
			boolean h = Mods.hasOption("particles", "p_" + path) && !Mods.bool("particles", "p_" + path);
			h = h || Mods.bool("particles", "p_" + path + "_hide");
			h = h || (path.equals("block") && Mods.bool("particles", "hideBlockBreak"));
			hide = h;
			self = flag("p_" + path + "_self", true);
			players = flag("p_" + path + "_players", true);
			entities = flag("p_" + path + "_entities", true);
			ctxAll = self && players && entities;
			reducedHides = path.equals("explosion") || path.equals("explosion_emitter") || path.equals("poof")
					|| path.equals("crit") || path.equals("enchanted_hit") || path.equals("effect")
					|| path.equals("entity_effect") || path.equals("firework") || path.equals("large_smoke");
			multiplier = slider("multiplier", 1.0) * slider("p_" + path + "_multiplier", 1.0);
			scale = slider("p_" + path + "_scale", 1.0);
			sound = flag("p_" + path + "_sound", false);
		}
	}

	private static final class Snap {
		final int rev;
		final boolean on, hideAll, hideFirstPerson, modeOff, modeReduced, hideBlockBreak;
		final float globalScale;
		final java.util.concurrent.ConcurrentHashMap<String, TypeInfo> types = new java.util.concurrent.ConcurrentHashMap<>();

		Snap(int rev) {
			this.rev = rev;
			on = Mods.on("particles");
			if (!on) {
				hideAll = hideFirstPerson = modeOff = modeReduced = hideBlockBreak = false;
				globalScale = 1f;
				return;
			}
			hideAll = Mods.bool("particles", "hideAll");
			hideFirstPerson = Mods.bool("particles", "hideFirstPerson");
			String mode = Mods.mode("particles", "mode");
			modeOff = mode.equals("Off");
			modeReduced = mode.equals("Reduced");
			double s = Mods.num("particles", "scale");
			globalScale = s > 0 && Math.abs(s - 1.0) > 0.001 ? (float) s : 1f;
			hideBlockBreak = hideAll || Mods.bool("particles", "hideBlockBreak")
					|| (Mods.hasOption("particles", "p_block") && !Mods.bool("particles", "p_block"))
					|| Mods.bool("particles", "p_block_hide");
		}

		TypeInfo type(String path) {
			TypeInfo t = types.get(path);
			if (t == null) {
				t = new TypeInfo(path);
				types.put(path, t);
			}
			return t;
		}
	}

	private static volatile Snap snap;

	private static Snap snap() {
		Snap s = snap;
		int r = Mods.configRev();
		if (s == null || s.rev != r) {
			s = new Snap(r);
			snap = s;
		}
		return s;
	}

	// particle id -> modern id; both tables are static, so this never needs invalidating
	private static final String[] PATH_BY_ID = new String[256];
	private static final boolean[] PATH_KNOWN = new boolean[256];

	/** The modern particle id ("crit", "block", ...) for a 1.8.9 particle id, or null if none. */
	public static String typePath(int particleId) {
		if (particleId >= 0 && particleId < 256 && PATH_KNOWN[particleId]) {
			return PATH_BY_ID[particleId];
		}
		String path = resolvePath(particleId);
		if (particleId >= 0 && particleId < 256) {
			PATH_BY_ID[particleId] = path;
			PATH_KNOWN[particleId] = true; // written after the value; a racing reader at worst resolves it again
		}
		return path;
	}

	private static String resolvePath(int particleId) {
		EnumParticleTypes t = EnumParticleTypes.getParticleFromId(particleId);
		if (t == null) {
			return null;
		}
		String name = t.getParticleName();
		if (name.endsWith("_")) {
			name = name.substring(0, name.length() - 1);   // "iconcrack_" + item args
		}
		return Mods.LEGACY_PARTICLE_IDS.get(name);
	}

	/** Global Scale slider (live, per frame) -- 1 when the mod is off or neutral. */
	public static float globalScale() {
		return snap().globalScale;
	}

	/** Hide All / Hide Block-Breaking: gates block break + hit particles (addBlockDestroyEffects / addBlockHitEffects). */
	public static boolean hideBlockBreak() {
		Snap s = snap();
		return s.on && s.hideBlockBreak;
	}

	/** Whether a spawn of this particle type at this position should be dropped. */
	public static boolean hidden(int particleId, double x, double y, double z) {
		Snap s = snap();
		if (!s.on) {
			return false;
		}
		if (s.hideAll) {
			return true;
		}
		// Hide particles that spawn right next to you in first person.
		if (s.hideFirstPerson) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.thePlayer != null && mc.gameSettings.thirdPersonView == 0
					&& mc.thePlayer.getPositionEyes(1.0F).squareDistanceTo(new net.minecraft.util.Vec3(x, y, z)) < 4.0) {
				return true;
			}
		}
		String path = typePath(particleId);
		TypeInfo ti = path == null ? null : s.type(path);
		if (ti != null) {
			// per-particle-type controls: master row toggle off, or its Hide flag
			// (only for types that actually have a row — unknown types pass through)
			if (ti.hide) {
				return true;
			}
			if (hiddenByContext(ti, x, y, z)) {
				return true;
			}
		}
		if (s.modeOff) {
			return true;
		}
		if (s.modeReduced && ti != null && ti.reducedHides) {
			return true;
		}
		// Multiplier < 1 thins the stream. Extra copies never re-roll this.
		if (ti != null && !spawningExtra) {
			double m = ti.multiplier;
			if (m < 1.0 && random() >= m) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Show on Self / Players / Entities. A particle carries no source entity, so
	 * "on X" is decided by what the spawn point sits inside. All three default to
	 * ON, and the entity sweep only runs when a row actually turns one OFF — with
	 * default settings this costs nothing beyond the snapshot flags.
	 */
	private static boolean hiddenByContext(TypeInfo ti, double x, double y, double z) {
		boolean self = ti.self;
		boolean players = ti.players;
		boolean entities = ti.entities;
		if (ti.ctxAll) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		WorldClient level = mc.theWorld;
		if (level == null) {
			return false;
		}
		if (!self && mc.thePlayer != null && near(mc.thePlayer, x, y, z)) {
			return true;
		}
		if (!players) {
			for (EntityPlayer p : level.playerEntities) {
				if (p != mc.thePlayer && near(p, x, y, z)) {
					return true;
				}
			}
		}
		if (!entities) {
			AxisAlignedBB box = new AxisAlignedBB(x - CONTEXT_RADIUS, y - CONTEXT_RADIUS, z - CONTEXT_RADIUS,
					x + CONTEXT_RADIUS, y + CONTEXT_RADIUS, z + CONTEXT_RADIUS);
			List<Entity> list = level.getEntitiesWithinAABBExcludingEntity(null, box);
			for (Entity e : list) {
				if (!(e instanceof EntityPlayer) && !(e instanceof EntityFX) && near(e, x, y, z)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Inflated bounding-box test — particles spawn on the surface of an entity, not its centre. */
	private static boolean near(Entity entity, double x, double y, double z) {
		AxisAlignedBB bb = entity.getEntityBoundingBox();
		return bb != null && bb.expand(CONTEXT_RADIUS, CONTEXT_RADIUS, CONTEXT_RADIUS)
				.isVecInside(new net.minecraft.util.Vec3(x, y, z));
	}

	// ThreadLocalRandom, not World.rand: the same code on every era.
	private static double random() {
		return ThreadLocalRandom.current().nextDouble();
	}

	/**
	 * Runs on a particle that just spawned: per-type Scale, Play Sound, and the
	 * Multiplier&gt;1 extra copies. Safe to call with a null particle (vanilla's
	 * "no factory" path).
	 */
	public static void afterSpawn(EffectRenderer engine, EntityFX particle, int particleId,
								  double x, double y, double z,
								  double xSpeed, double ySpeed, double zSpeed, int... params) {
		Snap snap = snap();
		if (!snap.on || spawningExtra) {
			return;
		}
		String path = typePath(particleId);
		if (path == null) {
			return;
		}
		TypeInfo ti = snap.type(path);
		if (particle != null) {
			double s = ti.scale;
			if (Math.abs(s - 1.0) > 0.001) {
				particle.multipleParticleScaleBy((float) s);
			}
		}
		if (ti.sound) {
			playSound(path, x, y, z);
		}

		// Multiplier > 1: one extra copy, jittered so it reads as "more particles"
		// instead of a second particle hiding exactly behind the first.
		double m = ti.multiplier;
		if (m > 1.0 && random() < m - 1.0) {
			spawningExtra = true;
			try {
				engine.spawnEffectParticle(particleId,
						x + (random() - 0.5) * 0.1, y + (random() - 0.5) * 0.1, z + (random() - 0.5) * 0.1,
						xSpeed * (0.9 + random() * 0.2),
						ySpeed * (0.9 + random() * 0.2),
						zSpeed * (0.9 + random() * 0.2), params);
			} finally {
				spawningExtra = false;
			}
		}
	}

	private static void playSound(String path, double x, double y, double z) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.theWorld == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST_SOUND.get(path);
		if (last != null && now - last < SOUND_GAP_MS) {
			return;
		}
		LAST_SOUND.put(path, now);
		// A short, quiet ding at the spawn — audible feedback (crits, hits) without
		// turning a particle stream into noise. 1.8.9: the XP orb is "random.orb".
		mc.theWorld.playSound(x, y, z, "random.orb", 0.25F, 1.7F, false);
	}
}
