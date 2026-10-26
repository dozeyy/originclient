import com.origin.client.client.mods.StepAside;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-logic checks for StepAside (src/mods/shared/.../mods/StepAside.java): the table
 * resolve, the effective on/off a feature reads as, the switch writes (the player's
 * forced-on flag) and the config contract (old configs read unchanged; forcing on then
 * off leaves the mod block exactly as it was). Run by run.py; exit 1 on any failure.
 */
public class StepAsideTests {
	static int failures = 0;

	static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL: " + what);
		} else {
			System.out.println("ok:   " + what);
		}
	}

	/** Mirrors Mods.setOn for a replaced feature onto a plain map (what ModsConfig stores per mod). */
	static void press(Map<String, Object> block, String feature, boolean want) {
		if (StepAside.replaced(feature) == null) {
			block.put("enabled", want);
			return;
		}
		StepAside.Write w = StepAside.toggle(feature, want);
		if (w.forced()) {
			block.put(StepAside.FORCED_KEY, true);
		} else {
			block.remove(StepAside.FORCED_KEY);
		}
		if (w.enabled() != null) {
			block.put("enabled", w.enabled());
		}
	}

	/** Mirrors Mods.on: the stored switch (default when absent), then StepAside. */
	static boolean on(Map<String, Object> block, String feature, boolean defaultOn) {
		Object e = block.get("enabled");
		boolean enabled = e == null ? defaultOn : (Boolean) e;
		boolean forced = Boolean.TRUE.equals(block.get(StepAside.FORCED_KEY));
		return StepAside.any() ? StepAside.effectiveOn(feature, enabled, forced) : enabled;
	}

	static boolean ascii(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				return false;
			}
		}
		return true;
	}

	public static void main(String[] args) {
		// --- the table itself ---
		Set<String> features = new HashSet<>();
		boolean unique = true, lower = true;
		for (StepAside.Row r : StepAside.TABLE) {
			unique &= features.add(r.feature());
			for (String id : r.foreignIds()) {
				lower &= id.equals(id.toLowerCase()) && !id.isBlank();
			}
		}
		check(unique, "every Origin feature appears once in the table");
		check(lower, "every foreign mod id is a lower-case Fabric id");
		check(features.containsAll(List.of("freelook", "zoom", "saturation", "worldeditcui", "jei")),
				"table covers freelook, zoom, saturation, worldeditcui, jei");

		// --- nothing resolved yet / nothing loaded: exactly today's behaviour ---
		check(!StepAside.any(), "before resolve nothing is replaced");
		Map<String, Object> old = new HashMap<>(Map.of("enabled", true, "key", 67));
		check(on(old, "freelook", true), "unresolved: freelook reads its own switch (on)");
		StepAside.resolve(id -> null);
		check(!StepAside.any(), "no overlapping mod loaded: nothing replaced");
		check(StepAside.replaced("freelook") == null && StepAside.cardNote("freelook", false) == null,
				"no overlapping mod: no card note");
		Map<String, Object> plain = new HashMap<>(old);
		press(plain, "freelook", false);
		check(plain.equals(Map.of("enabled", false, "key", 67)), "no overlap: the switch writes only 'enabled' (as before)");

		// --- a lookup that throws counts as absent ---
		StepAside.resolve(id -> {
			throw new IllegalStateException("boom");
		});
		check(!StepAside.any(), "a throwing lookup replaces nothing");

		// --- Freecam loaded ---
		Map<String, String> loaded = Map.of("freecam", "Freecam");
		StepAside.resolve(loaded::get);
		check(StepAside.any(), "freecam loaded: something is replaced");
		check(StepAside.replaced("freelook") != null && "Freecam".equals(StepAside.replaced("freelook").by()),
				"freelook is replaced by 'Freecam' (the mod's display name)");
		check(StepAside.replaced("zoom") == null, "zoom is not replaced by freecam");
		check(StepAside.replacedFeatures().equals(List.of("freelook")), "replacedFeatures() = [freelook]");

		Map<String, Object> fl = new HashMap<>(old);            // an old config: no forcedOn key
		check(!on(fl, "freelook", true), "old config + freecam: Origin's freelook is off (stepped aside)");
		check(StepAside.aside("freelook", false), "aside(freelook, not forced) = true");
		check("Replaced by Freecam".equals(StepAside.cardNote("freelook", false)), "card note reads 'Replaced by Freecam'");
		check(on(new HashMap<>(), "zoom", true), "an unreplaced feature keeps its default (zoom on)");

		press(fl, "freelook", true);                            // player turns it back on
		check(Boolean.TRUE.equals(fl.get(StepAside.FORCED_KEY)) && Boolean.TRUE.equals(fl.get("enabled")),
				"switch on: writes forcedOn=true and enabled=true");
		check(on(fl, "freelook", true), "forced on: Origin's freelook runs alongside Freecam");
		check(!StepAside.aside("freelook", true), "aside(freelook, forced) = false");
		check("Kept on with Freecam".equals(StepAside.cardNote("freelook", true)), "forced card note reads 'Kept on with Freecam'");

		press(fl, "freelook", false);                           // and off again
		check(!fl.containsKey(StepAside.FORCED_KEY), "switch off: the forcedOn key is removed");
		check(fl.equals(old), "on-then-off leaves the mod block exactly as the old config had it");
		check(!on(fl, "freelook", true), "switch off: stepped aside again");

		// Forced, then the player removes Freecam: the feature is just its own switch again.
		press(fl, "freelook", true);
		StepAside.resolve(id -> null);
		check(on(fl, "freelook", true), "freecam removed after forcing: freelook on (its own switch)");
		Map<String, Object> wasOff = new HashMap<>(Map.of("enabled", false));
		StepAside.resolve(loaded::get);
		press(wasOff, "freelook", false);                       // off while stepped aside
		StepAside.resolve(id -> null);
		check(!on(wasOff, "freelook", true), "a player's own 'off' survives the other mod coming and going");

		// --- first id in a row wins; the player's own JEI (asideOn row) ---
		StepAside.resolve(Map.of("jei", "Just Enough Items")::get);
		check(StepAside.replaced("jei") != null, "a player's own JEI replaces Origin's JEI switch");
		Map<String, Object> jei = new HashMap<>();              // card never touched: default off
		check(on(jei, "jei", false), "own JEI: Origin's switch stops hiding it (reads on)");
		press(jei, "jei", false);
		check(Boolean.TRUE.equals(jei.get(StepAside.FORCED_KEY)) && Boolean.FALSE.equals(jei.get("enabled")),
				"own JEI, switch off: Origin takes control and hides it");
		check(!on(jei, "jei", false), "own JEI forced: Origin's switch (off) wins");
		press(jei, "jei", true);
		check(!jei.containsKey(StepAside.FORCED_KEY) && on(jei, "jei", false),
				"own JEI, switch on again: force cleared, back to leaving it alone");

		// --- display names: ASCII only, capped, never blank ---
		StepAside.resolve(Map.of("appleskin", "AppleéSkin ✨  Mod")::get);
		String by = StepAside.replaced("saturation").by();
		check(ascii(by) && by.equals("AppleSkin Mod"), "non-ASCII stripped from the display name: '" + by + "'");
		StepAside.resolve(Map.of("zoomify", "✨✨")::get);
		check("zoomify".equals(StepAside.replaced("zoom").by()), "an all-non-ASCII name falls back to the mod id");
		StepAside.resolve(Map.of("zoomify", "Z".repeat(80))::get);
		check(StepAside.replaced("zoom").by().length() <= 32, "display names are capped at 32 chars");

		// --- every note is ASCII and names the replacing mod ---
		StepAside.resolve(Map.of("freecam", "Freecam", "zoomify", "Zoomify", "appleskin", "AppleSkin",
				"worldeditcui", "WorldEditCUI", "jei", "Just Enough Items")::get);
		check(StepAside.replacedFeatures().size() == StepAside.TABLE.size(), "every row resolves when its mod is loaded");
		boolean notesOk = true;
		for (StepAside.Row r : StepAside.TABLE) {
			for (boolean forced : new boolean[]{false, true}) {
				String c = StepAside.cardNote(r.feature(), forced);
				String p = StepAside.pageNote(r.feature(), "Name", forced);
				String by2 = StepAside.replaced(r.feature()).by();
				notesOk &= c != null && p != null && ascii(c) && ascii(p) && c.contains(by2) && p.contains(by2) && c.length() <= 40;
			}
		}
		check(notesOk, "card + page notes: ASCII, name the mod, card note <= 40 chars");

		System.out.println(failures == 0 ? "PASS: StepAside" : "FAILED: " + failures);
		System.exit(failures == 0 ? 0 : 1);
	}
}
