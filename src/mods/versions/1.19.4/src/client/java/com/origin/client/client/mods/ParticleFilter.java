package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.IdentityHashMap;
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

	/**
	 * One particle type's option keys ("p_crit", "p_crit_hide", ...) and its ding clock,
	 * built the first time that type spawns. The filter runs on every spawn and used to
	 * concatenate up to eight key strings per particle (and box a Long per ding).
	 * Particle types are registry singletons, so identity is the map key. Spawns run on
	 * the client thread only, like every caller of this class.
	 */
	private static final class Keys {
		final String path, row, hide, self, players, entities, multiplier, scale, sound;
		/** When this type last played its ding (ms), for SOUND_GAP_MS. */
		long lastSound;

		Keys(String path) {
			this.path = path;
			this.row = "p_".concat(path);
			this.hide = row + "_hide";
			this.self = row + "_self";
			this.players = row + "_players";
			this.entities = row + "_entities";
			this.multiplier = row + "_multiplier";
			this.scale = row + "_scale";
			this.sound = row + "_sound";
		}
	}

	private static final Map<net.minecraft.core.particles.ParticleType<?>, Keys> KEYS = new IdentityHashMap<>();

	/** This spawn's cached keys, or null for an unregistered (modded, unnamed) type. */
	private static Keys keys(ParticleOptions options) {
		net.minecraft.core.particles.ParticleType<?> type = options.getType();
		Keys k = KEYS.get(type);
		if (k == null) {
			String path = typePath(options);
			if (path == null) {
				return null;
			}
			k = new Keys(path);
			KEYS.put(type, k);
		}
		return k;
	}

	/** Set while a Multiplier&gt;1 extra copy is being spawned, so it can't multiply again. */
	private static boolean spawningExtra;

	/**
	 * Set when the firework starter has just decided to SHOW a spark
	 * (starterSparkHidden returned false). The starter then calls createParticle
	 * once and dereferences the result with no null check, so that one call must
	 * get the SAME answer: without this, the Multiplier&lt;1 random roll in hidden()
	 * re-rolled, returned null and crashed the game on any firework ("Ticking
	 * Particle" NPE) whenever a Particles multiplier sat below 1.0. Render thread only.
	 */
	private static boolean starterApproved;

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

	/**
	 * The running game's own particle types as Particle Changer rows {registry path, name},
	 * vanilla namespace only, in registry order. Read from the registry so every version lists
	 * exactly the particles it has (Sweep Attack, Totem of Undying, the mace's Dust Pillar, a
	 * newer version's additions) and none it doesn't. Null when the registry can't be read:
	 * Mods then keeps its fixed list (fail soft). Saved p_<id> keys of a particle this version
	 * lacks stay in the config untouched (Settings Sync carries them to the versions that have it).
	 */
	public static String[][] vanillaTypes() {
		try {
			java.util.List<String[]> out = new java.util.ArrayList<>();
			for (ParticleType<?> type : BuiltInRegistries.PARTICLE_TYPE) {
				var key = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
				if (key != null && "minecraft".equals(key.getNamespace())) {
					out.add(new String[] {key.getPath(), displayName(key.getPath())});
				}
			}
			return out.isEmpty() ? null : out.toArray(new String[0][]);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** "totem_of_undying" -> "Totem of Undying": the row label for a registry path. */
	static String displayName(String path) {
		StringBuilder sb = new StringBuilder();
		for (String word : path.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			boolean small = sb.length() > 0 && (word.equals("of") || word.equals("and"));
			sb.append(small ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
		}
		return sb.toString();
	}

	/** Registry path of a particle type ("crit", "block", ...), or null if unregistered. */
	public static String typePath(ParticleOptions options) {
		var key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
		return key == null ? null : key.getPath();
	}

	/**
	 * The firework starter's one decision per spark (FireworkStarterMixin). A
	 * "show" answer is remembered so the createParticle call that follows can't
	 * flip it to hidden (see starterApproved).
	 */
	public static boolean starterSparkHidden(double x, double y, double z) {
		starterApproved = false;
		boolean hide = hidden(ParticleTypes.FIREWORK, x, y, z);
		starterApproved = !hide;
		return hide;
	}

	/** Whether a spawn of this particle type at this position should be dropped. */
	public static boolean hidden(ParticleOptions options, double x, double y, double z) {
		if (starterApproved) {
			// consume the starter's pass on the very next spawn; it is always that spark
			starterApproved = false;
			if (options.getType() == ParticleTypes.FIREWORK) {
				return false;
			}
		}
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
		Keys k = keys(options);
		if (k != null) {
			// per-particle-type controls: master row toggle off, or its Hide flag
			// (only for types that actually have a row — unknown types pass through)
			if (Mods.hasOption("particles", k.row) && !Mods.bool("particles", k.row)) {
				return true;
			}
			if (Mods.bool("particles", k.hide)) {
				return true;
			}
			if (k.path.equals("block") && Mods.bool("particles", "hideBlockBreak")) {
				return true;
			}
			if (hiddenByContext(k, x, y, z)) {
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
		if (k != null && !spawningExtra) {
			double m = multiplier(k);
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
	private static boolean hiddenByContext(Keys k, double x, double y, double z) {
		boolean self = flag(k.self, true);
		boolean players = flag(k.players, true);
		boolean entities = flag(k.entities, true);
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

	private static double multiplier(Keys k) {
		return slider("multiplier", 1.0) * slider(k.multiplier, 1.0);
	}

	// Whole extra copies plus one more with the fractional chance, so 3.0x really
	// spawns three particles. It used to spawn at most one extra (a 2x ceiling) while
	// the global and per-type sliders multiply to 4x. Capped at 3 extra copies.
	private static int extraCopies(double m) {
		if (!(m > 1.0)) {
			return 0;
		}
		double extra = Math.min(m - 1.0, 3.0);
		int n = (int) extra;
		if (random() < extra - n) {
			n++;
		}
		return n;
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
		Keys k = keys(options);
		if (k == null) {
			return;
		}
		if (particle != null) {
			double s = slider(k.scale, 1.0);
			if (Math.abs(s - 1.0) > 0.001) {
				particle.scale((float) s);
			}
		}
		playSound(k, x, y, z);

		// Multiplier > 1: m - 1 extra copies on average (see extraCopies), each
		// jittered so it reads as "more particles"
		// instead of a second particle hiding exactly behind the first.
		double m = multiplier(k);
		for (int n = extraCopies(m); n > 0; n--) {
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

	private static void playSound(Keys k, double x, double y, double z) {
		if (!flag(k.sound, false)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - k.lastSound < SOUND_GAP_MS) {
			return;
		}
		k.lastSound = now;
		// A short, quiet ding at the spawn — audible feedback (crits, hits) without
		// turning a particle stream into noise.
		mc.level.playLocalSound(x, y, z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS,
				0.25F, 1.7F, false);
	}
}
