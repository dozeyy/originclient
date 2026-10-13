package com.origin.client.client.mods;

import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sound Changer: a per-sound volume slider (0-100%) for the sounds players
 * actually ask to tame -- hits, explosions, pearls, chests, rain, music...
 * Read by SoundChangerMixin, which scales a sound's volume as SoundManager
 * computes it (both when it starts and whenever vanilla re-applies a playing
 * sound's volume), so a slider move applies even to a sound already playing.
 *
 * The list is curated, not all of vanilla's sound events: one slider per event
 * would make the page unusable. Option keys are "v_" + the MODERN event path
 * with dots as underscores, on every version, so Settings Sync carries them.
 *
 * 1.8.9: sound events have their old names ("random.bow", "mob.zombie.say",
 * "ambient.weather.rain"...). LEGACY maps each 1.8.9 event to the modern row it
 * belongs to; rows with no 1.8.9 sound at all (sweep/crit attack sounds, shields,
 * totems, crossbows, tridents...) get no slider here -- they'd be dead controls --
 * but their stored values still round-trip through the config untouched.
 */
public final class SoundChanger {
	private static final String MOD = "soundchanger";

	// {header} rows start a section; {sound id, label} rows are sliders.
	// SAME table as every other version (modern ids); LEGACY below decides which
	// rows exist on 1.8.9.
	private static final String[][] SOUNDS = {
			{"Combat"},
			{"entity.player.hurt", "Player Hurt"},
			{"entity.player.attack.strong", "Strong Attack"},
			{"entity.player.attack.weak", "Weak Attack"},
			{"entity.player.attack.crit", "Critical Hit"},
			{"entity.player.attack.sweep", "Sweep Attack"},
			{"entity.player.attack.knockback", "Knockback Attack"},
			{"entity.player.attack.nodamage", "No-Damage Attack"},
			{"entity.player.death", "Player Death"},
			{"entity.arrow.shoot", "Bow Shoot"},
			{"entity.arrow.hit", "Arrow Hit"},
			{"entity.arrow.hit_player", "Arrow Hit Player"},
			{"item.crossbow.shoot", "Crossbow Shoot"},
			{"item.trident.throw", "Trident Throw"},
			{"item.shield.block", "Shield Block"},
			{"item.shield.break", "Shield Break"},
			{"item.totem.use", "Totem of Undying"},
			{"entity.ender_pearl.throw", "Ender Pearl Throw"},
			{"entity.generic.explode", "Explosion"},
			{"entity.tnt.primed", "TNT Fuse"},
			{"entity.firework_rocket.launch", "Firework Launch"},
			{"entity.firework_rocket.blast", "Firework Blast"},
			{"Player"},
			{"entity.player.levelup", "Level Up"},
			{"entity.experience_orb.pickup", "XP Orb Pickup"},
			{"entity.item.pickup", "Item Pickup"},
			{"entity.item.break", "Item Break"},
			{"entity.generic.eat", "Eating"},
			{"entity.generic.drink", "Drinking"},
			{"entity.player.burp", "Burp"},
			{"entity.player.small_fall", "Small Fall"},
			{"entity.player.big_fall", "Big Fall"},
			{"entity.player.splash", "Water Splash"},
			{"entity.player.swim", "Swimming"},
			{"item.armor.equip_generic", "Armor Equip"},
			{"Blocks"},
			{"block.chest.open", "Chest Open"},
			{"block.chest.close", "Chest Close"},
			{"block.ender_chest.open", "Ender Chest Open"},
			{"block.wooden_door.open", "Door Open"},
			{"block.wooden_door.close", "Door Close"},
			{"block.anvil.use", "Anvil Use"},
			{"block.anvil.land", "Anvil Land"},
			{"block.piston.extend", "Piston"},
			{"block.portal.ambient", "Nether Portal Hum"},
			{"block.portal.travel", "Portal Travel"},
			{"block.beacon.ambient", "Beacon Hum"},
			{"block.fire.ambient", "Fire Crackle"},
			{"block.lava.ambient", "Lava Pop"},
			{"block.water.ambient", "Flowing Water"},
			{"block.note_block.pling", "Note Block Pling"},
			{"Mobs"},
			{"entity.villager.ambient", "Villager"},
			{"entity.enderman.teleport", "Enderman Teleport"},
			{"entity.creeper.primed", "Creeper Hiss"},
			{"entity.ghast.ambient", "Ghast"},
			{"entity.zombie.ambient", "Zombie"},
			{"entity.skeleton.ambient", "Skeleton"},
			{"entity.bat.ambient", "Bat"},
			{"entity.chicken.egg", "Chicken Egg"},
			{"entity.cow.ambient", "Cow"},
			{"entity.sheep.ambient", "Sheep"},
			{"entity.pig.ambient", "Pig"},
			{"entity.wither.spawn", "Wither Spawn"},
			{"entity.ender_dragon.growl", "Ender Dragon Growl"},
			{"entity.ender_dragon.flap", "Ender Dragon Wings"},
			{"Weather & Ambience"},
			{"weather.rain", "Rain"},
			{"weather.rain.above", "Rain (Above)"},
			{"entity.lightning_bolt.thunder", "Thunder"},
			{"entity.lightning_bolt.impact", "Lightning Impact"},
			{"ambient.cave", "Cave Noises"},
			{"ambient.underwater.loop", "Underwater Loop"},
			{"Music & Interface"},
			{"music.game", "Game Music"},
			{"music.creative", "Creative Music"},
			{"music.menu", "Menu Music"},
			{"ui.button.click", "Button Click"},
			{"ui.toast.challenge_complete", "Challenge Complete"},
	};

	// 1.8.9 sound event -> the modern row it belongs to. Several 1.8.9 events can
	// share one row (piston in/out, the firework blast variants, the nether/end
	// music tracks under Game Music, chest open for chests AND ender chests --
	// 1.8.9 plays the same sound for both).
	private static final String[][] LEGACY = {
			{"game.player.hurt", "entity.player.hurt"},
			{"game.player.die", "entity.player.death"},
			{"random.bow", "entity.arrow.shoot"},
			{"random.bowhit", "entity.arrow.hit"},
			{"random.successful_hit", "entity.arrow.hit_player"},
			{"random.explode", "entity.generic.explode"},
			{"game.tnt.primed", "entity.tnt.primed"},
			{"fireworks.launch", "entity.firework_rocket.launch"},
			{"fireworks.blast", "entity.firework_rocket.blast"},
			{"fireworks.blast_far", "entity.firework_rocket.blast"},
			{"fireworks.largeBlast", "entity.firework_rocket.blast"},
			{"fireworks.largeBlast_far", "entity.firework_rocket.blast"},
			{"fireworks.twinkle", "entity.firework_rocket.blast"},
			{"fireworks.twinkle_far", "entity.firework_rocket.blast"},
			{"random.levelup", "entity.player.levelup"},
			{"random.orb", "entity.experience_orb.pickup"},
			{"random.pop", "entity.item.pickup"},
			{"random.break", "entity.item.break"},
			{"random.eat", "entity.generic.eat"},
			{"random.drink", "entity.generic.drink"},
			{"random.burp", "entity.player.burp"},
			{"game.player.hurt.fall.small", "entity.player.small_fall"},
			{"game.player.hurt.fall.big", "entity.player.big_fall"},
			{"game.player.swim.splash", "entity.player.splash"},
			{"game.player.swim", "entity.player.swim"},
			{"random.chestopen", "block.chest.open"},
			{"random.chestclosed", "block.chest.close"},
			{"random.door_open", "block.wooden_door.open"},
			{"random.door_close", "block.wooden_door.close"},
			{"random.anvil_use", "block.anvil.use"},
			{"random.anvil_land", "block.anvil.land"},
			{"tile.piston.out", "block.piston.extend"},
			{"tile.piston.in", "block.piston.extend"},
			{"portal.portal", "block.portal.ambient"},
			{"portal.travel", "block.portal.travel"},
			{"portal.trigger", "block.portal.travel"},
			{"fire.fire", "block.fire.ambient"},
			{"liquid.lavapop", "block.lava.ambient"},
			{"liquid.water", "block.water.ambient"},
			{"note.pling", "block.note_block.pling"},
			{"mob.villager.idle", "entity.villager.ambient"},
			{"mob.endermen.portal", "entity.enderman.teleport"},
			{"creeper.primed", "entity.creeper.primed"},
			{"mob.ghast.moan", "entity.ghast.ambient"},
			{"mob.zombie.say", "entity.zombie.ambient"},
			{"mob.skeleton.say", "entity.skeleton.ambient"},
			{"mob.bat.idle", "entity.bat.ambient"},
			{"mob.chicken.plop", "entity.chicken.egg"},
			{"mob.cow.say", "entity.cow.ambient"},
			{"mob.sheep.say", "entity.sheep.ambient"},
			{"mob.pig.say", "entity.pig.ambient"},
			{"mob.wither.spawn", "entity.wither.spawn"},
			{"mob.enderdragon.growl", "entity.ender_dragon.growl"},
			{"mob.enderdragon.wings", "entity.ender_dragon.flap"},
			{"ambient.weather.rain", "weather.rain"},
			{"ambient.weather.thunder", "entity.lightning_bolt.thunder"},
			{"ambient.cave.cave", "ambient.cave"},
			{"music.game", "music.game"},
			{"music.game.nether", "music.game"},
			{"music.game.end", "music.game"},
			{"music.game.end.dragon", "music.game"},
			{"music.game.end.credits", "music.game"},
			{"music.game.creative", "music.creative"},
			{"music.menu", "music.menu"},
			{"gui.button.press", "ui.button.click"},
	};

	// minecraft:<1.8.9 path> -> option key, built once for the per-sound lookup.
	private static final Map<String, String> KEY_BY_ID = new HashMap<>();
	// modern rows that have at least one 1.8.9 sound (the only rows shown here).
	private static final java.util.Set<String> PRESENT = new java.util.HashSet<>();

	static {
		for (String[] l : LEGACY) {
			KEY_BY_ID.put("minecraft:" + l[0], key(l[1]));
			PRESENT.add(l[1]);
		}
	}

	private SoundChanger() {
	}

	private static String key(String soundPath) {
		return "v_" + soundPath.replace('.', '_');
	}

	/** The mod's option rows (section headers + one 0-100% slider per sound 1.8.9 has). */
	static ModOption[] options() {
		List<ModOption> o = new ArrayList<>();
		for (String[] s : SOUNDS) {
			if (s.length == 1) {
				o.add(ModOption.header(s[0]));
			} else if (PRESENT.contains(s[0])) {
				o.add(ModOption.slider(key(s[0]), s[1], 0, 1, 0.05, 1.0, "%.0f%%"));
			}
		}
		return o.toArray(new ModOption[0]);
	}

	/** Volume multiplier for a (1.8.9-named) sound event (1 = untouched). */
	public static float factor(ResourceLocation sound) {
		if (sound == null || !Mods.on(MOD)) {
			return 1f;
		}
		String key = KEY_BY_ID.get(sound.toString());
		return key == null ? 1f : (float) Math.max(0.0, Math.min(1.0, Mods.num(MOD, key)));
	}
}
