package com.origin.client.client;

import com.mojang.blaze3d.platform.Window;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's half of the launcher-to-game startup handshake (the launcher's
 * half, and the why: Core/Launch/GameHandoff.cs; the visual spec:
 * docs/MOTION.md). The launcher passes a per-launch token as
 * -Dorigin.handoff=&lt;token&gt;; this prints one log line per stage,
 * "origin-handoff &lt;token&gt; &lt;stage&gt;", which the launcher reads from the
 * game's stdout it already captures:
 *   hello   - pre-launch, before Minecraft even loads: "I speak the handshake"
 *   window  - "x,y,w,h,scale": where the game window opened (window handoff below)
 *   loading - the Origin loading screen is on screen (with the window handoff:
 *             once the window has finished fading in)
 *   ready   - the loading screen is gone (title screen, or a Quick Play world)
 * Each stage is said once. No token (a launch from anything but Origin
 * Launcher, a dev run) means nothing is ever printed. Purely informational:
 * nothing here can change how the game starts, and the launcher has a
 * fallback for every line that never comes.
 *
 * WINDOW HANDOFF (launch experience v2, docs/MOTION.md). With
 * -Dorigin.handoff.window=cx,cy (the launcher's centre, screen pixels) the game
 * window is created hidden (WindowHandoffMixin), centred on that point (kept
 * inside that monitor's work area, title bar reachable), made fully transparent
 * and only then shown, and its rect is announced. The launcher glides its
 * loading scene onto exactly that rect; once Origin's loading screen has drawn a
 * few frames and the glide has had time to land, the window fades in over
 * RAMP_MS on top of the launcher's identical scene, so the player sees one
 * scene, never a window popping up. Fullscreen, no property, or any failure:
 * the window opens exactly as vanilla's (shown, opaque).
 *
 * Loaded as a Fabric preLaunch entrypoint, so the class must not touch any
 * Minecraft class during class init — Minecraft and GLFW are only referenced
 * inside methods that run long after.
 */
public final class OriginHandoff implements PreLaunchEntrypoint {
	private static final Logger LOG = LoggerFactory.getLogger("Origin");
	private static final String TOKEN = readToken();
	private static final int HELLO = 1, LOADING = 2, READY = 4;
	private static final AtomicInteger said = new AtomicInteger();

	/** The launcher's scene glides onto the window over its Motion.Intro (560 ms); a little slack. */
	private static final long GLIDE_MS = 620;
	/** The fade-in itself: the launcher's Motion.Handoff. */
	private static final long RAMP_MS = 320;
	/** Fade in by now whatever was (not) drawn: an invisible window must never linger. */
	private static final long SHOW_CEILING_MS = 3000;
	/** Origin loading frames on screen before the fade starts (the first frames can be blank). */
	private static final int SETTLE_FRAMES = 3;
	private static final int[] TARGET = readTarget();

	// Render thread only (the Window is created on it too), so plain fields.
	private static boolean hidden;        // the window was created hidden by us
	private static boolean revealed = true; // false while the fade-in is still owed
	private static long windowHandle;
	private static long windowShownAt;
	private static long rampStartAt;
	private static int originFrames;

	@Override
	public void onPreLaunch() {
		say(HELLO, "hello");
	}

	/** The Origin (or, if it failed, vanilla) loading overlay drew a frame. */
	public static void loading() {
		originFrames++;
		if (revealed) {
			say(LOADING, "loading");
		}
	}

	/** Window constructor, just before glfwCreateWindow: create it hidden if the launcher will hand off to it. */
	public static void beforeWindowCreate(boolean fullscreen) {
		if (TOKEN == null || TARGET == null || fullscreen) {
			return;
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		hidden = true;
	}

	/** Window constructor RETURN: place the hidden window, keep it transparent, show it, announce it. */
	public static void windowCreated(Window window) {
		if (!hidden) {
			return;
		}
		hidden = false;
		long handle = window.getWindow();
		try {
			int[] w = new int[1], h = new int[1];
			GLFW.glfwGetWindowSize(handle, w, h);
			int[] l = new int[1], t = new int[1], r = new int[1], b = new int[1];
			GLFW.glfwGetWindowFrameSize(handle, l, t, r, b);
			int x = TARGET[0] - w[0] / 2;
			int y = TARGET[1] - h[0] / 2;
			int[] area = workAreaAt(TARGET[0], TARGET[1]);
			if (area != null) {
				x = fit(x, area[0] + l[0], area[0] + area[2] - r[0] - w[0]);
				y = fit(y, area[1] + t[0], area[1] + area[3] - b[0] - h[0]);
			}
			GLFW.glfwSetWindowPos(handle, x, y);
			GLFW.glfwSetWindowOpacity(handle, 0f);
			GLFW.glfwShowWindow(handle);
			windowHandle = handle;
			windowShownAt = System.currentTimeMillis();
			revealed = false;
			int scale = 0;
			try {
				Minecraft mc = Minecraft.getInstance();
				scale = window.calculateScale(mc.options.guiScale().get(), mc.options.forceUnicodeFont().get());
			} catch (Throwable ignored) {
				// the launcher falls back to the auto-scale rule
			}
			LOG.info("origin-handoff {} window {},{},{},{},{}", TOKEN, x, y, w[0], h[0], scale);
		} catch (Throwable e) {
			LOG.warn("Origin window handoff failed; showing the window normally", e);
			showNow(handle);
		}
	}

	/**
	 * Every frame (GameRenderer.render HEAD): the window's fade-in, once the
	 * loading screen has settled and the launcher's glide has landed. Costs one
	 * boolean read once it is done.
	 */
	public static void frame() {
		if (revealed) {
			return;
		}
		long now = System.currentTimeMillis();
		if (rampStartAt == 0) {
			boolean settled = originFrames >= SETTLE_FRAMES && now - windowShownAt >= GLIDE_MS;
			if (!settled && now - windowShownAt < SHOW_CEILING_MS) {
				return;
			}
			rampStartAt = now;
		}
		double t = (now - rampStartAt) / (double) RAMP_MS;
		if (t >= 1.0) {
			finishReveal();
			return;
		}
		try {
			GLFW.glfwSetWindowOpacity(windowHandle, (float) (t * t * (3.0 - 2.0 * t)));
		} catch (Throwable e) {
			finishReveal();
		}
	}

	// Fully opaque (GLFW drops the layered style at 1), then the launcher's cue.
	private static void finishReveal() {
		showNow(windowHandle);
		say(LOADING, "loading");
	}

	private static void showNow(long handle) {
		revealed = true;
		try {
			GLFW.glfwSetWindowOpacity(handle, 1f);
			GLFW.glfwShowWindow(handle);
		} catch (Throwable ignored) {
			// nothing more to try; a window that exists is shown by GLFW anyway
		}
	}

	/** The work area (x, y, w, h) of the monitor holding the point, or null. */
	private static int[] workAreaAt(int px, int py) {
		PointerBuffer monitors = GLFW.glfwGetMonitors();
		if (monitors == null) {
			return null;
		}
		for (int i = 0; i < monitors.limit(); i++) {
			int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
			GLFW.glfwGetMonitorWorkarea(monitors.get(i), x, y, w, h);
			if (px >= x[0] && px < x[0] + w[0] && py >= y[0] && py < y[0] + h[0]) {
				return new int[] {x[0], y[0], w[0], h[0]};
			}
		}
		return null;
	}

	// Into [lo, hi]; a window bigger than the area keeps its top/left edge (the title bar) inside.
	private static int fit(int v, int lo, int hi) {
		return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
	}

	/** END_CLIENT_TICK: the first tick without the startup overlay is the game being ready. */
	public static void tick(Minecraft client) {
		if ((said.get() & READY) != 0 || TOKEN == null) {
			return;
		}
		if (client.getOverlay() == null) {
			// A build whose loading screen never drew (overlay mixin failed to
			// apply) still owes the launcher its takeover line first.
			if (!revealed) {
				finishReveal();
			}
			say(LOADING, "loading");
			say(READY, "ready");
		}
	}

	private static void say(int bit, String stage) {
		if (TOKEN == null) {
			return;
		}
		int before = said.getAndUpdate(v -> v | bit);
		if ((before & bit) == 0) {
			LOG.info("origin-handoff {} {}", TOKEN, stage);
		}
	}

	// The launcher's token is 32 lowercase hex characters; anything else is
	// not from it, and is ignored rather than echoed into the log.
	private static String readToken() {
		String t = System.getProperty("origin.handoff");
		return t != null && t.matches("[0-9a-f]{16,64}") ? t : null;
	}

	// "cx,cy" in screen pixels, from the launcher; anything else means no handoff.
	private static int[] readTarget() {
		String v = System.getProperty("origin.handoff.window");
		if (v == null || !v.matches("-?\\d{1,6},-?\\d{1,6}")) {
			return null;
		}
		String[] p = v.split(",");
		return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
	}
}
