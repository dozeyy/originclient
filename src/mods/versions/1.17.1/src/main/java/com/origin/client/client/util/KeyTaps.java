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
 * <p>Mouse buttons count the same way (INPUT-4): an Origin key bound to Mouse 4 / 5 (JG-4) is
 * read raw like the keys, so a quick side-button click missed the tick too. Their presses come
 * in through {@link #onMouse} and are stored under the Origin mouse code (util/KeyCodes,
 * -100 + button), so {@link #consume} takes whatever Mods.keyCode returned.
 *
 * <p>A press is only good for {@link #MAX_AGE_NANOS}: a feature that skipped its key for a while
 * (switched off, or a menu opened right after the press) must never act on an old press later.
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
 * Version-independent (plain ints): a copy of shared/'s (this module is outside shared sync).
 */
public final class KeyTaps {
	// GLFW key codes run 32..348 (GLFW_KEY_LAST); anything outside is ignored.
	private static final int SIZE = 512;
	// Mouse buttons 0..7 (util/KeyCodes: Origin code -100 + button) sit after the keys.
	private static final int MOUSE_OFFSET = -100;
	private static final int MOUSE_BUTTONS = 8;
	/** How long a press waits for its feature's tick: a second covers the slowest real frame. */
	static final long MAX_AGE_NANOS = 1_000_000_000L;
	private static final boolean[] PRESSED = new boolean[SIZE + MOUSE_BUTTONS];
	private static final long[] PRESSED_AT = new long[SIZE + MOUSE_BUTTONS];
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
			press(key, System.nanoTime());
		}
	}

	/**
	 * A mouse-button event: {@code button} 0 = left, 1 = right, 2 = middle, 3.. side buttons (the
	 * util/KeyCodes numbering), {@code action} press (1) / release (0). Clicks made with a screen open
	 * are clicks on that screen, never an Origin key.
	 */
	public static void onMouse(int button, int action, boolean screenOpen) {
		if (action != 1 || button < 0 || button >= MOUSE_BUTTONS || screenOpen) {
			return;
		}
		press(SIZE + button, System.nanoTime());
	}

	private static void press(int slot, long now) {
		PRESSED[slot] = true;
		PRESSED_AT[slot] = now;
	}

	// key code or Origin mouse code -> array slot, or -1 when it is neither
	private static int slot(int code) {
		if (code > 0 && code < SIZE) {
			return code;
		}
		if (code >= MOUSE_OFFSET && code < MOUSE_OFFSET + MOUSE_BUTTONS) {
			return SIZE + (code - MOUSE_OFFSET);
		}
		return -1;
	}

	/** True when {@code key} (a key code or an Origin mouse code) was pressed since the last call for
	 *  it, within the last second; clears it. */
	public static boolean consume(int key) {
		return consume(key, System.nanoTime());
	}

	/** {@link #consume(int)} at a given System.nanoTime() (tools/qol-tests checks the age limit). */
	public static boolean consume(int key, long now) {
		int s = slot(key);
		if (s < 0) {
			return false;
		}
		boolean pressed = PRESSED[s] && now - PRESSED_AT[s] <= MAX_AGE_NANOS;
		PRESSED[s] = false;
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
