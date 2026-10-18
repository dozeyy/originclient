package com.origin.client.client;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The game's half of the launcher-to-game startup handshake (the launcher's
 * half, and the why: Core/Launch/GameHandoff.cs; docs/MOTION.md). The launcher
 * passes a per-launch token as -Dorigin.handoff=&lt;token&gt;; this prints one
 * log line per stage, "origin-handoff &lt;token&gt; &lt;stage&gt;", which the
 * launcher reads from the game's stdout it already captures:
 *   hello   - "I speak the handshake"
 *   loading - the game's loading screen is on screen (the launcher lowers its
 *             progress screen)
 *   ready   - the loading screen is gone (title screen, or a server join)
 * Each stage is said once. No token (a launch from anything but Origin
 * Launcher, a dev run) means nothing is ever printed. Purely informational:
 * nothing here changes how the game starts, and the launcher has a fallback
 * for every line that never comes.
 *
 * 1.8.9: Forge has no pre-launch entrypoint, so hello comes from the earliest
 * hook the module has: the @Mod class's construction ({@link #startup}, from
 * OriginClient's constructor). By then FML's own loading splash (themed
 * charcoal by the launcher) is already on screen, so loading follows at once.
 * Ready is the first END client tick: Minecraft only starts ticking after
 * startGame has closed the splash. Printed through the mod's log4j logger,
 * which Forge's console appender writes to stdout behind a "[time]
 * [thread/INFO] [originclient]: " prefix; the launcher matches the phrase
 * anywhere in the line.
 *
 * Touches no Minecraft class, so it is safe at mod construction.
 */
public final class OriginHandoff {
	private static final String TOKEN = readToken();
	private static final int HELLO = 1, LOADING = 2, READY = 4;
	private static final AtomicInteger said = new AtomicInteger();

	private OriginHandoff() {
	}

	/** Mod construction: the handshake's hello, and FML's splash is the loading screen already up. */
	public static void startup() {
		say(HELLO, "hello");
		say(LOADING, "loading");
	}

	/** END client tick: the game loop is running, so the splash is gone. */
	public static void tick() {
		if (TOKEN == null || (said.get() & READY) != 0) {
			return;
		}
		say(HELLO, "hello");       // no-ops when construction already said them
		say(LOADING, "loading");
		say(READY, "ready");
	}

	private static void say(int bit, String stage) {
		if (TOKEN == null) {
			return;
		}
		int before = said.getAndUpdate(v -> v | bit);
		if ((before & bit) == 0) {
			com.origin.client.OriginClient.LOGGER.info("origin-handoff {} {}", TOKEN, stage);
		}
	}

	// The launcher's token is 32 lowercase hex characters; anything else is
	// not from it, and is ignored rather than echoed into the log.
	private static String readToken() {
		String t = System.getProperty("origin.handoff");
		return t != null && t.matches("[0-9a-f]{16,64}") ? t : null;
	}
}
