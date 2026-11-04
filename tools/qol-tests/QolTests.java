import com.origin.client.client.util.HotkeyChord;
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

		// util/HotkeyChord (JG-1): "None" fires while Ctrl/Shift are held as Sprint/Sneak
		int[] keys = {0, 71, 0, 0};                 // slot 1 = G, slots 2-3 off
		String[] modes = {null, "None", "None", "None"};
		eq(HotkeyChord.fires(1, keys, modes, false, false, false), true, "None, nothing held");
		eq(HotkeyChord.fires(1, keys, modes, true, false, false), true, "None while sprinting (Ctrl)");
		eq(HotkeyChord.fires(1, keys, modes, false, true, false), true, "None while sneaking (Shift)");
		eq(HotkeyChord.fires(1, keys, modes, true, true, true), true, "None with every modifier held");
		modes[1] = "Ctrl";
		eq(HotkeyChord.fires(1, keys, modes, false, false, false), false, "Ctrl slot without Ctrl");
		eq(HotkeyChord.fires(1, keys, modes, true, true, false), true, "Ctrl slot with Ctrl (+Shift)");
		modes[1] = "Alt";
		eq(HotkeyChord.fires(1, keys, modes, true, false, false), false, "Alt slot with only Ctrl");
		// plain G (slot 1) and Ctrl + G (slot 2) on one key: each sends only its own line
		int[] twin = {0, 71, 71, 72};
		String[] twinModes = {null, "None", "Ctrl", "None"};
		eq(HotkeyChord.fires(1, twin, twinModes, false, true, false), true, "plain G while sneaking");
		eq(HotkeyChord.fires(1, twin, twinModes, true, false, false), false, "plain G steps aside for Ctrl + G");
		eq(HotkeyChord.fires(2, twin, twinModes, true, false, false), true, "Ctrl + G fires");
		eq(HotkeyChord.fires(3, twin, twinModes, true, false, false), true, "other key's None ignores the G twin");
		int[] offTwin = {0, 71, 0};                 // the Ctrl + G slot is switched off
		eq(HotkeyChord.fires(1, offTwin, new String[]{null, "None", "Ctrl"}, true, false, false), true,
				"an off twin never blocks");
		eq(HotkeyChord.fires(1, keys, new String[]{null, "Bogus", null, null}, true, false, false), true,
				"unknown modifier = None");

		// util/KeyTaps (G121-7): taps between ticks
		eq(KeyTaps.consume(67), false, "nothing pressed");
		KeyTaps.onKey(67, 1, false, false);
		KeyTaps.onKey(67, 0, false, false);         // a tap: down and up before the tick
		eq(KeyTaps.consume(67), true, "tap between ticks is seen");
		eq(KeyTaps.consume(67), false, "a tap counts once");
		KeyTaps.onKey(67, 2, false, false);         // OS key repeat is not a press
		eq(KeyTaps.consume(67), false, "repeat ignored");
		KeyTaps.onKey(67, 1, false, false);
		KeyTaps.onKey(67, 1, false, false);         // two taps in one tick: one toggle
		eq(KeyTaps.consume(67), true, "double tap seen");
		eq(KeyTaps.consume(67), false, "double tap counts once");
		KeyTaps.onKey(-1, 1, false, false);
		KeyTaps.onKey(100000, 1, true, false);      // out of range: ignored, never throws
		eq(KeyTaps.consume(-1), false, "unbound");
		eq(KeyTaps.consume(100000), false, "out of range");
		eq(KeyTaps.inF3Combo(100000, true), false, "out of range combo");
		KeyTaps.onKey(70, 1, false, false);
		eq(KeyTaps.consume(67), false, "other key untouched");
		eq(KeyTaps.consume(70), true, "own key");
		KeyTaps.onKey(71, 1, false, true);          // typed into a screen
		eq(KeyTaps.consume(71), false, "press inside a screen is typing, not a tap");

		// util/KeyTaps F3 combos (BMOD-1): F3+B must not fire Snaplook on B
		int b = 66;
		KeyTaps.onKey(b, 1, true, false);           // B pressed while F3 held
		eq(KeyTaps.consume(b), false, "F3+B is not a tap");
		eq(KeyTaps.inF3Combo(b, true), true, "B held in the combo reads as up");
		eq(KeyTaps.inF3Combo(b, true), true, "still held after F3 is let go first");
		eq(KeyTaps.inF3Combo(b, false), false, "B up: never a combo");
		eq(KeyTaps.inF3Combo(b, true), false, "next hold of B is the player's own (release seen by polling)");
		KeyTaps.onKey(b, 1, true, false);
		KeyTaps.onKey(b, 1, false, false);          // released unseen, pressed again without F3
		eq(KeyTaps.inF3Combo(b, true), false, "a plain press clears the combo");
		eq(KeyTaps.consume(b), true, "and counts as a tap");
		KeyTaps.onKey(b, 1, true, true);            // F3 + B with a screen open
		eq(KeyTaps.inF3Combo(b, true), true, "combo inside a screen still marked");
		eq(KeyTaps.inF3Combo(70, true), false, "other keys untouched by the combo");

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
