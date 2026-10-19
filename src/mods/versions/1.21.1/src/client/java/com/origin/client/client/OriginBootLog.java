package com.origin.client.client;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only boot timeline: how long from the JVM starting to an interactive main
 * menu, and where that time goes. OFF unless the JVM is started with
 * {@code -Dorigin.bootLog=true} (dev runs: {@code ./gradlew runClient -PbootLog};
 * CI: .github/workflows/boot-time.yml). When off every hook is one static-final
 * check and nothing is printed.
 *
 * <p>Each mark is printed once, as {@code [OriginBoot] mark <name> <ms>} where ms
 * is JVM uptime (the same clock -Xlog:gc stamps), and the whole timeline is
 * printed again as one {@code [OriginBoot] summary} line once the title screen
 * is interactive. With {@code -Dorigin.bootLog.quit=true} the game then closes
 * itself a few seconds later, so CI can boot it again.
 *
 * <p>Loaded from the preLaunch entrypoint (OriginHandoff), so this class must
 * never touch a Minecraft class.
 */
public final class OriginBootLog {
	public static final boolean ENABLED = Boolean.getBoolean("origin.bootLog");
	/** Close the game this long after the title screen became interactive (ms). */
	public static final long QUIT_AFTER_MS = ENABLED && Boolean.getBoolean("origin.bootLog.quit") ? 4000L : -1L;
	private static final Logger LOG = LoggerFactory.getLogger("OriginBoot");
	private static final List<String> names = new ArrayList<>();
	private static final List<Long> times = new ArrayList<>();
	private static long interactiveAt = -1;

	private OriginBootLog() {
	}

	/** Record {@code name} at the current uptime; a name is recorded once. */
	public static synchronized void mark(String name) {
		if (!ENABLED || names.contains(name)) {
			return;
		}
		long ms = ManagementFactory.getRuntimeMXBean().getUptime();
		names.add(name);
		times.add(ms);
		LOG.info("[OriginBoot] mark {} {}", name, ms);
	}

	/** The title screen took input for the first time: print the timeline. */
	public static synchronized void interactive() {
		if (!ENABLED || interactiveAt >= 0) {
			return;
		}
		mark("title-interactive");
		interactiveAt = System.nanoTime();
		StringBuilder sb = new StringBuilder("[OriginBoot] summary");
		for (int i = 0; i < names.size(); i++) {
			sb.append(' ').append(names.get(i)).append('=').append(times.get(i));
		}
		LOG.info(sb.toString());
	}

	/** A free-form line for the boot log (CI reads it from the log). */
	public static void note(String text) {
		if (ENABLED) {
			LOG.info("[OriginBoot] note {}", text);
		}
	}

	/** True once the title screen has taken input (the summary is printed). */
	public static synchronized boolean isInteractive() {
		return interactiveAt >= 0;
	}

	/** True once the quit delay after the interactive title has passed (CI only). */
	public static boolean shouldQuit() {
		return QUIT_AFTER_MS >= 0 && interactiveAt >= 0 && (System.nanoTime() - interactiveAt) / 1_000_000L >= QUIT_AFTER_MS;
	}
}
