import com.origin.client.client.mods.StepAside;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 1.8.9 module's own step-aside table (src/mods/versions/1.8.9/.../mods/StepAside.java,
 * a Java 8 hand copy with Forge mod ids). Compiled on its own by run.py, so it never sees
 * the shared copy. Checks the Forge ids map to the right Origin feature, that ids match
 * exactly (Forge's Loader.isModLoaded is case-sensitive), and that side-by-side mods
 * (Patcher, minimaps, keystrokes) never switch an Origin mod off.
 */
public class StepAside189Tests {
	static int failures = 0;

	static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL: " + what);
		} else {
			System.out.println("ok:   " + what);
		}
	}

	/** Which feature one loaded Forge mod id switches off (null = none). */
	static String featureFor(String id) {
		StepAside.resolve(x -> x.equals(id) ? id : null);
		List<String> f = StepAside.replacedFeatures();
		return f.isEmpty() ? null : f.get(0);
	}

	public static void main(String[] args) {
		Map<String, String> expect = new HashMap<>();
		expect.put("djperspectivemod", "freelook");
		expect.put("freelook", "freelook");
		expect.put("bettersprinting", "togglesprint");
		expect.put("blur", "menublur");
		expect.put("motionblurmod", "motionblur");
		expect.put("sk1er_old_animations", "oldanimations");
		expect.put("overflowanimations", "oldanimations");
		expect.put("epiczoomer", "zoom");
		for (Map.Entry<String, String> e : expect.entrySet()) {
			check(e.getValue().equals(featureFor(e.getKey())), e.getKey() + " steps aside Origin's " + e.getValue());
		}

		for (String id : Arrays.asList("patcher", "keystrokesmod", "journeymap", "voxelmap", "xaerominimap",
				"BetterSprinting", "Blur", "optifine")) {
			check(featureFor(id) == null, id + " switches nothing off");
		}

		boolean exact = true;
		for (StepAside.Row r : StepAside.TABLE) {
			for (String id : r.foreignIds()) {
				exact &= id.equals(id.toLowerCase()) && id.equals(id.trim()) && !id.isEmpty();
			}
			exact &= !r.shippedByOrigin(); // nothing nested in Origin's 1.8.9 jar
		}
		check(exact, "every 1.8.9 id is the exact lowercase Forge modid; no row is shippedByOrigin");

		StepAside.resolve(x -> x.equals("bettersprinting") ? "Better Sprinting" : null);
		check("Replaced by Better Sprinting".equals(StepAside.cardNote("togglesprint", false)),
				"card note names Better Sprinting");
		check(StepAside.effectiveOn("togglesprint", true, true), "forced on: Origin's toggle sprint runs anyway");
		check(!StepAside.effectiveOn("togglesprint", true, false), "not forced: Origin's toggle sprint is off");

		System.out.println(failures == 0 ? "PASS: StepAside (1.8.9)" : "FAILED: " + failures);
		System.exit(failures == 0 ? 0 : 1);
	}
}
