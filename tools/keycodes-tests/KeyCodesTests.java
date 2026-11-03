import com.origin.client.client.util.KeyCodes;

/**
 * Origin's one cross-version key format (util/KeyCodes). Run by run.py twice: once
 * against the shared (GLFW) copy, once against 26.3's SDL3 copy (argument "sdl").
 */
public final class KeyCodesTests {
	private static int failures;

	private static void eq(String what, long want, long got) {
		if (want != got) {
			System.out.println("FAIL " + what + ": want " + want + ", got " + got);
			failures++;
		}
	}

	public static void main(String[] args) {
		boolean sdl = args.length > 0 && "sdl".equals(args[0]);

		// The format itself, the same on every version.
		eq("unbound", -1, KeyCodes.fromFile(-1));
		for (int b = 0; b < 8; b++) {
			int code = KeyCodes.mouse(b);
			eq("mouse " + b + " code", -100 + b, code);
			eq("mouse " + b + " from file", code, KeyCodes.fromFile(code));
			eq("mouse " + b + " to file", code, KeyCodes.toFile(code));
			eq("mouse " + b + " by name", code, KeyCodes.mouseFromName(KeyCodes.mouseName(code)));
		}
		eq("key.mouse.left", KeyCodes.mouse(0), KeyCodes.mouseFromName("key.mouse.left"));
		eq("key.mouse.4", KeyCodes.mouse(3), KeyCodes.mouseFromName("key.mouse.4"));
		eq("not a mouse name", -1, KeyCodes.mouseFromName("key.keyboard.c"));
		// Old 1.8.9 files stored mouse button b as b; 0..31 are no GLFW key: unbound, never a key.
		for (int v = 0; v < 32; v++) {
			eq("old value " + v, -1, KeyCodes.fromFile(v));
		}
		eq("past GLFW_KEY_LAST", -1, KeyCodes.fromFile(349));
		eq("old 1.8.9 mouse sentinel range", -1, KeyCodes.fromFile(-101));

		if (!sdl) {
			for (int k = 32; k <= 348; k++) {
				eq("GLFW key " + k + " unchanged", k, KeyCodes.fromFile(k));
				eq("GLFW key " + k + " stored as is", k, KeyCodes.toFile(k));
			}
		} else {
			// 26.3: the file stays GLFW, the game reads SDL scancodes.
			eq("C", 6, KeyCodes.fromFile(67));
			eq("Left Ctrl (Toggle Sprint's default 341)", 224, KeyCodes.fromFile(341));
			eq("Left Alt", 226, KeyCodes.fromFile(342));
			eq("Tab", 43, KeyCodes.fromFile(258));
			eq("F9", 66, KeyCodes.fromFile(298));
			eq("[", 47, KeyCodes.fromFile(91));
			eq("SDL C stored as GLFW", 67, KeyCodes.toFile(6));
			eq("SDL F10 stored as GLFW", 299, KeyCodes.toFile(67));
			eq("SDL unknown key", -1, KeyCodes.toFile(0));
			int mapped = 0;
			for (int k = 32; k <= 348; k++) {
				int s = KeyCodes.fromFile(k);
				if (s == -1) {
					continue;
				}
				mapped++;
				eq("GLFW " + k + " -> SDL " + s + " -> GLFW", k, KeyCodes.toFile(s));
			}
			if (mapped < 110) {
				System.out.println("FAIL only " + mapped + " GLFW keys have an SDL scancode");
				failures++;
			}
			for (int s = 1; s < 512; s++) {
				int g = KeyCodes.toFile(s);
				if (g != -1) {
					eq("SDL " + s + " -> GLFW " + g + " -> SDL", s, KeyCodes.fromFile(g));
				}
			}
		}
		System.out.println(failures == 0 ? "OK: KeyCodes (" + (sdl ? "26.3 SDL3" : "GLFW") + ")" : failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
