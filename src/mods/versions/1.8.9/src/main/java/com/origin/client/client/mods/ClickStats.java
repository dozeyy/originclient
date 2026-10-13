package com.origin.client.client.mods;

import java.util.ArrayDeque;

// Mouse button state + click history. Serves the CPS counter (clicks in the
// trailing second) and the Keystrokes overlay (live pressed state).
// 1.8.9: fed by HudHooks from Forge's MouseEvent (every LWJGL mouse event while
// no screen is open -- Minecraft.runTick posts it before routing the click) and
// GuiScreenEvent.MouseInputEvent.Pre (the same events while a screen owns the
// mouse, counts=false); 1.16.5 used MouseHandlerMixin.onPress for both.
public final class ClickStats {
	public static volatile boolean leftDown = false;
	public static volatile boolean rightDown = false;

	private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

	private ClickStats() {
	}

	/**
	 * @param counts whether this press should count toward CPS. Held-state
	 *               (for the Keystrokes overlay) always updates; only the CPS
	 *               tally is gated, so clicks made in a menu ("cancelled"
	 *               clicks) don't inflate the counter.
	 */
	public static synchronized void onButton(int button, boolean pressed, boolean counts) {
		long now = System.currentTimeMillis();
		if (button == 0) {
			leftDown = pressed;
			if (pressed && counts) {
				LEFT.addLast(now);
			}
		} else if (button == 1) {
			rightDown = pressed;
			if (pressed && counts) {
				RIGHT.addLast(now);
			}
		}
	}

	public static synchronized int leftCps() {
		return count(LEFT);
	}

	public static synchronized int rightCps() {
		return count(RIGHT);
	}

	private static int count(ArrayDeque<Long> q) {
		long cutoff = System.currentTimeMillis() - 1000;
		while (!q.isEmpty() && q.peekFirst() < cutoff) {
			q.removeFirst();
		}
		return q.size();
	}
}
