import com.origin.client.client.util.HypixelLocraw;

/** util/HypixelLocraw (JG-11): Play Again knows the Hypixel game from /locraw. Run by run.py. */
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

		System.out.println(failures == 0 ? "LocrawTests: OK" : "LocrawTests: " + failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
