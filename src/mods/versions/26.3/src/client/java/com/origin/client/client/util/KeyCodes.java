package com.origin.client.client.util;

/**
 * THE key format of Origin's keybind rows in originclient-mods.json.
 *
 * WHY: Settings Sync carries that file between every version, so every version
 * must read and write ONE format, whatever its own input layer uses:
 *   - a keyboard key is its GLFW key code (32..348);
 *   - mouse button b (0 = left, 1 = right, 2 = middle, 3.. = side buttons) is
 *     MOUSE_OFFSET + b, i.e. -100..-93 (vanilla 1.8.9's own convention, which the
 *     launcher's options.txt sync also uses: Core/Sync/OptionsTxtFormat.cs);
 *   - unbound is -1.
 * Versions whose input layer is not GLFW convert at the edge, in their own copy
 * of this class: 1.8.9 (LWJGL 2 codes) and 26.3 (SDL3 scancodes). On every GLFW
 * version {@link #fromFile} / {@link #toFile} only clean the value up.
 *
 * Old files: 1.8.9 used to store mouse button b as plain b (0..7), which every
 * other version then read as a keyboard key (an invalid GLFW key: the bind died
 * and glfwGetKey logged an error every tick). Anything outside the ranges above,
 * those values included, reads as unbound, never as a wrong key: 1..7 can't be
 * told apart from a pre-fix 26.3 file's SDL keys (A = 4). Nothing is lost: the
 * mouse bind itself lives in options.txt (Controls), and the first tick's key sync
 * copies a Controls bind into an unbound Origin key in this format.
 *
 * 26.3 (SDL3): this module's key codes are SDL scancodes (InputConstants.isKeyDown
 * reads SDL_GetKeyboardState; KEY_C is 6, Left Ctrl 224). The file stays GLFW, so
 * {@link #fromFile} maps GLFW -> SDL and {@link #toFile} maps SDL -> GLFW, exactly as
 * 1.8.9 does for LWJGL 2. Without it a Zoom key of C (GLFW 67) read as SDL 67 = F10,
 * and a key bound here (C = 6) reached every other version as GLFW 6, a dead key.
 * A key with no counterpart becomes UNBOUND, never a different key. Mouse codes
 * and UNBOUND are the same on both sides. A pre-fix 26.3 file holds SDL numbers;
 * those can't be told apart from GLFW ones, so such a key reads as some other key
 * (or none) until it is bound again once.
 *
 * Pure Java on purpose (no Minecraft imports): tools/keycodes-check compiles it.
 */
public final class KeyCodes {
	public static final int UNBOUND = -1;
	public static final int MOUSE_OFFSET = -100;
	private static final int MOUSE_BUTTONS = 8;
	private static final int GLFW_KEY_FIRST = 32;   // GLFW_KEY_SPACE: no GLFW key is lower
	private static final int GLFW_KEY_LAST = 348;   // GLFW_KEY_MENU

	private static final int[] GLFW_TO_SDL = new int[GLFW_KEY_LAST + 1];
	private static final int[] SDL_TO_GLFW = new int[512];

	static {
		java.util.Arrays.fill(GLFW_TO_SDL, UNBOUND);
		java.util.Arrays.fill(SDL_TO_GLFW, UNBOUND);
		// SDL scancodes are USB HID usage ids (SDL_scancode.h)
		for (int i = 0; i < 26; i++) {
			map(65 + i, 4 + i);           // A..Z
		}
		for (int i = 1; i <= 9; i++) {
			map(48 + i, 29 + i);          // 1..9
		}
		map(48, 39);                      // 0
		map(257, 40);                     // Enter
		map(256, 41);                     // Escape
		map(259, 42);                     // Backspace
		map(258, 43);                     // Tab
		map(32, 44);                      // Space
		map(45, 45);                      // -
		map(61, 46);                      // =
		map(91, 47);                      // [
		map(93, 48);                      // ]
		map(92, 49);                      // backslash
		map(59, 51);                      // ;
		map(39, 52);                      // '
		map(96, 53);                      // `
		map(44, 54);                      // ,
		map(46, 55);                      // .
		map(47, 56);                      // /
		map(280, 57);                     // Caps Lock
		for (int i = 0; i < 12; i++) {
			map(290 + i, 58 + i);         // F1..F12
		}
		map(283, 70);                     // Print Screen
		map(281, 71);                     // Scroll Lock
		map(284, 72);                     // Pause
		map(260, 73);                     // Insert
		map(268, 74);                     // Home
		map(266, 75);                     // Page Up
		map(261, 76);                     // Delete
		map(269, 77);                     // End
		map(267, 78);                     // Page Down
		map(262, 79);                     // Right
		map(263, 80);                     // Left
		map(264, 81);                     // Down
		map(265, 82);                     // Up
		map(282, 83);                     // Num Lock
		map(331, 84);                     // keypad /
		map(332, 85);                     // keypad *
		map(333, 86);                     // keypad -
		map(334, 87);                     // keypad +
		map(335, 88);                     // keypad Enter
		for (int i = 1; i <= 9; i++) {
			map(320 + i, 88 + i);         // keypad 1..9
		}
		map(320, 98);                     // keypad 0
		map(330, 99);                     // keypad .
		map(162, 100);                    // ISO "<>" key (GLFW_KEY_WORLD_2 / SDL_SCANCODE_NONUSBACKSLASH)
		map(348, 101);                    // Menu / Application
		map(336, 103);                    // keypad =
		for (int i = 0; i < 12; i++) {
			map(302 + i, 104 + i);        // F13..F24
		}
		map(341, 224);                    // Left Ctrl
		map(340, 225);                    // Left Shift
		map(342, 226);                    // Left Alt
		map(343, 227);                    // Left Super
		map(345, 228);                    // Right Ctrl
		map(344, 229);                    // Right Shift
		map(346, 230);                    // Right Alt
		map(347, 231);                    // Right Super
	}

