package com.origin.client.client.util;

/**
 * Auto Text Hotkey's modifier rule (JG-1, 2026-11-04), kept free of Minecraft types so
 * tools/qol-tests can run it on every module's copy.
 *
 * <p>A slot fires on its key plus its Modifier. "None" means the key on its own: a held
 * Ctrl, Shift or Alt does NOT block it, because Left Ctrl and Left Shift are vanilla's
 * Sprint and Sneak keys and a PvP player holds one of them almost all the time (the old
 * rule "None = no Ctrl/Shift/Alt held" made the hotkey dead while sprinting or sneaking).
 * The one exception keeps a plain G and a Ctrl + G slot apart: a "None" slot steps aside
 * while another enabled slot on the SAME key has its modifier held, so Ctrl + G sends only
 * the Ctrl slot's line.
 *
 * <p>Version-independent (plain ints and strings, Java 8), so it lives in shared/; the
 * `.no-shared-sync` modules carry identical copies (qol-tests runs each one).
 */
public final class HotkeyChord {
	private HotkeyChord() {
	}

	/**
	 * Whether slot {@code slot} fires, given that its own key is down right now.
	 * {@code keys[j]}: slot j's key code, or 0 when slot j is off or unbound (only compared
	 * for equality, so any key-code scheme works: GLFW, LWJGL2, mouse codes).
	 * {@code modes[j]}: slot j's Modifier ("None", "Ctrl", "Shift", "Alt"; anything else = None).
	 */
	public static boolean fires(int slot, int[] keys, String[] modes, boolean ctrl, boolean shift, boolean alt) {
		String mode = modes[slot];
		if (!isNone(mode)) {
			return held(mode, ctrl, shift, alt);
		}
		for (int j = 0; j < keys.length; j++) {
			if (j != slot && keys[j] != 0 && keys[j] == keys[slot]
					&& !isNone(modes[j]) && held(modes[j], ctrl, shift, alt)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isNone(String mode) {
		return !"Ctrl".equals(mode) && !"Shift".equals(mode) && !"Alt".equals(mode);
	}

	private static boolean held(String mode, boolean ctrl, boolean shift, boolean alt) {
		if ("Ctrl".equals(mode)) {
			return ctrl;
		}
		if ("Shift".equals(mode)) {
			return shift;
		}
		return alt;
	}
}
