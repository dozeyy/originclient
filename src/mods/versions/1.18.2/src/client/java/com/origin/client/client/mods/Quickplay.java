package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.util.List;

// Quickplay (Lunar parity): one menu listing Hypixel's games and modes; picking
// a mode runs Hypixel's own /play <mode> command, exactly what typing it does.
// The "Open Quickplay" key opens QuickplayScreen; "Play Again" re-runs the last
// pick. Keys are edge-triggered once per client tick with no screen open.
//
// The mode ids are Hypixel's public /play ids. Hypixel renames or retires
// rotating modes now and then; an id it no longer knows just gets Hypixel's
// own "unknown game" reply in chat -- nothing breaks client-side.
public final class Quickplay {
	private Quickplay() {
	}

	private static final String ID = "quickplay";

	public record Mode(String name, String playId) {
	}

	/** lobby is the /lobby target for the game's own lobby, or null when it has none. */
	public record Game(String name, String lobby, List<Mode> modes) {
	}

	private static Mode m(String name, String id) {
		return new Mode(name, id);
	}

	public static final List<Game> GAMES = List.of(
			new Game("Bed Wars", "bedwars", List.of(
					m("Solo", "bedwars_eight_one"), m("Doubles", "bedwars_eight_two"),
					m("3v3v3v3", "bedwars_four_three"), m("4v4v4v4", "bedwars_four_four"),
					m("4v4", "bedwars_two_four"))),
			new Game("SkyWars", "skywars", List.of(
					m("Solo Normal", "solo_normal"), m("Solo Insane", "solo_insane"),
					m("Teams Normal", "teams_normal"), m("Teams Insane", "teams_insane"))),
			new Game("Duels", "duels", List.of(
					m("Classic", "duels_classic_duel"), m("Bridge", "duels_bridge_duel"),
					m("Bridge Doubles", "duels_bridge_doubles"), m("Sumo", "duels_sumo_duel"),
					m("UHC", "duels_uhc_duel"), m("SkyWars", "duels_sw_duel"),
					m("OP", "duels_op_duel"), m("Combo", "duels_combo_duel"),
					m("Bow", "duels_bow_duel"), m("NoDebuff", "duels_potion_duel"),
					m("Blitz", "duels_blitz_duel"), m("Mega Walls", "duels_mw_duel"),
					m("Boxing", "duels_boxing_duel"), m("Bow Spleef", "duels_bowspleef_duel"),
					m("Parkour", "duels_parkour_eight"))),
			new Game("Murder Mystery", "murder", List.of(
					m("Classic", "murder_classic"), m("Double Up", "murder_double_up"),
					m("Infection", "murder_infection"))),
			new Game("Build Battle", "build", List.of(
					m("Solo", "build_battle_solo_normal"), m("Teams", "build_battle_teams_normal"),
					m("Pro", "build_battle_solo_pro"), m("Guess the Build", "build_battle_guess_the_build"))),
			new Game("The Pit", null, List.of(m("Play", "pit"))),
			new Game("SkyBlock", null, List.of(m("Play", "skyblock"))),
			new Game("TNT Games", "tnt", List.of(
					m("TNT Run", "tnt_tntrun"), m("PVP Run", "tnt_pvprun"), m("Bow Spleef", "tnt_bowspleef"),
					m("TNT Tag", "tnt_tntag"), m("Wizards", "tnt_capture"))),
			new Game("Mega Walls", "megawalls", List.of(
					m("Standard", "mw_standard"), m("Face Off", "mw_face_off"))),
			new Game("Blitz SG", "blitz", List.of(
					m("Solo", "blitz_solo_normal"), m("Teams", "blitz_teams_normal"))),
			new Game("UHC", "uhc", List.of(m("Solo", "uhc_solo"), m("Teams", "uhc_teams"))),
			new Game("Wool Wars", null, List.of(m("4v4", "wool_wool_wars_two_four"))),
			new Game("Arcade", "arcade", List.of(
					m("Party Games", "arcade_party_games_1"), m("Hole in the Wall", "arcade_hole_in_the_wall"),
					m("Mini Walls", "arcade_mini_walls"), m("Football", "arcade_soccer"),
					m("Ender Spleef", "arcade_ender_spleef"), m("Throw Out", "arcade_throw_out"),
					m("Dragon Wars", "arcade_dragon_wars"), m("Bounty Hunters", "arcade_bounty_hunters"),
					m("Farm Hunt", "arcade_farm_hunt"), m("Prop Hunt", "arcade_hide_and_seek_prop_hunt"),
					m("Party Pooper", "arcade_hide_and_seek_party_pooper"), m("Galaxy Wars", "arcade_starwars"),
					m("Pixel Painters", "arcade_pixel_painters"), m("Pixel Party", "arcade_pixel_party"),
					m("Dropper", "arcade_dropper"), m("Zombies: Dead End", "arcade_zombies_dead_end"),
					m("Zombies: Bad Blood", "arcade_zombies_bad_blood"),
					m("Zombies: Alien Arcadium", "arcade_zombies_alien_arcadium"))),
			new Game("Cops and Crims", "cvc", List.of(
					m("Defusal", "mcgo_normal"), m("Deathmatch", "mcgo_deathmatch"))),
			new Game("Smash Heroes", "smash", List.of(
					m("Solo", "super_smash_solo_normal"), m("2v2", "super_smash_2v2_normal"),
					m("Teams", "super_smash_teams_normal"))),
			new Game("Classic Games", "classic", List.of(
					m("Quakecraft Solo", "quake_solo"), m("Quakecraft Teams", "quake_teams"),
					m("Paintball", "paintball"), m("VampireZ", "vampirez"), m("The Walls", "walls"),
					m("Turbo Kart Racers", "tkr"), m("Arena Brawl 1v1", "arena_1v1"))));

