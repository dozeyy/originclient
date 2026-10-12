package com.origin.client.client.mods;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sound Changer: a per-sound volume slider (0-100%) for the sounds players
 * actually ask to tame -- hits, explosions, pearls, chests, rain, music...
 * Read by SoundChangerMixin, which scales a sound's volume as SoundEngine
 * computes it (both when it starts and every tick it keeps playing), so a
 * slider move applies even to a sound that is already looping (rain, music).
 *
 * The list is curated, not all ~1,600 vanilla sound events: one slider per event
 * would make the page unusable. An id that doesn't exist simply never matches.
 * Option keys are "v_" + the event path with dots as underscores.
 */
public final class SoundChanger {
	private static final String MOD = "soundchanger";

	// {header} rows start a section; {sound id, label} rows are sliders.
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
			{"entity.warden.heartbeat", "Warden Heartbeat"},
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

	// minecraft:<path> -> option key, built once for the per-sound lookup.
	private static final Map<String, String> KEY_BY_ID = new HashMap<>();

	static {
		for (String[] s : SOUNDS) {
			if (s.length == 2) {
				KEY_BY_ID.put("minecraft:" + s[0], key(s[0]));
			}
		}
	}

	private SoundChanger() {
	}

	private static String key(String soundPath) {
		return "v_" + soundPath.replace('.', '_');
	}

	/** The mod's option rows (section headers + one 0-100% slider per sound). */
	static ModOption[] options() {
		List<ModOption> o = new ArrayList<>();
		for (String[] s : SOUNDS) {
			o.add(s.length == 1
					? ModOption.header(s[0])
					: ModOption.slider(key(s[0]), s[1], 0, 1, 0.05, 1.0, "%.0f%%"));
		}
		return o.toArray(new ModOption[0]);
	}

	/** Volume multiplier for a sound event (1 = untouched). */
	public static float factor(Identifier sound) {
		if (sound == null || !Mods.on(MOD)) {
			return 1f;
		}
		String key = KEY_BY_ID.get(sound.toString());
		// Biome tracks (music.overworld.*, music.nether.*) play in place of
		// music.game, so the Game Music slider covers them too.
		if (key == null && sound.getNamespace().equals("minecraft")
				&& (sound.getPath().startsWith("music.overworld.") || sound.getPath().startsWith("music.nether."))) {
			key = key("music.game");
		}
		return key == null ? 1f : (float) Math.max(0.0, Math.min(1.0, Mods.num(MOD, key)));
	}
}
