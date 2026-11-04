import com.origin.client.client.util.KeyTaps;
import com.origin.client.client.util.WindowTitles;

// Pure-logic checks for the QoL fixes of 2026-11-04 (run.py compiles the shared sources
// next to this file). Exit code = number of failures.
public class QolTests {
	static int bad = 0;

	static void eq(Object got, Object want, String what) {
		if (want == null ? got != null : !want.equals(got)) {
			System.out.println("FAIL " + what + ": got " + got + ", want " + want);
			bad++;
		}
	}

	// Mods.keepOldDefaultKeysOnce's call shape, over a plain map standing in for the config's meta.
	static boolean once(java.util.Map<String, String> meta, String marker, Runnable step) {
		return com.origin.client.client.util.OnceSteps.run(marker, 1, meta::get,
				(k, v) -> meta.put(k, String.valueOf(v)), step);
	}

	public static void main(String[] args) {
		// util/WindowTitles (G121-12)
		eq(WindowTitles.origin("Minecraft* 1.21.1 - Singleplayer", "Steve"), "Origin 1.21.1 - Steve - Singleplayer", "singleplayer");
		eq(WindowTitles.origin("Minecraft* 1.21.1", "Steve"), "Origin 1.21.1 - Steve", "title screen");
		eq(WindowTitles.origin("Minecraft 1.20.1 - Multiplayer (3rd-party Server)", "Alex"),
				"Origin 1.20.1 - Alex - Multiplayer (3rd-party Server)", "unmodded mark, server");
		eq(WindowTitles.origin("Minecraft* 1.21.1 - Singleplayer", ""), "Origin 1.21.1 - Singleplayer", "no name");
		eq(WindowTitles.origin("Minecraft* 1.21.1", null), "Origin 1.21.1", "null name");
		eq(WindowTitles.origin("My Modpack 2.0", "Steve"), "My Modpack 2.0", "foreign title untouched");
		eq(WindowTitles.origin(null, "Steve"), null, "null title");

		// util/KeyTaps (G121-7)
		eq(KeyTaps.consume(67), false, "nothing pressed");
		KeyTaps.onKey(67, 1);
		KeyTaps.onKey(67, 0);                       // a tap: down and up before the tick
		eq(KeyTaps.consume(67), true, "tap between ticks is seen");
		eq(KeyTaps.consume(67), false, "a tap counts once");
		KeyTaps.onKey(67, 2);                       // OS key repeat is not a press
		eq(KeyTaps.consume(67), false, "repeat ignored");
		KeyTaps.onKey(67, 1);
		KeyTaps.onKey(67, 1);                       // two taps in one tick: one toggle
		eq(KeyTaps.consume(67), true, "double tap seen");
		eq(KeyTaps.consume(67), false, "double tap counts once");
		KeyTaps.onKey(-1, 1);
		KeyTaps.onKey(100000, 1);                   // out of range: ignored, never throws
		eq(KeyTaps.consume(-1), false, "unbound");
		eq(KeyTaps.consume(100000), false, "out of range");
		KeyTaps.onKey(70, 1);
		eq(KeyTaps.consume(67), false, "other key untouched");
		eq(KeyTaps.consume(70), true, "own key");

		// util/OnceSteps (review 2026-11-04: Mods.keepOldDefaultKeysOnce ran every launch)
		java.util.Map<String, String> meta = new java.util.HashMap<>();
		int[] ran = {0};
		Runnable step = () -> ran[0]++;
		String mk = "optionVersion.defaultKeys";
		eq(once(meta, mk, step), true, "fresh config: step runs");
		eq(meta.get(mk), "1", "marker written even when the step finds nothing");
		eq(once(meta, mk, step), false, "same session: not again");
		java.util.Map<String, String> reloaded = new java.util.HashMap<>(meta); // next launch, saved file
		eq(once(reloaded, mk, step), false, "next launch: not again (the old bug)");
		eq(ran[0], 1, "step ran exactly once");
		meta.clear();
		meta.put(mk, "abc");
		eq(once(meta, mk, step), true, "hand-edited marker counts as not done");
		meta.put(mk, "2");
		eq(once(meta, mk, step), false, "a newer marker (synced from a later build) is done");
		meta.clear();
		meta.put("optionVersion.menublur.enabled", "2");
		eq(once(meta, mk, step), true, "another option's marker doesn't count");

		System.out.println(bad == 0 ? "qol-tests: all passed" : "qol-tests: " + bad + " failed");
		System.exit(bad);
	}
}
