package com.origin.client.client;

import com.origin.client.client.mods.Mods;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEV-ONLY (dev-shots world set, ORIGIN_SHOTS_SET=world): the Origin features that only show inside a
 * real world, shot in a real survival/creative world instead of the design set's screens:
 *   hud_readouts  every HUD readout on together, with armour on and two effects running
 *   hitboxes      Hitbox on, three mobs a few blocks ahead
 *   weather_rain  Weather Changer on Rain
 *   weather_snow  Weather Changer on Snow
 *   nametags_f5   Nametags on, third person: the named mobs' tags and your own tag over your head
 *
 * Version-independent on purpose (plain Java 8, only the Mods API): which mods each shot turns on, the
 * scene commands per command dialect, and the file names. Each module's OriginDevWorld does the Minecraft
 * side -- runs the commands on the integrated server, sets the camera, grabs the frame. 1.8.9 (no shared
 * sync) carries a verbatim copy of this file; keep the two the same.
 *
 * Everything apply() changes is put back by restore(), and cleanupCommands() removes the mobs, armour and
 * effects, so the shots that follow (cosmetics fit, pause, party) look exactly as before.
 */
public final class DevFeatureShots {
	private DevFeatureShots() {
	}

	/** One shot of the sequence, in order. */
	public enum Shot {
		HUD_READOUTS("hud_readouts", false, 4000),
		HITBOXES("hitboxes", false, 2000),
		WEATHER_RAIN("weather_rain", false, 4000),
		WEATHER_SNOW("weather_snow", false, 4000),
		NAMETAGS_F5("nametags_f5", true, 2500);

		/** The PNG name (same size suffix as the rest of the world set). */
		public final String file;
		/** Shoot from behind the player (F5) instead of first person. */
		public final boolean thirdPerson;
		/** How long to let the change draw before the grab. */
		public final long settleMs;

		Shot(String name, boolean thirdPerson, long settleMs) {
			this.file = name + "_1920x1080.png";
			this.thirdPerson = thirdPerson;
			this.settleMs = settleMs;
		}
	}

	/** How the scene commands are spelled on a version. */
	public enum Dialect {
		/** 1.20 - 1.21.4: a mob's CustomName is a JSON text string inside the SNBT. */
		JSON_NAMES,
		/** 1.21.5+ and 26.x: CustomName is an SNBT text component; a plain string is literal text. */
		SNBT_NAMES,
		/** 1.8.9: old entity ids (Cow), replaceitem, numeric effect ids, no entity tags. */
		LEGACY_189
	}

	/** Every HUD readout Origin has, on together for the first shot (and kept on for the rest). */
	private static final String[] HUD = {"fps", "cps", "coords", "armorhud", "potionhud", "keystrokes", "clock",
			"ping", "memory", "daycounter", "directionhud", "combo", "reach", "packdisplay", "stopwatch",
			"saturation", "cooldowns"};
	/** The feature mods the shots switch; their on/off is saved before and put back after. */
	private static final String[] FEATURES = {"hitboxes", "weather", "nametags"};
	public static final String TAG = "origin_featshot";

	private static final Map<String, Boolean> wasOn = new LinkedHashMap<String, Boolean>();
	private static String wasWeatherMode;
	private static boolean wasThunder, wasThirdPersonTag, wasLimitUnfocused, saved;

	/** Turns on exactly what this shot shows. Call once per shot, in Shot order. */
	public static void apply(Shot s) {
		if (!saved) {
			for (String id : HUD) {
				wasOn.put(id, Mods.on(id));
			}
			for (String id : FEATURES) {
				wasOn.put(id, Mods.on(id));
			}
			wasWeatherMode = Mods.mode("weather", "mode");
			wasThunder = Mods.bool("weather", "thunder");
			wasThirdPersonTag = Mods.bool("nametags", "thirdPerson");
			wasLimitUnfocused = Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps");
			saved = true;
		}
		switch (s) {
			case HUD_READOUTS:
				// Xvfb has no window manager, so the game window is never "active": with Limit Unfocused
				// FPS on, Origin's HUD skips itself (FrameWatch.idle) and the readouts would be missing.
				Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", false);
				for (String id : HUD) {
					Mods.setOn(id, true);
				}
				break;
			case HITBOXES:
				Mods.setOn("hitboxes", true);
				break;
			case WEATHER_RAIN:
				Mods.setOn("hitboxes", false);
				Mods.setOn("weather", true);
				Mods.set("weather", "mode", "Rain");
				Mods.set("weather", "thunder", false);
				break;
			case WEATHER_SNOW:
				Mods.set("weather", "mode", "Snow");
				break;
			case NAMETAGS_F5:
				Mods.setOn("weather", false);
				Mods.setOn("nametags", true);
				Mods.set("nametags", "thirdPerson", true);
				break;
			default:
				break;
		}
	}

