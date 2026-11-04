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

		System.out.println(bad == 0 ? "qol-tests: all passed" : "qol-tests: " + bad + " failed");
		System.exit(bad);
	}
}
