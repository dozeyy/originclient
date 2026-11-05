import com.origin.client.client.util.HypixelLocraw;

/** util/HypixelLocraw (JG-11): Play Again knows the Hypixel game from /locraw; the same ask names
 *  the waypoint place (JG-8). Run by run.py. */
public final class LocrawTests {
	private static int failures;

	static void eq(Object got, Object want, String what) {
		if (want == null ? got != null : !want.equals(got)) {
			System.out.println("FAIL " + what + ": want " + want + ", got " + got);
			failures++;
		}
	}

	public static void main(String[] args) {
		// the reply -> /play id
		eq(HypixelLocraw.playId("{\"server\":\"mini123A\",\"gametype\":\"BEDWARS\",\"mode\":\"BEDWARS_EIGHT_TWO\",\"map\":\"Lighthouse\"}"),
				"bedwars_eight_two", "Bed Wars Doubles");
		eq(HypixelLocraw.playId("{\"server\":\"mini9B\",\"gametype\":\"DUELS\",\"mode\":\"DUELS_BRIDGE_DUEL\",\"map\":\"Fort\"}"),
				"duels_bridge_duel", "Bridge");
		eq(HypixelLocraw.playId("{\"server\":\"mini2\", \"gametype\": \"SKYWARS\", \"mode\": \"solo_insane\"}"), "solo_insane", "spaces, lower case");
		eq(HypixelLocraw.playId("{\"server\":\"lobby12\",\"gametype\":\"BEDWARS\",\"lobbyname\":\"bedwarslobby12\"}"), null, "a lobby has no mode");
		eq(HypixelLocraw.playId("{\"server\":\"lobby3\",\"gametype\":\"MAIN\",\"mode\":\"LOBBY\"}"), null, "mode LOBBY");
		eq(HypixelLocraw.playId("{\"server\":\"mini1\",\"gametype\":\"SKYBLOCK\",\"mode\":\"hub\"}"), null, "SkyBlock island is no /play id");
		eq(HypixelLocraw.playId("{\"server\":\"limbo\"}"), null, "limbo");
		eq(HypixelLocraw.playId("{\"server\":\"m\",\"gametype\":\"X\",\"mode\":\"a b; /x\"}"), HypixelLocraw.UNKNOWN, "odd mode never becomes a command");
		eq(HypixelLocraw.playId("{\"server\":\"m\",\"gametype\":\"SKYWARS\",\"mode\":\"a b; /x\"}"), HypixelLocraw.UNKNOWN, "odd SkyWars mode never becomes a command");

		// HYPIXEL-1: games whose API mode key is not their /play id go to Quickplay's own ids
		String[][] mapped = {
				{"SURVIVAL_GAMES", "solo_normal", "blitz_solo_normal"},     // not SkyWars Solo Normal
				{"SURVIVAL_GAMES", "teams_normal", "blitz_teams_normal"},
				{"SUPER_SMASH", "solo_normal", "super_smash_solo_normal"},  // not SkyWars Solo Normal
				{"SUPER_SMASH", "2v2_normal", "super_smash_2v2_normal"},
				{"SUPER_SMASH", "teams_normal", "super_smash_teams_normal"},
				{"TNTGAMES", "TNTRUN", "tnt_tntrun"}, {"TNTGAMES", "PVPRUN", "tnt_pvprun"},
				{"TNTGAMES", "BOWSPLEEF", "tnt_bowspleef"}, {"TNTGAMES", "TNTTAG", "tnt_tntag"},
				{"TNTGAMES", "CAPTURE", "tnt_capture"},
				{"WALLS3", "standard", "mw_standard"}, {"WALLS3", "face_off", "mw_face_off"},
				{"UHC", "SOLO", "uhc_solo"}, {"UHC", "TEAMS", "uhc_teams"},
				{"MCGO", "normal", "mcgo_normal"}, {"MCGO", "deathmatch", "mcgo_deathmatch"},
				{"WOOL_GAMES", "wool_wars_two_four", "wool_wool_wars_two_four"},
				{"ARCADE", "PARTY", "arcade_party_games_1"}, {"ARCADE", "HOLE_IN_THE_WALL", "arcade_hole_in_the_wall"},
				{"ARCADE", "DRAGONWARS2", "arcade_dragon_wars"}, {"ARCADE", "ONEINTHEQUIVER", "arcade_bounty_hunters"},
				{"ARCADE", "ZOMBIES_DEAD_END", "arcade_zombies_dead_end"},
				{"QUAKECRAFT", "solo", "quake_solo"}, {"PAINTBALL", "normal", "paintball"},
				{"VAMPIREZ", "normal", "vampirez"}, {"GINGERBREAD", "normal", "tkr"}, {"WALLS", "normal", "walls"},
				{"MURDER_MYSTERY", "MURDER_CLASSIC", "murder_classic"},
				{"BUILD_BATTLE", "BUILD_BATTLE_SOLO_PRO", "build_battle_solo_pro"},
				{"PIT", "PIT", "pit"},
		};
		for (String[] r : mapped) {
			eq(HypixelLocraw.playId("{\"server\":\"mini1\",\"gametype\":\"" + r[0] + "\",\"mode\":\"" + r[1] + "\"}"),
					r[2], r[0] + " " + r[1]);
		}
		// a game Origin has no id for: UNKNOWN (a short message), never a guess at another game's id
		eq(HypixelLocraw.playId("{\"server\":\"mini1\",\"gametype\":\"BATTLEGROUND\",\"mode\":\"ctf_mini\"}"),
				HypixelLocraw.UNKNOWN, "Warlords: unknown");
		eq(HypixelLocraw.playId("{\"server\":\"mini1\",\"gametype\":\"ARCADE\",\"mode\":\"DEFENDER\"}"),
				HypixelLocraw.UNKNOWN, "unlisted Arcade mode: unknown");
		eq(HypixelLocraw.playId("{\"server\":\"mini1\",\"gametype\":\"SURVIVAL_GAMES\",\"mode\":\"solo_nokits\"}"),
				HypixelLocraw.UNKNOWN, "unlisted Blitz mode: unknown, not SkyWars");

		// asking: once per world, after the delay, only when wanted
		HypixelLocraw.reset();
		Object w1 = new Object(), w2 = new Object();
		long t = 1_000_000;
		eq(HypixelLocraw.tick(w1, true, t), false, "not right away");
		eq(HypixelLocraw.tick(w1, true, t + 1_999), false, "still waiting");
		eq(HypixelLocraw.tick(w1, true, t + 2_000), true, "asks after 2 s");
		eq(HypixelLocraw.tick(w1, true, t + 9_000), false, "once per world");
		eq(HypixelLocraw.tick(w1, false, t + 9_500), false, "off Hypixel / Quickplay off: never");
		eq(HypixelLocraw.tick(w2, true, t + 10_000), false, "new world: wait again");
		eq(HypixelLocraw.tick(w2, true, t + 12_000), true, "new world: asks");

		// the reply to our ask is hidden and recorded; a typed /locraw shows
		String reply = "{\"server\":\"mini1\",\"gametype\":\"BEDWARS\",\"mode\":\"BEDWARS_FOUR_FOUR\",\"map\":\"Aquarium\"}";
		eq(HypixelLocraw.onMessage(reply, t + 12_300), false, "our reply hidden");
		eq(HypixelLocraw.takeSeen(), "bedwars_four_four", "game recorded");
		eq(HypixelLocraw.takeSeen(), null, "taken once");
		eq(HypixelLocraw.onMessage(reply, t + 12_400), true, "a second (typed) /locraw shows");
		eq(HypixelLocraw.onMessage("[MVP+] Steve: {\"server\":\"x\"}", t + 12_500), true, "player chat untouched");
		eq(HypixelLocraw.takeSeen(), "bedwars_four_four", "typed reply still records");
		eq(HypixelLocraw.tick(w2, true, t + 20_000), false, "no new ask");
		eq(HypixelLocraw.onMessage(reply, t + 40_000), true, "late reply with no ask shows");


		// JG-8: the same reply names the waypoint place; one ask per world serves Waypoints too
		eq(HypixelLocraw.placeOf(reply), "bedwars/aquarium", "place: game + map");
		eq(HypixelLocraw.placeIn(w2), "bedwars/aquarium", "place known in that world");
		eq(HypixelLocraw.placeIn(w1), null, "not in another world");
		eq(HypixelLocraw.placeOf("{\"server\":\"lobby7\",\"gametype\":\"BEDWARS\",\"lobbyname\":\"bedwarslobby51\"}"), "bedwars/lobby", "place: lobby");
		eq(HypixelLocraw.placeOf(" {\"server\": \"mini1B\", \"gametype\": \"SKYBLOCK\", \"mode\": \"hub\", \"map\": \"Hub\"} "), "skyblock/hub", "place: SkyBlock hub");
		eq(HypixelLocraw.placeOf("{\"server\":\"mini9\",\"gametype\":\"SKYBLOCK\",\"mode\":\"dynamic\"}"), "skyblock/dynamic", "place: mode when no map");
		eq(HypixelLocraw.placeOf("{\"server\":\"limbo\"}"), "limbo", "place: limbo");
		eq(HypixelLocraw.placeOf("[MVP+] Someone: {\"server\":\"x\"}"), null, "place: chat is not a reply");
		eq(HypixelLocraw.placeOf("{\"gametype\":\"BEDWARS\"}"), null, "place: no server");
		Object w3 = new Object(), w4 = new Object();
		eq(HypixelLocraw.tick(w3, false, t + 50_000), false, "Quickplay off, Waypoints off: no ask");
		eq(HypixelLocraw.placeIn(w3), null, "new world forgets the place");
		HypixelLocraw.waypointsWant = true;
		eq(HypixelLocraw.tick(w4, false, t + 51_000), false, "Waypoints on: wait");
		eq(HypixelLocraw.tick(w4, false, t + 53_000), true, "Waypoints on, Quickplay off: asks");
		eq(HypixelLocraw.tick(w4, true, t + 54_000), false, "Quickplay on too: still one ask per world");
		// Hypixel never answers Origin's ask: the player's own /locraw 3+ s later shows, and ends the ask
		Object noAnswer = new Object();
		HypixelLocraw.tick(noAnswer, true, t + 70_000);
		eq(HypixelLocraw.tick(noAnswer, true, t + 72_000), true, "asks in another world");
		eq(HypixelLocraw.onMessage(reply, t + 75_500), true, "typed /locraw after an unanswered ask shows");
		eq(HypixelLocraw.onMessage(reply, t + 75_600), true, "and the ask is over: the next one shows too");
		// a reply inside the window after the first one (another mod's, a typed one) shows
		Object twoReplies = new Object();
		HypixelLocraw.tick(twoReplies, true, t + 80_000);
		eq(HypixelLocraw.tick(twoReplies, true, t + 82_000), true, "asks in one more world");
		eq(HypixelLocraw.onMessage(reply, t + 82_400), false, "first reply in the window hidden");
		eq(HypixelLocraw.onMessage(reply, t + 82_900), true, "a second reply in the window shows");
		// N92-3: Waypoints' want arrives a tick after the world appears (Hypixel.tick runs after this
		// tick): the world is still asked about, once
		HypixelLocraw.reset();
		Object lobby = new Object();
		eq(HypixelLocraw.tick(lobby, false, t + 90_000), false, "first tick in a world: not wanted yet");
		HypixelLocraw.waypointsWant = true;
		eq(HypixelLocraw.tick(lobby, false, t + 90_050), false, "wanted next tick: wait");
		eq(HypixelLocraw.tick(lobby, false, t + 92_049), false, "still waiting");
		eq(HypixelLocraw.tick(lobby, false, t + 92_050), true, "first lobby is asked about");
		eq(HypixelLocraw.tick(lobby, false, t + 99_000), false, "and only once");
		// Waypoints turned on mid-world (Quickplay off): asked then, once
		HypixelLocraw.waypointsWant = false;
		Object island = new Object();
		eq(HypixelLocraw.tick(island, false, t + 100_000), false, "nothing wants it");
		eq(HypixelLocraw.tick(island, false, t + 130_000), false, "still nothing");
		HypixelLocraw.waypointsWant = true;
		eq(HypixelLocraw.tick(island, false, t + 140_000), false, "Waypoints turned on: wait");
		eq(HypixelLocraw.tick(island, false, t + 142_000), true, "Waypoints turned on: asks");
		eq(HypixelLocraw.tick(island, true, t + 150_000), false, "Quickplay on too: no second ask");
		// turned off before the ask went out, then on again: the ask is not lost
		Object flip = new Object();
		eq(HypixelLocraw.tick(flip, false, t + 160_000), false, "wanted: wait");
		HypixelLocraw.waypointsWant = false;
		eq(HypixelLocraw.tick(flip, false, t + 161_000), false, "turned off before the ask");
		HypixelLocraw.waypointsWant = true;
		eq(HypixelLocraw.tick(flip, false, t + 170_000), false, "on again: wait again");
		eq(HypixelLocraw.tick(flip, false, t + 172_000), true, "on again: asks");
		// a place already known in this world (the player typed /locraw) needs no ask
		Object typed = new Object();
		HypixelLocraw.waypointsWant = false;
		HypixelLocraw.tick(typed, false, t + 180_000);
		HypixelLocraw.onMessage(reply, t + 180_500);
		HypixelLocraw.waypointsWant = true;
		eq(HypixelLocraw.tick(typed, false, t + 181_000), false, "place known: no ask");
		eq(HypixelLocraw.tick(typed, false, t + 190_000), false, "place known: still no ask");
		eq(HypixelLocraw.placeIn(typed), "bedwars/aquarium", "typed reply gives the place");
		HypixelLocraw.reset();

		System.out.println(failures == 0 ? "LocrawTests: OK" : "LocrawTests: " + failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
