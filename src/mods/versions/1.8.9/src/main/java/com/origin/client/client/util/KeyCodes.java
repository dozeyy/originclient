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
 * In the file, a mouse button b is stored as b -- its InputConstants MOUSE value
 * on the modern versions (0..7 are not GLFW keyboard codes, which start at 32).
 * A code with no counterpart translates to -1 (unbound), never to a wrong key.
 */
public final class KeyCodes {
	public static final int UNBOUND = -1;
	/** vanilla 1.8.9: mouse button b is bound as MOUSE_OFFSET + b. */
	public static final int MOUSE_OFFSET = -100;

	private static final int[] GLFW_TO_LWJGL = new int[349];
	private static final int[] LWJGL_TO_GLFW = new int[256];

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
	}

	private KeyCodes() {
	}

	private static void map(int glfw, int lwjgl) {
		GLFW_TO_LWJGL[glfw] = lwjgl;
		LWJGL_TO_GLFW[lwjgl] = glfw;
	}

	/** GLFW (file) code -> LWJGL2 (1.8.9) code; -1 when unbound or unmapped. */
	public static int glfwToLwjgl(int glfw) {
		if (glfw < 0) {
			return UNBOUND;
		}
		if (glfw <= 7) {
			return MOUSE_OFFSET + glfw;       // mouse button (InputConstants MOUSE value)
		}
		return glfw < GLFW_TO_LWJGL.length ? GLFW_TO_LWJGL[glfw] : UNBOUND;
	}

	/** LWJGL2 (1.8.9) code -> GLFW (file) code; -1 when unbound or unmapped. */
	public static int lwjglToGlfw(int lwjgl) {
		if (isMouse(lwjgl)) {
			return lwjgl - MOUSE_OFFSET;
		}
		if (lwjgl <= 0 || lwjgl >= LWJGL_TO_GLFW.length) {
			return UNBOUND;
		}
		return LWJGL_TO_GLFW[lwjgl];
	}

	/** A 1.8.9 mouse-button code (-100 .. -93)? */
	public static boolean isMouse(int lwjgl) {
		return lwjgl >= MOUSE_OFFSET && lwjgl < MOUSE_OFFSET + 8;
	}

	/**
	 * Is this LWJGL2 code held right now? Handles unbound (false), mouse buttons
	 * (Mouse.isButtonDown) and keys (Keyboard.isKeyDown) -- Keyboard.isKeyDown
	 * itself would throw on a negative index. Never throws.
	 */
	public static boolean isDown(int lwjgl) {
		try {
			if (isMouse(lwjgl)) {
				int b = lwjgl - MOUSE_OFFSET;
				return b < Mouse.getButtonCount() && Mouse.isButtonDown(b);
			}
			return lwjgl > 0 && lwjgl < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(lwjgl);
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
