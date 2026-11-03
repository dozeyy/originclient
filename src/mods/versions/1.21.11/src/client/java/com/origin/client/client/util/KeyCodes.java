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
 * Pure Java on purpose (Java 8, no Minecraft imports): the same file compiles
 * on every module and in a plain javac check.
 */
public final class KeyCodes {
	public static final int UNBOUND = -1;
	public static final int MOUSE_OFFSET = -100;
	private static final int MOUSE_BUTTONS = 8;
	private static final int GLFW_KEY_FIRST = 32;   // GLFW_KEY_SPACE: no GLFW key is lower
	private static final int GLFW_KEY_LAST = 348;   // GLFW_KEY_MENU

	private KeyCodes() {
	}

	/** File value -> this version's key code (GLFW: the same number, cleaned up). */
	public static int fromFile(int v) {
		return canonical(v);
	}

	/** This version's key code -> file value (GLFW: the same number, cleaned up). */
	public static int toFile(int v) {
		return canonical(v);
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