	private KeyCodes() {
	}

	private static void map(int glfw, int sdl) {
		GLFW_TO_SDL[glfw] = sdl;
		SDL_TO_GLFW[sdl] = glfw;
	}

	/** File value (GLFW) -> this version's key code (SDL scancode); mouse codes unchanged. */
	public static int fromFile(int v) {
		int c = canonical(v);
		return c >= GLFW_KEY_FIRST ? GLFW_TO_SDL[c] : c;
	}

	/** This version's key code (SDL scancode) -> file value (GLFW); mouse codes unchanged. */
	public static int toFile(int v) {
		if (isMouse(v)) {
			return v;
		}
		return v > 0 && v < SDL_TO_GLFW.length ? SDL_TO_GLFW[v] : UNBOUND;
	}

	/** Any stored value in the one format: a GLFW key, a mouse code, or UNBOUND. */
	public static int canonical(int v) {
		if (v >= GLFW_KEY_FIRST && v <= GLFW_KEY_LAST) {
			return v;
		}
		if (isMouse(v)) {
			return v;
		}
		return UNBOUND;
	}

	/** A mouse-button code (-100 .. -93)? */
	public static boolean isMouse(int code) {
		return code >= MOUSE_OFFSET && code < MOUSE_OFFSET + MOUSE_BUTTONS;
	}

	/** Mouse button b (0 = left) as a key code. */
	public static int mouse(int button) {
		return button >= 0 && button < MOUSE_BUTTONS ? MOUSE_OFFSET + button : UNBOUND;
	}

	/** The button of a mouse code (0 = left). */
	public static int mouseButton(int code) {
		return code - MOUSE_OFFSET;
	}

	/**
	 * A mouse code's options.txt name (key.mouse.left / right / middle / key.mouse.4 ..),
	 * the form InputConstants.getKey(String) reads on every 1.13+ version. Going
	 * through the name keeps the button right even where a version numbers its
	 * mouse buttons differently (26.3 moved to SDL3).
	 */
	public static String mouseName(int code) {
		int b = mouseButton(code);
		switch (b) {
			case 0:
				return "key.mouse.left";
			case 1:
				return "key.mouse.right";
			case 2:
				return "key.mouse.middle";
			default:
				return "key.mouse." + (b + 1);
		}
	}

	/** The mouse code for an options.txt mouse key name; UNBOUND when it isn't one. */
	public static int mouseFromName(String name) {
		if (name == null || !name.startsWith("key.mouse.")) {
			return UNBOUND;
		}
		String s = name.substring("key.mouse.".length());
		if ("left".equals(s)) {
			return mouse(0);
		}
		if ("right".equals(s)) {
			return mouse(1);
		}
		if ("middle".equals(s)) {
			return mouse(2);
		}
		try {
			return mouse(Integer.parseInt(s) - 1);
		} catch (NumberFormatException e) {
			return UNBOUND;
		}
	}

	/** The Origin menu's label for a mouse code ("Mouse 4"). */
	public static String mouseLabel(int code) {
		return "Mouse " + (mouseButton(code) + 1);
	}
}
