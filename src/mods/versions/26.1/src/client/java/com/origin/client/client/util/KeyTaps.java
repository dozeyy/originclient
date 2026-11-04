package com.origin.client.client.util;

/**
 * Origin's raw-key bookkeeping, fed by key PRESS events.
 *
 * <p>1. Taps between ticks (G121-7). Origin's toggle keys (Toggle Sprint/Sneak, Chunk Borders,
 * the scoreboard / nametag / full-bright toggles, Toggle Zoom) are read once per tick (20 a
 * second) as "down right now?"; a tap that went down and up between two ticks (a 40 ms tap
 * misses ~1 in 5, more at low FPS when ticks bunch up in one frame) was never seen. Vanilla's
 * own keys count press events and never miss; {@link #consume} is the same idea for Origin's
 * raw keys. Only presses made while no screen is open count: a letter typed into chat or a
 * sign must never toggle anything, even when Enter closes the screen before the next tick.
 *
 * <p>2. F3 combos (BMOD-1). F3+B (hitboxes), F3+G (chunk borders), F3+C ... are vanilla's
 * debug combos; vanilla keeps them away from its own key bindings, and Origin's raw-key
 * features (Snaplook, Freelook, Zoom, Quickplay, the map, the toggles) must ignore them too.
 * A key pressed while F3 was held stays "part of an F3 combo" until it is up again
 * ({@link #inF3Combo}), so letting go of F3 a moment before B never swings the camera. The
 * next press made without F3 clears it, so no release events are needed (26.3's SDL input
 * has no release/repeat constants we rely on, and 1.8.9's FML event misses releases inside
 * screens).
 *
 * <p>Fed by each module's KeyTapsMixin (KeyboardHandler.keyPress HEAD; 1.8.9:
 * OriginClientMod.onKeyInput), which works out whether F3 was held and whether a screen is
 * open. Key callbacks and the client tick both run on the render thread: no locking.
 * Version-independent (plain ints), so it lives in shared/.
 */
public final class KeyTaps {
	// GLFW key codes run 32..348 (GLFW_KEY_LAST); anything outside is ignored.
	private static final int SIZE = 512;
	private static final boolean[] PRESSED = new boolean[SIZE];
	private static final boolean[] F3_COMBO = new boolean[SIZE];

	private KeyTaps() {
	}

	/**
	 * A key event. {@code action}: press (1); release (0) and repeat (2) do nothing.
	 * {@code f3Held}: F3 was down when this key went down (the caller passes false for F3
	 * itself). {@code screenOpen}: a screen had the keyboard, so the press is typing.
	 */
	public static void onKey(int key, int action, boolean f3Held, boolean screenOpen) {
		if (action != 1 || key <= 0 || key >= SIZE) {
			return;
		}
		F3_COMBO[key] = f3Held;
		if (!f3Held && !screenOpen) {
			PRESSED[key] = true;
		}
	}

	/** True when {@code key} was pressed since the last call for that key; clears it. */
	public static boolean consume(int key) {
		if (key <= 0 || key >= SIZE) {
			return false;
		}
		boolean pressed = PRESSED[key];
		PRESSED[key] = false;
		return pressed;
	}

	/**
	 * True while {@code key} is held as part of an F3 combo, so the raw "is it down?" read
	 * must say no. {@code downNow} is the key's live state: a key that is up is never part of
	 * a combo, which also forgets one whose release was never seen.
	 */
	public static boolean inF3Combo(int key, boolean downNow) {
		if (key <= 0 || key >= SIZE) {
			return false;
		}
		if (!downNow) {
			F3_COMBO[key] = false;
			return false;
		}
		return F3_COMBO[key];
	}
}
