package com.origin.client.client.util;

import net.minecraft.client.settings.GameSettings;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * GLFW <-> LWJGL2 key codes.
 *
 * WHY: every other version stores Origin keybinds in originclient-*.json as GLFW
 * codes, and the launcher's Settings Sync carries that file between versions.
 * 1.8.9 runs LWJGL 2 (no GLFW), so it keeps the FILE FORMAT in GLFW and
 * translates at the edge: {@code Mods.keyCode(...)} hands out LWJGL2 codes and
 * {@code Mods.set(...)} on a KEYBIND row stores GLFW.
 *
 * Conventions (the 1.8.9 side follows vanilla 1.8.9's own KeyBinding codes):
 *   - keyboard: org.lwjgl.input.Keyboard.KEY_* (1..255);
 *   - mouse button b: -100 + b (vanilla 1.8.9's convention);
 *   - unbound: -1 on BOTH sides (LWJGL's KEY_NONE 0 also reads as unbound).
 * The file's format is the one every version shares (Fabric modules:
 * util/KeyCodes there): GLFW keys, mouse button b as -100 + b -- the same number
 * as here, so a mouse bind passes through unchanged -- and -1 unbound. Until
 * 2026-11 this class wrote mouse button b as plain b, which every other version
 * read as an invalid keyboard key (the bind died there and glfwGetKey logged an
 * error every tick); such old values now read as unbound, and the first tick's
 * Controls sync (OriginClientMod.syncedKeyDown / tickTabList) writes the mouse
 * bind back in the new form.
 * A code with no counterpart translates to -1 (unbound), never to a wrong key.
 *
 * Keyboard layouts (bug run 10, INPUT-1): a GLFW key code is a key's PLACE (GLFW_KEY_W is
 * where W sits on a US keyboard, whatever is printed on it), but LWJGL 2 on Windows
 * numbers a key by the CHARACTER the layout gives it (WindowsKeyboard.handleKey ->
 * WindowsKeycodes.mapVirtualKeyToLWJGLCode(virtual key)). The tables below are the US
 * layout, where both agree; letters, digits and punctuation then go through the player's
 * layout (place -> virtual key -> LWJGL code), so an AZERTY player's Z key is GLFW_KEY_W
 * in the file and Keyboard.KEY_Z here -- the same key 1.21.1 uses. The launcher's
 * Settings Sync does the same for vanilla's keys (Core/Sync/LegacyKeyLayout.cs).
 * The Mac's LWJGL 2 numbers keys by place already; Linux (X keysyms) keeps the US reading.
 * Schema defaults (ModOption.defKey) are places too, like every other version's defaults:
 * {@link #fromPlace} turns one into the code to watch.
 */
public final class KeyCodes {
	public static final int UNBOUND = -1;
	/** vanilla 1.8.9: mouse button b is bound as MOUSE_OFFSET + b. */
	public static final int MOUSE_OFFSET = -100;

	private static final int[] GLFW_TO_LWJGL = new int[349];
	private static final int[] LWJGL_TO_GLFW = new int[256];

	// Places (US-layout LWJGL codes) a layout can give another character: the number row with
	// - and =, the letter rows with their punctuation, ` and backslash, and 0x56 (the ISO key left of Z).
	private static final boolean[] LAYOUT_PLACE = new boolean[256];
	// Windows virtual key -> LWJGL 2 code, for the virtual keys a layout can put on those
	// places: LWJGL 2's own WindowsKeycodes table (anything else reads as KEY_NONE there too).
	private static final int[] VK_TO_LWJGL = new int[256];
	// This PC's layout: place -> LWJGL code and back, -1 = no counterpart; null = the US
	// reading (not Windows, or the layout couldn't be read). Read once, on the game thread.
	private static int[] placeToLive, liveToPlace;
	private static boolean layoutRead;

	static {
		java.util.Arrays.fill(GLFW_TO_LWJGL, UNBOUND);
		java.util.Arrays.fill(LWJGL_TO_GLFW, UNBOUND);
		// printable
		map(32, Keyboard.KEY_SPACE);
		map(39, Keyboard.KEY_APOSTROPHE);
		map(44, Keyboard.KEY_COMMA);
		map(45, Keyboard.KEY_MINUS);
		map(46, Keyboard.KEY_PERIOD);
		map(47, Keyboard.KEY_SLASH);
		map(48, Keyboard.KEY_0);
		map(49, Keyboard.KEY_1);
		map(50, Keyboard.KEY_2);
		map(51, Keyboard.KEY_3);
		map(52, Keyboard.KEY_4);
		map(53, Keyboard.KEY_5);
		map(54, Keyboard.KEY_6);
		map(55, Keyboard.KEY_7);
		map(56, Keyboard.KEY_8);
		map(57, Keyboard.KEY_9);
		map(59, Keyboard.KEY_SEMICOLON);
		map(61, Keyboard.KEY_EQUALS);
		map(65, Keyboard.KEY_A);
		map(66, Keyboard.KEY_B);
		map(67, Keyboard.KEY_C);
		map(68, Keyboard.KEY_D);
		map(69, Keyboard.KEY_E);
		map(70, Keyboard.KEY_F);
		map(71, Keyboard.KEY_G);
		map(72, Keyboard.KEY_H);
		map(73, Keyboard.KEY_I);
		map(74, Keyboard.KEY_J);
		map(75, Keyboard.KEY_K);
		map(76, Keyboard.KEY_L);
		map(77, Keyboard.KEY_M);
		map(78, Keyboard.KEY_N);
		map(79, Keyboard.KEY_O);
		map(80, Keyboard.KEY_P);
		map(81, Keyboard.KEY_Q);
		map(82, Keyboard.KEY_R);
		map(83, Keyboard.KEY_S);
		map(84, Keyboard.KEY_T);
		map(85, Keyboard.KEY_U);
		map(86, Keyboard.KEY_V);
		map(87, Keyboard.KEY_W);
		map(88, Keyboard.KEY_X);
		map(89, Keyboard.KEY_Y);
		map(90, Keyboard.KEY_Z);
		map(91, Keyboard.KEY_LBRACKET);
		map(92, Keyboard.KEY_BACKSLASH);
		map(93, Keyboard.KEY_RBRACKET);
		map(96, Keyboard.KEY_GRAVE);
		// GLFW_KEY_WORLD_2: the ISO "<>" key. LWJGL 2 names no constant for it, but
		// reports its DirectInput scancode DIK_OEM_102 (0x56) as the key code.
		map(162, 0x56);
		// function / editing
		map(256, Keyboard.KEY_ESCAPE);
		map(257, Keyboard.KEY_RETURN);
		map(258, Keyboard.KEY_TAB);
		map(259, Keyboard.KEY_BACK);
		map(260, Keyboard.KEY_INSERT);
		map(261, Keyboard.KEY_DELETE);
		map(262, Keyboard.KEY_RIGHT);
		map(263, Keyboard.KEY_LEFT);
		map(264, Keyboard.KEY_DOWN);
		map(265, Keyboard.KEY_UP);
		map(266, Keyboard.KEY_PRIOR);
		map(267, Keyboard.KEY_NEXT);
		map(268, Keyboard.KEY_HOME);
		map(269, Keyboard.KEY_END);
		map(280, Keyboard.KEY_CAPITAL);
		map(281, Keyboard.KEY_SCROLL);
		map(282, Keyboard.KEY_NUMLOCK);
		map(283, Keyboard.KEY_SYSRQ);         // print screen
		map(284, Keyboard.KEY_PAUSE);
		map(290, Keyboard.KEY_F1);
		map(291, Keyboard.KEY_F2);
		map(292, Keyboard.KEY_F3);
		map(293, Keyboard.KEY_F4);
		map(294, Keyboard.KEY_F5);
		map(295, Keyboard.KEY_F6);
		map(296, Keyboard.KEY_F7);
		map(297, Keyboard.KEY_F8);
		map(298, Keyboard.KEY_F9);
		map(299, Keyboard.KEY_F10);
		map(300, Keyboard.KEY_F11);
		map(301, Keyboard.KEY_F12);
		map(302, Keyboard.KEY_F13);
		map(303, Keyboard.KEY_F14);
		map(304, Keyboard.KEY_F15);
		map(305, Keyboard.KEY_F16);
		map(306, Keyboard.KEY_F17);
		map(307, Keyboard.KEY_F18);
		map(308, Keyboard.KEY_F19);
		// keypad
		map(320, Keyboard.KEY_NUMPAD0);
		map(321, Keyboard.KEY_NUMPAD1);
		map(322, Keyboard.KEY_NUMPAD2);
		map(323, Keyboard.KEY_NUMPAD3);
		map(324, Keyboard.KEY_NUMPAD4);
		map(325, Keyboard.KEY_NUMPAD5);
		map(326, Keyboard.KEY_NUMPAD6);
		map(327, Keyboard.KEY_NUMPAD7);
		map(328, Keyboard.KEY_NUMPAD8);
		map(329, Keyboard.KEY_NUMPAD9);
		map(330, Keyboard.KEY_DECIMAL);
		map(331, Keyboard.KEY_DIVIDE);
		map(332, Keyboard.KEY_MULTIPLY);
		map(333, Keyboard.KEY_SUBTRACT);
		map(334, Keyboard.KEY_ADD);
		map(335, Keyboard.KEY_NUMPADENTER);
		map(336, Keyboard.KEY_NUMPADEQUALS);
		// modifiers
		map(340, Keyboard.KEY_LSHIFT);
		map(341, Keyboard.KEY_LCONTROL);
		map(342, Keyboard.KEY_LMENU);         // left alt
		map(343, Keyboard.KEY_LMETA);
		map(344, Keyboard.KEY_RSHIFT);
		map(345, Keyboard.KEY_RCONTROL);
		map(346, Keyboard.KEY_RMENU);         // right alt
		map(347, Keyboard.KEY_RMETA);
		map(348, Keyboard.KEY_APPS);          // menu key

		LAYOUT_PLACE[0x56] = true;
		for (int[] range : new int[][] {{2, 13}, {16, 27}, {30, 41}, {43, 53}}) {
			for (int code = range[0]; code <= range[1]; code++) {
				LAYOUT_PLACE[code] = true;
			}
		}
		java.util.Arrays.fill(VK_TO_LWJGL, UNBOUND);
		String[] rows = {"1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
		int[] firsts = {Keyboard.KEY_1, Keyboard.KEY_Q, Keyboard.KEY_A, Keyboard.KEY_Z};
		for (int r = 0; r < rows.length; r++) {
			for (int i = 0; i < rows[r].length(); i++) {
				VK_TO_LWJGL[rows[r].charAt(i)] = firsts[r] + i;   // VK_0..9 / VK_A..Z are the characters
			}
		}
		VK_TO_LWJGL[0xBA] = Keyboard.KEY_SEMICOLON;   // VK_OEM_1
		VK_TO_LWJGL[0xBB] = Keyboard.KEY_EQUALS;      // VK_OEM_PLUS
		VK_TO_LWJGL[0xBC] = Keyboard.KEY_COMMA;       // VK_OEM_COMMA
		VK_TO_LWJGL[0xBD] = Keyboard.KEY_MINUS;       // VK_OEM_MINUS
		VK_TO_LWJGL[0xBE] = Keyboard.KEY_PERIOD;      // VK_OEM_PERIOD
		VK_TO_LWJGL[0xBF] = Keyboard.KEY_SLASH;       // VK_OEM_2
		VK_TO_LWJGL[0xC0] = Keyboard.KEY_GRAVE;       // VK_OEM_3
		VK_TO_LWJGL[0xDB] = Keyboard.KEY_LBRACKET;    // VK_OEM_4
		VK_TO_LWJGL[0xDC] = Keyboard.KEY_BACKSLASH;   // VK_OEM_5
		VK_TO_LWJGL[0xDD] = Keyboard.KEY_RBRACKET;    // VK_OEM_6
		VK_TO_LWJGL[0xDE] = Keyboard.KEY_APOSTROPHE;  // VK_OEM_7
		VK_TO_LWJGL[0xDF] = Keyboard.KEY_GRAVE;       // VK_OEM_8 (LWJGL 2 folds it into the same code)
	}

	private KeyCodes() {
	}

	private static void map(int glfw, int lwjgl) {
		GLFW_TO_LWJGL[glfw] = lwjgl;
		LWJGL_TO_GLFW[lwjgl] = glfw;
	}

	/** GLFW (file) code -> LWJGL2 (1.8.9) code on this keyboard; -1 when unbound or unmapped. */
	public static int glfwToLwjgl(int glfw) {
		if (isMouse(glfw)) {
			return glfw;                      // mouse: the same -100 + b on both sides
		}
		if (glfw < 32) {
			return UNBOUND;                   // no GLFW key is below 32 (old 0..7 mouse values included)
		}
		return glfw < GLFW_TO_LWJGL.length ? fromPlace(GLFW_TO_LWJGL[glfw]) : UNBOUND;
	}

	/** LWJGL2 (1.8.9) code on this keyboard -> GLFW (file) code; -1 when unbound or unmapped. */
	public static int lwjglToGlfw(int lwjgl) {
		if (isMouse(lwjgl)) {
			return lwjgl;
		}
		return placeToGlfw(toPlace(lwjgl));
	}

	/** A US-layout LWJGL2 code (a place, e.g. a schema default) -> GLFW code, no layout involved. */
	public static int placeToGlfw(int place) {
		if (isMouse(place)) {
			return place;
		}
		return place <= 0 || place >= LWJGL_TO_GLFW.length ? UNBOUND : LWJGL_TO_GLFW[place];
	}

	/** A place (US-layout LWJGL2 code) -> the code LWJGL 2 reports for that key on this keyboard. */
	public static int fromPlace(int place) {
		if (place <= 0 || place >= 256 || !LAYOUT_PLACE[place] || !readLayout()) {
			return place;
		}
		return placeToLive[place];
	}

	private static int toPlace(int live) {
		if (live <= 0 || live >= 256 || !LAYOUT_PLACE[live] || !readLayout()) {
			return live;
		}
		return liveToPlace[live];
	}

	/**
	 * Reads this PC's layout once; false = use the US reading. Windows only, through LWJGL 2's
	 * own MapVirtualKey (WindowsKeyboard, private native): it answers for the calling thread's
	 * layout, the same one the game window's key events use. Until the game window exists the
	 * native isn't usable, so it answers false then and tries again later.
	 */
	private static synchronized boolean readLayout() {
		if (layoutRead) {
			return placeToLive != null;
		}
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			layoutRead = true;
			return false;
		}
		try {
			if (!org.lwjgl.opengl.Display.isCreated()) {
				return false;
			}
			java.lang.reflect.Method map = Class.forName("org.lwjgl.opengl.WindowsKeyboard")
					.getDeclaredMethod("MapVirtualKey", int.class, int.class);
			map.setAccessible(true);
			int[] toLive = new int[256];
			int[] toPlace = new int[256];
			int[] count = new int[256];
			java.util.Arrays.fill(toLive, UNBOUND);
			java.util.Arrays.fill(toPlace, UNBOUND);
			for (int place = 0; place < 256; place++) {
				if (LAYOUT_PLACE[place]) {
					int vk = (Integer) map.invoke(null, place, 1);   // MAPVK_VSC_TO_VK
					int live = vk > 0 && vk < 256 ? VK_TO_LWJGL[vk] : UNBOUND;
					if (live > 0) {
						toLive[place] = live;
						count[live]++;
					}
				}
			}
			for (int place = 0; place < 256; place++) {
				int live = toLive[place];
				if (live > 0 && count[live] > 1) {
					toLive[place] = UNBOUND;     // two keys give one code (UK ' and `): neither can be told apart
				} else if (live > 0) {
					toPlace[live] = place;
				}
			}
			placeToLive = toLive;
			liveToPlace = toPlace;
		} catch (Throwable t) {
			// LWJGL without that native: the US reading, as before
		}
		layoutRead = true;
		return placeToLive != null;
	}

	/** A 1.8.9 mouse-button code (-100 .. -93)? */
	public static boolean isMouse(int lwjgl) {
		return lwjgl >= MOUSE_OFFSET && lwjgl < MOUSE_OFFSET + 8;
	}

	/**
	 * Is this LWJGL2 code held right now? Handles unbound (false), mouse buttons
	 * (Mouse.isButtonDown) and keys (Keyboard.isKeyDown) -- Keyboard.isKeyDown
	 * itself would throw on a negative index. A key held as part of an F3 combo reads as up.
	 * Never throws.
	 */
	public static boolean isDown(int lwjgl) {
		try {
			if (isMouse(lwjgl)) {
				int b = lwjgl - MOUSE_OFFSET;
				return b < Mouse.getButtonCount() && Mouse.isButtonDown(b);
			}
			boolean down = lwjgl > 0 && lwjgl < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(lwjgl);
			// F3+B, F3+G ... are vanilla's debug combos: a key pressed while F3 was held never
			// drives an Origin feature, even if F3 is let go first (util/KeyTaps, BMOD-1).
			return down && !KeyTaps.inF3Combo(lwjgl, down);
		} catch (Throwable t) {
			return false;
		}
	}

	/** Display name for an LWJGL2 code ("None" when unbound), vanilla's own wording. */
	public static String name(int lwjgl) {
		if (lwjgl == UNBOUND || lwjgl == 0) {
			return "None";
		}
		try {
			return GameSettings.getKeyDisplayString(lwjgl);
		} catch (Throwable t) {
			return "Key " + lwjgl;
		}
	}
}
