package com.origin.client.client;

import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The game's half of the launcher-to-game startup handshake (the launcher's
 * half, and the why: Core/Launch/GameHandoff.cs; docs/MOTION.md). The launcher
 * passes a per-launch token as -Dorigin.handoff=&lt;token&gt;; this prints one
 * log line per stage, "origin-handoff &lt;token&gt; &lt;stage&gt;", which the
 * launcher reads from the game's stdout it already captures:
 *   hello   - pre-launch, before Minecraft even loads: "I speak the handshake"
 *   loading - the game's loading screen is on screen (the launcher lowers its
 *             progress screen)
 *   ready   - the loading screen is gone (title screen, or a Quick Play world)
 * Each stage is said once. No token (a launch from anything but Origin
 * Launcher, a dev run) means nothing is ever printed. Purely informational:
 * nothing here changes how the game starts (the window opens like vanilla's,
 * Will 2026-10-18), and the launcher has a fallback for every line that never
 * comes.
 *
 * Loaded as a Fabric preLaunch entrypoint, so the class must not touch any
 * Minecraft class during class init — Minecraft is only referenced inside
 * methods that run long after.
 */
public final class OriginHandoff implements PreLaunchEntrypoint {
	private static final Logger LOG = LogManager.getLogger("Origin");
	private static final String TOKEN = readToken();
	private static final int HELLO = 1, LOADING = 2, READY = 4;
	private static final AtomicInteger said = new AtomicInteger();

	@Override
	public void onPreLaunch() {
		say(HELLO, "hello");
	}

	/** The Origin (or, if it failed, vanilla) loading overlay drew a frame. */
	public static void loading() {
		say(LOADING, "loading");
	}

	/** END_CLIENT_TICK: the first tick without the startup overlay is the game being ready. */
	public static void tick(Minecraft client) {
		if ((said.get() & READY) != 0 || TOKEN == null) {
			return;
		}
		if (client.getOverlay() == null) {
			// A build whose loading screen never drew (overlay mixin failed to
			// apply) still owes the launcher its takeover line first.
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
}