	private static String lastId = null, lastLabel = null;
	private static boolean openWasDown = false, againWasDown = false;

	public static void tick(Minecraft mc) {
		boolean active = Mods.on(ID) && mc.player != null && mc.screen == null;
		boolean open = active && OriginClientMod.isRawKeyDown(Mods.keyCode(ID, "key"));
		boolean again = active && OriginClientMod.isRawKeyDown(Mods.keyCode(ID, "requeueKey"));
		if (open && !openWasDown) {
			mc.setScreen(new com.origin.client.client.gui.QuickplayScreen());
		} else if (again && !againWasDown) {
			if (lastId != null) {
				run(mc, "play " + lastId, lastLabel);
			} else if (mc.player != null) {
				mc.player.displayClientMessage(new TextComponent("Pick a game in Quickplay first"), true);
			}
		}
		openWasDown = open;
		againWasDown = again;
	}

	/** Joins a mode (remembered for Play Again). */
	public static void play(Minecraft mc, Game game, Mode mode) {
		String label = mode.name().equals("Play") ? game.name() : game.name() + " · " + mode.name();
		lastId = mode.playId();
		lastLabel = label;
		run(mc, "play " + mode.playId(), label);
	}

	/** Goes to the game's lobby. */
	public static void lobby(Minecraft mc, Game game) {
		if (game.lobby() != null) {
			run(mc, "lobby " + game.lobby(), game.name() + " lobby");
		}
	}

	private static void run(Minecraft mc, String command, String label) {
		ClientPacketListener conn = mc.getConnection();
		if (conn == null) {
			return;
		}
		Era.sendCommand(mc, command);
		if (mc.player != null && Mods.bool(ID, "displayMessage")) {
			mc.player.displayClientMessage(new TextComponent("Joining " + label + "..."), true);
		}
	}

	public static boolean onHypixel(Minecraft mc) {
		return AutoGG.onHypixel(mc);
	}
}