	/** Puts every setting apply() touched back the way it was. */
	public static void restore() {
		if (!saved) {
			return;
		}
		for (Map.Entry<String, Boolean> e : wasOn.entrySet()) {
			Mods.setOn(e.getKey(), e.getValue());
		}
		Mods.set("weather", "mode", wasWeatherMode);
		Mods.set("weather", "thunder", wasThunder);
		Mods.set("nametags", "thirdPerson", wasThirdPersonTag);
		Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", wasLimitUnfocused);
		saved = false;
	}

	/** Ground height at a block column (the first free block above the surface), asked on the server thread. */
	public interface Ground {
		int y(int x, int z);
	}

	/**
	 * Server commands that set the scene: diamond armour and two particle-free effects on the player (so
	 * Armor Status and Potion Effects have something to show), and a cow, a villager and a pig standing
	 * 6 blocks ahead facing the camera, still and silent. The cow and the villager are named with their
	 * tags always shown (the "other entity nametags"); the pig stays unnamed. Run on the server thread.
	 */
	public static List<String> sceneCommands(double px, double pz, float yawDeg, Ground ground, Dialect d) {
		double yaw = Math.toRadians(yawDeg);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // horizontal view direction
		double sx = -fz, sz = fx;                          // to the side
		String[][] mobs = d == Dialect.LEGACY_189
				? new String[][]{{"Cow", "Bessie"}, {"Villager", "Origin Tester"}, {"Pig", null}}
				: new String[][]{{"minecraft:cow", "Bessie"}, {"minecraft:villager", "Origin Tester"}, {"minecraft:pig", null}};
		List<String> cmds = new ArrayList<String>();
		if (d == Dialect.LEGACY_189) {
			cmds.add("replaceitem entity @a slot.armor.head minecraft:diamond_helmet");
			cmds.add("replaceitem entity @a slot.armor.chest minecraft:diamond_chestplate");
			cmds.add("replaceitem entity @a slot.armor.legs minecraft:diamond_leggings");
			cmds.add("replaceitem entity @a slot.armor.feet minecraft:diamond_boots");
			cmds.add("effect @a 11 600 0 true");   // resistance
			cmds.add("effect @a 12 600 0 true");   // fire resistance
		} else {
			cmds.add("item replace entity @a armor.head with minecraft:diamond_helmet");
			cmds.add("item replace entity @a armor.chest with minecraft:diamond_chestplate");
			cmds.add("item replace entity @a armor.legs with minecraft:diamond_leggings");
			cmds.add("item replace entity @a armor.feet with minecraft:diamond_boots");
			cmds.add("effect give @a minecraft:resistance 600 0 true");
			cmds.add("effect give @a minecraft:fire_resistance 600 0 true");
		}
		float face = yawDeg + 180f;   // turned toward the player
		for (int i = 0; i < mobs.length; i++) {
			double side = (i - 1) * 3.0;
			int x = (int) Math.floor(px + fx * 6 + sx * side);
			int z = (int) Math.floor(pz + fz * 6 + sz * side);
			int y = ground.y(x, z);
			StringBuilder nbt = new StringBuilder("{NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[")
					.append(face).append("f,0f]");
			if (d != Dialect.LEGACY_189) {
				nbt.append(",Tags:[\"").append(TAG).append("\"]");
			}
			String name = mobs[i][1];
			if (name != null) {
				nbt.append(",CustomNameVisible:1b,CustomName:");
				nbt.append(d == Dialect.JSON_NAMES ? "'\"" + name + "\"'" : "\"" + name + "\"");
			}
			nbt.append('}');
			cmds.add("summon " + mobs[i][0] + " " + (x + 0.5) + " " + y + " " + (z + 0.5) + " " + nbt);
		}
		return cmds;
	}

	/** Removes the scene: the three mobs, the armour and the effects. Run on the server thread.
	 *  Each line stands alone: one that finds no target just fails and the rest still run. */
	public static List<String> cleanupCommands(Dialect d) {
		List<String> cmds = new ArrayList<String>();
		if (d == Dialect.LEGACY_189) {
			// 1.8.9 has no entity tags: remove the scene's mob types (a dev world, nothing else to keep)
			cmds.add("kill @e[type=Cow]");
			cmds.add("kill @e[type=Villager]");
			cmds.add("kill @e[type=Pig]");
			cmds.add("clear @a");   // the whole inventory, armour included (the dev world's is otherwise empty)
			cmds.add("effect @a clear");
		} else {
			cmds.add("kill @e[tag=" + TAG + "]");
			cmds.add("clear @a");   // the whole inventory, armour included (the dev world's is otherwise empty)
			cmds.add("effect clear @a");
		}
		return cmds;
	}
}
