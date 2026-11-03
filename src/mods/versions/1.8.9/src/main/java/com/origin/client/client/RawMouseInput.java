package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.java.games.input.Component;
import net.java.games.input.Controller;
import net.java.games.input.RawInputEnvironmentPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

// General > Raw Mouse Input on 1.8.9. LWJGL 2 has no raw-motion switch: its
// Windows mouse turns cursor positions (WM_MOUSEMOVE) into deltas, so Windows'
// pointer speed and Enhance Pointer Precision acceleration land on the camera.
// 1.21.1 avoids both with GLFW raw motion. Here the same counts come from
// JInput's raw plugin, which Minecraft 1.8.9 already ships (jar and natives).
//
// Polled once a frame on the render thread: no extra thread, no added latency
// (JInput's own queue thread collects the counts between polls). Anything wrong
// (no native, no mouse, a mouse raw input can't see) falls back to LWJGL's
// deltas for the session; the camera never stops working because of this class.
public final class RawMouseInput {
	private static final Logger LOG = LogManager.getLogger("Origin");
	// Frames in a row where the cursor moved but raw input saw nothing. One or two
	// can be timing; this many means raw input isn't reaching us for this mouse
	// (remote desktop, a VM's absolute pointer, one plugged in after startup).
	private static final int SILENT_LIMIT = 30;

	private static volatile List<Controller> mice;  // null until the loader thread finishes
	private static boolean loadStarted;
	private static boolean failed;
	private static int silentFrames;
	private static int rawX, rawY;

	private RawMouseInput() {}

	public static int dx() { return rawX; }
	public static int dy() { return rawY; }

	// Device setup talks to Windows, so it stays off the render thread. LWJGL's
	// deltas drive the camera until it's ready.
	private static void load() {
		loadStarted = true;
		Thread t = new Thread(() -> {
			try {
				RawInputEnvironmentPlugin env = new RawInputEnvironmentPlugin();
				List<Controller> found = new ArrayList<>();
				if (env.isSupported()) {
					for (Controller c : env.getControllers()) {
						if (c.getType() == Controller.Type.MOUSE
								&& c.getComponent(Component.Identifier.Axis.X) != null
								&& c.getComponent(Component.Identifier.Axis.Y) != null) {
							found.add(c);
						}
					}
				}
				if (found.isEmpty()) {
					failed = true;
					LOG.info("Raw mouse input: no mouse found, using standard input");
				} else {
					LOG.info("Raw mouse input: on, {} device(s)", found.size());
				}
				mice = found;
			} catch (Throwable e) {
				failed = true;
				LOG.info("Raw mouse input: unavailable, using standard input ({})", e.toString());
			}
		}, "Origin Raw Mouse");
		t.setDaemon(true);
		t.start();
	}

	private static boolean enabled() {
		if (failed || !Mods.bool(Mods.GENERAL_ID, "rawMouseInput")) return false;
		if (!loadStarted) load();
		return mice != null && !failed;
	}

	// Throws away motion from while the mouse was free (menus, alt-tab), so the
	// camera doesn't jump when it's grabbed again.
	public static void drain() {
		try {
			if (enabled()) poll();
		} catch (Throwable e) {
			fail(e);
		}
		silentFrames = 0;
	}

	// True when dx()/dy() should replace LWJGL's deltas for this frame.
	public static boolean frame(int cursorDx, int cursorDy) {
		try {
			if (!enabled()) return false;
			poll();
			if (rawX != 0 || rawY != 0) {
				silentFrames = 0;
			} else if ((cursorDx != 0 || cursorDy != 0) && ++silentFrames >= SILENT_LIMIT) {
				failed = true;
				LOG.info("Raw mouse input: no motion from the device, using standard input");
				return false;
			}
			return true;
		} catch (Throwable e) {
			fail(e);
			return false;
		}
	}

	private static void poll() {
		int x = 0, y = 0;
		for (Controller c : mice) {
			if (!c.poll()) continue;  // unplugged: the others still count
			x += (int) c.getComponent(Component.Identifier.Axis.X).getPollData();
			y += (int) c.getComponent(Component.Identifier.Axis.Y).getPollData();
		}
		rawX = x;
		rawY = -y;  // raw counts grow downward, Minecraft's deltaY grows upward
	}

	private static void fail(Throwable e) {
		failed = true;
		LOG.info("Raw mouse input: stopped, using standard input ({})", e.toString());
	}
}
