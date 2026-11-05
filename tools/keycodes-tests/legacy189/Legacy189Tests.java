import com.origin.client.client.util.KeyCodes;

/**
 * 1.8.9's util/KeyCodes (LWJGL 2 <-> the shared GLFW file format) on non-US keyboards (bug run 10,
 * INPUT-1). Run by run.py once per layout (-Dkeycodes.layout, the WindowsKeyboard stand-in) as
 * Windows, and once as Linux: the file must name the same KEY (its place) that 1.21.1 does.
 * GLFW codes: W 87, A 65, M 77, Y 89, Z 90, ; 59, ' 39, ` 96, space 32, F5 294, ISO key 162.
 */
public final class Legacy189Tests {
	private static int failures;

	private static void eq(String what, int want, int got) {
		if (want != got) {
			System.out.println("FAIL " + what + ": want " + want + ", got " + got);
			failures++;
		}
	}

	public static void main(String[] args) {
		String layout = args[0];
		// Never layout-dependent: the same everywhere.
		eq("space", 57, KeyCodes.glfwToLwjgl(32));
		eq("F5", 294, KeyCodes.lwjglToGlfw(63));
		eq("left shift", 340, KeyCodes.lwjglToGlfw(42));
		eq("mouse 4", -97, KeyCodes.lwjglToGlfw(-97));
		eq("unbound", -1, KeyCodes.fromPlace(-1));
		eq("KEY_NONE (Kana, Henkan, ...) can't be stored", -1, KeyCodes.lwjglToGlfw(0));
		// A schema default is a place: stored as that place's GLFW code whatever the layout.
		eq("default W stored", 87, KeyCodes.placeToGlfw(17));

		if ("linux".equals(layout) || "us".equals(layout)) {
			for (int glfw = 65; glfw <= 90; glfw++) {
				int code = KeyCodes.glfwToLwjgl(glfw);
				eq("letter " + (char) glfw + " round trip", glfw, KeyCodes.lwjglToGlfw(code));
			}
			eq("W", 17, KeyCodes.glfwToLwjgl(87));
			eq("default Y", 21, KeyCodes.fromPlace(21));
		}
		if ("us".equals(layout)) {
			eq("ISO key: no LWJGL 2 code on Windows", -1, KeyCodes.glfwToLwjgl(162));
		}
		if ("azerty".equals(layout)) {
			eq("GLFW W (AZERTY's Z key) -> KEY_Z", 44, KeyCodes.glfwToLwjgl(87));
			eq("GLFW A (AZERTY's Q key) -> KEY_Q", 16, KeyCodes.glfwToLwjgl(65));
			eq("KEY_Z -> GLFW W", 87, KeyCodes.lwjglToGlfw(44));
			eq("KEY_Q -> GLFW A", 65, KeyCodes.lwjglToGlfw(16));
			eq("KEY_M -> GLFW ;", 59, KeyCodes.lwjglToGlfw(50));
			eq("GLFW M -> KEY_COMMA", 51, KeyCodes.glfwToLwjgl(77));
			eq("KEY_GRAVE comes from two keys: not stored", -1, KeyCodes.lwjglToGlfw(41));
			eq("default W (forward's place) watches KEY_Z", 44, KeyCodes.fromPlace(17));
		}
		if ("qwertz".equals(layout)) {
			eq("GLFW Y (QWERTZ's Z key) -> KEY_Z", 44, KeyCodes.glfwToLwjgl(89));
			eq("KEY_Y -> GLFW Z", 90, KeyCodes.lwjglToGlfw(21));
			eq("default Y watches the key 1.21.1 uses", 44, KeyCodes.fromPlace(21));
		}
		if ("uk".equals(layout)) {
			eq("' and ` both give KEY_GRAVE: not stored", -1, KeyCodes.lwjglToGlfw(41));
			eq("GLFW ' has no 1.8.9 code here", -1, KeyCodes.glfwToLwjgl(39));
			eq("KEY_BACKSLASH is the ISO key", 162, KeyCodes.lwjglToGlfw(43));
		}
		System.out.println("1.8.9 KeyCodes, " + layout + ": " + (failures == 0 ? "ok" : failures + " failed"));
		System.exit(failures == 0 ? 0 : 1);
	}
}
