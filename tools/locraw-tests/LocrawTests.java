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
		eq(HypixelLocraw.playId("{\"server\":\"m\",\"gametype\":\"X\",\"mode\":\"a b; /x\"}"), null, "odd mode never becomes a command");

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
		HypixelLocraw.reset();

		System.out.println(failures == 0 ? "LocrawTests: OK" : "LocrawTests: " + failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
