package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every Particle Changer rule in one place — asked once per spawn, from
 * {@code ParticleEngineMixin} (and, on the render-split versions, from the
 * firework-starter guard, which must ask the SAME question: vanilla's
 * FireworkParticles$Starter dereferences the result of createParticle without a
 * null check, so a spark suppressed by returning null crashes the game).
 *
 * The per-type rows and the global sliders all resolve here:
 *   row toggle / Hide Particle      -> drop the spawn
 *   Show on Self / Players / Entities -> drop when the spawn is on that kind of
 *                                      thing (proximity at spawn time — a
 *                                      particle carries no source entity)
 *   Multiplier (global x per-type)  -> &lt;1 drops a share of spawns, &gt;1 adds one
 *   Scale (per-type)                -> applied ONCE at spawn via Particle.scale
 *                                      (the global Scale slider stays a live
 *                                      per-frame multiply in SingleQuadParticleMixin
 *                                      so it previews while you drag it)
 *   Play Sound                      -> a short, rate-limited ding at the spawn
 *
 * Deliberately NOT a mixin class: mixin classes can't be referenced from normal
 * code (IllegalClassLoadError), and several hooks need to call this.
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

	// ---- schema-safe reads: unknown (modded) particle types have no rows, and
	// must fall back to "show, unchanged" rather than to Mods' zero defaults ----

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

	/** Registry path of a particle type ("crit", "block", ...), or null if unregistered. */
	public static String typePath(ParticleOptions options) {
		var key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
		return key == null ? null : key.getPath();
	}

	/** Whether a spawn of this particle type at this position should be dropped. */
	public static boolean hidden(ParticleOptions options, double x, double y, double z) {
		if (!Mods.on("particles")) {
			return false;
		}
		if (Mods.bool("particles", "hideAll")) {
			return true;
		}
		// Hide particles that spawn right next to you in first person.
		if (Mods.bool("particles", "hideFirstPerson")) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.options.getCameraType().isFirstPerson()
					&& mc.player.getEyePosition().distanceToSqr(x, y, z) < 4.0) {
				return true;
			}
		}
		String path = typePath(options);
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
		if (mode.equals("Reduced")) {
			var t = options.getType();
			if (t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER
					|| t == ParticleTypes.POOF || t == ParticleTypes.CRIT
					|| t == ParticleTypes.ENCHANTED_HIT || t == ParticleTypes.EFFECT
					|| t == ParticleTypes.ENTITY_EFFECT || t == ParticleTypes.FIREWORK
					|| t == ParticleTypes.LARGE_SMOKE) {
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
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return false;
		}
		if (!self && mc.player != null && near(mc.player, x, y, z)) {
			return true;
		}
		if (!players) {
			for (Player p : level.players()) {
				if (p != mc.player && near(p, x, y, z)) {
					return true;
				}
			}
		}
		if (!entities) {
			AABB box = new AABB(x - CONTEXT_RADIUS, y - CONTEXT_RADIUS, z - CONTEXT_RADIUS,
					x + CONTEXT_RADIUS, y + CONTEXT_RADIUS, z + CONTEXT_RADIUS);
			for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
				if (near(e, x, y, z)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Inflated bounding-box test — particles spawn on the surface of an entity, not its centre. */
	private static boolean near(Entity entity, double x, double y, double z) {
		return entity.getBoundingBox().inflate(CONTEXT_RADIUS).contains(x, y, z);
	}

	private static double multiplier(String path) {
		return slider("multiplier", 1.0) * slider("p_" + path + "_multiplier", 1.0);
	}

	// ThreadLocalRandom, not Level.random: the level's RandomSource is protected
	// again from 26.2 on, and this needs to compile identically on every era.
	private static double random() {
		return ThreadLocalRandom.current().nextDouble();
	}

	/**
	 * Runs on a particle that just spawned: per-type Scale, Play Sound, and the
	 * Multiplier&gt;1 extra copies. Safe to call with a null particle (vanilla's
	 * "no provider" path).
	 */
	public static void afterSpawn(ParticleEngine engine, Particle particle, ParticleOptions options,
								  double x, double y, double z,
								  double xSpeed, double ySpeed, double zSpeed) {
		if (!Mods.on("particles") || spawningExtra) {
			return;
		}
		String path = typePath(options);
		if (path == null) {
			return;
		}
		if (particle != null) {
			double s = slider("p_" + path + "_scale", 1.0);
			if (Math.abs(s - 1.0) > 0.001) {
				particle.scale((float) s);
			}
		}
		playSound(path, x, y, z);

		// Multiplier > 1: one extra copy, jittered so it reads as "more particles"
		// instead of a second particle hiding exactly behind the first.
		double m = multiplier(path);
		if (m > 1.0 && random() < m - 1.0) {
			spawningExtra = true;
			try {
				engine.createParticle(options,
						x + (random() - 0.5) * 0.1, y + (random() - 0.5) * 0.1, z + (random() - 0.5) * 0.1,
						xSpeed * (0.9 + random() * 0.2),
						ySpeed * (0.9 + random() * 0.2),
						zSpeed * (0.9 + random() * 0.2));
			} finally {
				spawningExtra = false;
			}
		}
	}

	private static void playSound(String path, double x, double y, double z) {
		if (!flag("p_" + path + "_sound", false)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST_SOUND.get(path);
		if (last != null && now - last < SOUND_GAP_MS) {
			return;
		}
		LAST_SOUND.put(path, now);
		// A short, quiet ding at the spawn — audible feedback (crits, hits) without
		// turning a particle stream into noise.
		mc.level.playLocalSound(x, y, z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS,
				0.25F, 1.7F, false);
	}
}
