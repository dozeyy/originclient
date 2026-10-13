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

	/** The modern particle id ("crit", "block", ...) for a 1.8.9 particle id, or null if none. */
	public static String typePath(int particleId) {
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
		if (!Mods.on("particles")) {
			return 1f;
		}
		double s = Mods.num("particles", "scale");
		return s > 0 && Math.abs(s - 1.0) > 0.001 ? (float) s : 1f;
	}

	/** Hide All / Hide Block-Breaking: gates block break + hit particles (addBlockDestroyEffects / addBlockHitEffects). */
	public static boolean hideBlockBreak() {
		return Mods.on("particles") && (Mods.bool("particles", "hideAll") || Mods.bool("particles", "hideBlockBreak")
				|| (Mods.hasOption("particles", "p_block") && !Mods.bool("particles", "p_block"))
				|| Mods.bool("particles", "p_block_hide"));
	}

	/** Whether a spawn of this particle type at this position should be dropped. */
	public static boolean hidden(int particleId, double x, double y, double z) {
		if (!Mods.on("particles")) {
			return false;
		}
		if (Mods.bool("particles", "hideAll")) {
			return true;
		}
		// Hide particles that spawn right next to you in first person.
		if (Mods.bool("particles", "hideFirstPerson")) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.thePlayer != null && mc.gameSettings.thirdPersonView == 0
					&& mc.thePlayer.getPositionEyes(1.0F).squareDistanceTo(new net.minecraft.util.Vec3(x, y, z)) < 4.0) {
				return true;
			}
		}
		String path = typePath(particleId);
		if (path != null) {
			// per-particle-type controls: master row toggle off, or its Hide flag
			// (only for types that actually have a row — unknown types pass through)
			if (Mods.hasOption("particles", "p_" + path) && !Mods.bool("particles", "p_" + path)) {
				return true;
			}
			if (Mods.bool("particles", "p_" + path + "_hide")) {
				return true;
			}
			if (path.equals("block") && Mods.bool("particles", "hideBlockBreak")) {
				return true;
			}
			if (hiddenByContext(path, x, y, z)) {
				return true;
			}
		}
		String mode = Mods.mode("particles", "mode");
		if (mode.equals("Off")) {
			return true;
		}
		if (mode.equals("Reduced") && path != null) {
			if (path.equals("explosion") || path.equals("explosion_emitter") || path.equals("poof")
					|| path.equals("crit") || path.equals("enchanted_hit") || path.equals("effect")
					|| path.equals("entity_effect") || path.equals("firework") || path.equals("large_smoke")) {
				return true;
			}
		}
		// Multiplier < 1 thins the stream. Extra copies never re-roll this.
		if (path != null && !spawningExtra) {
			double m = multiplier(path);
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
	 * default settings this costs three config reads and nothing else.
	 */
	private static boolean hiddenByContext(String path, double x, double y, double z) {
		boolean self = flag("p_" + path + "_self", true);
		boolean players = flag("p_" + path + "_players", true);
		boolean entities = flag("p_" + path + "_entities", true);
		if (self && players && entities) {
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

	private static double multiplier(String path) {
		return slider("multiplier", 1.0) * slider("p_" + path + "_multiplier", 1.0);
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
		if (!Mods.on("particles") || spawningExtra) {
			return;
		}
		String path = typePath(particleId);
		if (path == null) {
			return;
		}
		if (particle != null) {
			double s = slider("p_" + path + "_scale", 1.0);
			if (Math.abs(s - 1.0) > 0.001) {
				particle.multipleParticleScaleBy((float) s);
			}
		}
		playSound(path, x, y, z);

		// Multiplier > 1: one extra copy, jittered so it reads as "more particles"
		// instead of a second particle hiding exactly behind the first.
		double m = multiplier(path);
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
		if (!flag("p_" + path + "_sound", false)) {
			return;
		}
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
