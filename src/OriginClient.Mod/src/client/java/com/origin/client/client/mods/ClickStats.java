package com.origin.client.client.mods;

import java.util.ArrayDeque;

// Mouse button state + click history, fed by MouseHandlerMixin's onPress
// hook. Serves the CPS counter (clicks in the trailing second) and the
// Keystrokes overlay (live pressed state).
public final class ClickStats {
	public static volatile boolean leftDown = false;
	public static volatile boolean rightDown = false;

	private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

	private ClickStats() {
	}

	public static synchronized void onButton(int button, boolean pressed) {
		long now = System.currentTimeMillis();
		if (button == 0) {
			leftDown = pressed;
			if (pressed) {
				LEFT.addLast(now);
			}
		} else if (button == 1) {
			rightDown = pressed;
			if (pressed) {
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
