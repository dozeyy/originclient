package com.origin.client.client.mods;

import com.origin.client.client.util.KeyCodes;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.util.List;

// Quickplay (Lunar parity): one menu listing Hypixel's games and modes; picking
// a mode runs Hypixel's own /play <mode> command, exactly what typing it does.
// The "Open Quickplay" key opens QuickplayScreen; "Play Again" re-runs the last
// pick. Keys are edge-triggered once per client tick with no screen open.
//
// The mode ids are Hypixel's public /play ids. Hypixel renames or retires
// rotating modes now and then; an id it no longer knows just gets Hypixel's
// own "unknown game" reply in chat -- nothing breaks client-side.
//
// 1.8.9: keys are LWJGL2 codes from Mods.keyCode, polled with KeyCodes.isDown;
// commands go out as "/" chat (EntityPlayerSP.sendChatMessage) and the
// "Joining ..." note uses the action bar (GuiIngame.setRecordPlaying).
public final class Quickplay {
	private Quickplay() {
	}

	private static final String ID = "quickplay";

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Mode {
		private final String name;
		private final String playId;

		public Mode(String name, String playId) {
			this.name = name;
			this.playId = playId;
		}

		public String name() {
			return name;
		}

		public String playId() {
			return playId;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Mode)) {
				return false;
			}
			Mode other = (Mode) obj;
			return java.util.Objects.equals(this.name, other.name)
					&& java.util.Objects.equals(this.playId, other.playId);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(name, playId);
		}
	}

	/** lobby is the /lobby target for the game's own lobby, or null when it has none. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Game {
		private final String name;
		private final String lobby;
		private final List<Mode> modes;

		public Game(String name, String lobby, List<Mode> modes) {
			this.name = name;
			this.lobby = lobby;
			this.modes = modes;
		}

		public String name() {
			return name;
		}

		public String lobby() {
			return lobby;
		}

		public List<Mode> modes() {
			return modes;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Game)) {
				return false;
			}
			Game other = (Game) obj;
			return java.util.Objects.equals(this.name, other.name)
					&& java.util.Objects.equals(this.lobby, other.lobby)
					&& java.util.Objects.equals(this.modes, other.modes);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(name, lobby, modes);
		}
	}

	private static Mode m(String name, String id) {
		return new Mode(name, id);
	}

	public static final List<Game> GAMES = java.util.Arrays.asList(
			new Game("Bed Wars", "bedwars", java.util.Arrays.asList(
					m("Solo", "bedwars_eight_one"), m("Doubles", "bedwars_eight_two"),
					m("3v3v3v3", "bedwars_four_three"), m("4v4v4v4", "bedwars_four_four"),
					m("4v4", "bedwars_two_four"))),
			new Game("SkyWars", "skywars", java.util.Arrays.asList(
					m("Solo Normal", "solo_normal"), m("Solo Insane", "solo_insane"),
					m("Teams Normal", "teams_normal"), m("Teams Insane", "teams_insane"))),
			new Game("Duels", "duels", java.util.Arrays.asList(
					m("Classic", "duels_classic_duel"), m("Bridge", "duels_bridge_duel"),
					m("Bridge Doubles", "duels_bridge_doubles"), m("Sumo", "duels_sumo_duel"),
					m("UHC", "duels_uhc_duel"), m("SkyWars", "duels_sw_duel"),
					m("OP", "duels_op_duel"), m("Combo", "duels_combo_duel"),
					m("Bow", "duels_bow_duel"), m("NoDebuff", "duels_potion_duel"),
					m("Blitz", "duels_blitz_duel"), m("Mega Walls", "duels_mw_duel"),
					m("Boxing", "duels_boxing_duel"), m("Bow Spleef", "duels_bowspleef_duel"),
					m("Parkour", "duels_parkour_eight"))),
			new Game("Murder Mystery", "murder", java.util.Arrays.asList(
					m("Classic", "murder_classic"), m("Double Up", "murder_double_up"),
					m("Infection", "murder_infection"))),
			new Game("Build Battle", "build", java.util.Arrays.asList(
					m("Solo", "build_battle_solo_normal"), m("Teams", "build_battle_teams_normal"),
					m("Pro", "build_battle_solo_pro"), m("Guess the Build", "build_battle_guess_the_build"))),
			new Game("The Pit", null, java.util.Arrays.asList(m("Play", "pit"))),
			new Game("SkyBlock", null, java.util.Arrays.asList(m("Play", "skyblock"))),
			new Game("TNT Games", "tnt", java.util.Arrays.asList(
					m("TNT Run", "tnt_tntrun"), m("PVP Run", "tnt_pvprun"), m("Bow Spleef", "tnt_bowspleef"),
					m("TNT Tag", "tnt_tntag"), m("Wizards", "tnt_capture"))),
			new Game("Mega Walls", "megawalls", java.util.Arrays.asList(
					m("Standard", "mw_standard"), m("Face Off", "mw_face_off"))),
			new Game("Blitz SG", "blitz", java.util.Arrays.asList(
					m("Solo", "blitz_solo_normal"), m("Teams", "blitz_teams_normal"))),
			new Game("UHC", "uhc", java.util.Arrays.asList(m("Solo", "uhc_solo"), m("Teams", "uhc_teams"))),
			new Game("Wool Wars", null, java.util.Arrays.asList(m("4v4", "wool_wool_wars_two_four"))),
			new Game("Arcade", "arcade", java.util.Arrays.asList(
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
			new Game("Cops and Crims", "cvc", java.util.Arrays.asList(
					m("Defusal", "mcgo_normal"), m("Deathmatch", "mcgo_deathmatch"))),
			new Game("Smash Heroes", "smash", java.util.Arrays.asList(
					m("Solo", "super_smash_solo_normal"), m("2v2", "super_smash_2v2_normal"),
					m("Teams", "super_smash_teams_normal"))),
			new Game("Classic Games", "classic", java.util.Arrays.asList(
					m("Quakecraft Solo", "quake_solo"), m("Quakecraft Teams", "quake_teams"),
					m("Paintball", "paintball"), m("VampireZ", "vampirez"), m("The Walls", "walls"),
					m("Turbo Kart Racers", "tkr"), m("Arena Brawl 1v1", "arena_1v1"))));

	private static String lastId = null, lastLabel = null;
	private static boolean openWasDown = false, againWasDown = false;

	public static void tick(Minecraft mc) {
		boolean active = Mods.on(ID) && mc.thePlayer != null && mc.currentScreen == null;
		boolean open = active && KeyCodes.isDown(Mods.keyCode(ID, "key"));
		boolean again = active && KeyCodes.isDown(Mods.keyCode(ID, "requeueKey"));
		if (open && !openWasDown) {
			mc.displayGuiScreen(new com.origin.client.client.gui.QuickplayScreen());
		} else if (again && !againWasDown) {
			if (lastId != null) {
				run(mc, "play " + lastId, lastLabel);
			} else if (mc.thePlayer != null) {
				actionBar(mc, "Pick a game in Quickplay first");
			}
		}
		openWasDown = open;
		againWasDown = again;
	}

	/** Joins a mode (remembered for Play Again). */
	public static void play(Minecraft mc, Game game, Mode mode) {
		String label = mode.name().equals("Play") ? game.name() : game.name() + " \u00b7 " + mode.name();
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
		if (mc.getNetHandler() == null || mc.thePlayer == null) {
			return;
		}
		mc.thePlayer.sendChatMessage("/" + command);   // 1.8.9: commands go out as "/" chat
		if (mc.thePlayer != null && Mods.bool(ID, "displayMessage")) {
			actionBar(mc, "Joining " + label + "...");
		}
	}

	// 1.8.9: the action bar is GuiIngame.setRecordPlaying(component, false).
	private static void actionBar(Minecraft mc, String text) {
		if (mc.ingameGUI != null) {
			mc.ingameGUI.setRecordPlaying(new ChatComponentText(text), false);
		}
	}

	public static boolean onHypixel(Minecraft mc) {
		return AutoGG.onHypixel(mc);
	}
}
