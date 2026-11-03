package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import java.util.ArrayDeque;

// Attack / Use presses for the CPS counter (presses in the trailing second) and
// the Keystrokes overlay (live held state). Counted from the Attack and Use
// keybinds, whatever they are bound to (mouse button or key) -- not raw mouse
// buttons 0/1, which counted the wrong thing for anyone who rebinds them.
// 1.8.9: fed by KeyBindingMixin's hook on KeyBinding.onTick, which
// Minecraft.runTick only reaches in the world (no screen open), so clicks in
// menus never count. Mouse buttons arrive there as button - 100.
public final class ClickStats {
	private static final ArrayDeque<Long> LEFT = new ArrayDeque<Long>();
	private static final ArrayDeque<Long> RIGHT = new ArrayDeque<Long>();

	private ClickStats() {
	}

	/** A key or button press the game turned into a keybind click. */
	public static void onClick(int keyCode) {
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (keyCode == 0 || mc == null || mc.gameSettings == null) {
				return;
			}
			boolean attack = keyCode == mc.gameSettings.keyBindAttack.getKeyCode();
			boolean use = keyCode == mc.gameSettings.keyBindUseItem.getKeyCode();
			if (attack || use) {
				record(attack, use);
			}
		} catch (Throwable t) {
			// counting is cosmetic: never let it break input
		}
	}

	private static synchronized void record(boolean attack, boolean use) {
		long now = System.currentTimeMillis();
		if (attack) {
			LEFT.addLast(now);
			trim(LEFT, now);
		}
		if (use) {
			RIGHT.addLast(now);
			trim(RIGHT, now);
		}
	}

	/** Attack is held (Keystrokes LMB). */
	public static boolean leftDown() {
		return isDown(true);
	}

	/** Use is held (Keystrokes RMB). */
	public static boolean rightDown() {
		return isDown(false);
	}

	private static boolean isDown(boolean attack) {
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc == null || mc.gameSettings == null) {
				return false;
			}
			KeyBinding k = attack ? mc.gameSettings.keyBindAttack : mc.gameSettings.keyBindUseItem;
			return k.isKeyDown();
		} catch (Throwable t) {
			return false;
		}
	}

	public static synchronized int leftCps() {
		return count(LEFT);
	}

	public static synchronized int rightCps() {
		return count(RIGHT);
	}

	private static int count(ArrayDeque<Long> q) {
		trim(q, System.currentTimeMillis());
		return q.size();
	}

	// Trimmed on every press too, not only when the CPS readout asks: with that
	// readout off nothing else drains the queue, and it grew one boxed Long per
	// click for the whole session. The cap is a backstop if the clock jumps back.
	private static void trim(ArrayDeque<Long> q, long now) {
		long cutoff = now - 1000;
		while (!q.isEmpty() && (q.peekFirst() < cutoff || q.size() > 128)) {
			q.removeFirst();
		}
	}
}
