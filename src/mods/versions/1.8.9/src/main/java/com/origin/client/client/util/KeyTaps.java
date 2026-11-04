package com.origin.client.client.util;

/**
 * Counts key PRESSES between client ticks, so Origin's toggle keys (Toggle Sprint/Sneak,
 * Chunk Borders, the scoreboard / nametag / full-bright toggles, Toggle Zoom) catch a quick
 * tap (G121-7). Those keys are read once per tick (20 a second) as "down right now?"; a tap
 * that went down and up between two ticks (a 40 ms tap misses ~1 in 5, more at low FPS when
 * ticks bunch up in one frame) was never seen. Vanilla's own keys count press events and
 * never miss; this is the same idea for Origin's raw keys.
 *
 * <p>1.8.9: fed by OriginClientMod.onKeyInput (FML KeyInputEvent, LWJGL2 codes), only while no screen
 * is open: a letter typed into chat or a sign must never toggle anything, even when Enter
 * closes the screen before the next tick. Read with {@link #consume} once per tick per key.
 * LWJGL2's key queue and the client tick both run on the main thread: no locking.
 * Version-independent (plain ints): a copy of shared/'s (this module is outside shared sync).
 */
public final class KeyTaps {
	// LWJGL2 key codes run 1..255 here (GLFW 32..348 on the Fabric versions); anything outside is ignored.
	private static final int SIZE = 512;
	private static final boolean[] PRESSED = new boolean[SIZE];

	private KeyTaps() {
	}

	/** A key event: press (1) marks the key; release (0) and repeat (2) do nothing. */
	public static void onKey(int glfwKey, int action) {
		if (action == 1 && glfwKey > 0 && glfwKey < SIZE) {
			PRESSED[glfwKey] = true;
		}
	}

	/** True when {@code glfwKey} was pressed since the last call for that key; clears it. */
	public static boolean consume(int glfwKey) {
		if (glfwKey <= 0 || glfwKey >= SIZE) {
			return false;
		}
		boolean pressed = PRESSED[glfwKey];
		PRESSED[glfwKey] = false;
		return pressed;
	}
}
