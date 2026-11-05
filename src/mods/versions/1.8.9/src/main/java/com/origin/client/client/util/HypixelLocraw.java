package com.origin.client.client.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Hypixel game the player is in, from Hypixel's own /locraw reply (JG-11), so Quickplay's
 * "Play Again" knows the game however the player joined it: an NPC, a typed /play, a party warp or
 * a relaunch, not only a pick in Quickplay. Pure Java 8, no Minecraft types: shared/ syncs it into
 * the Fabric modules, the pre-1.20 modules and 1.8.9 (.no-shared-sync) carry byte-identical copies,
 * and tools/locraw-tests runs it on its own.
 *
 * Hypixel answers /locraw with one system line of JSON:
 *   {"server":"mini123A","gametype":"BEDWARS","mode":"BEDWARS_EIGHT_TWO","map":"Lighthouse"}
 * In a lobby the "mode" is missing (or "LOBBY"); SkyBlock's is the island ("hub", "dynamic"), not a
 * /play id. For every other game the mode, lowercased, IS the /play id ("bedwars_eight_two"), the
 * same mapping every Hypixel requeue mod uses.
 *
 * Asking: once per world (each Hypixel server switch loads a new world), ASK_DELAY_MS after it
 * appears, while Quickplay is on. The reply to Origin's own ask is hidden from chat; a /locraw the
 * player types shows as usual. Game thread only (tick and chat both run there); synchronized anyway.
 */
public final class HypixelLocraw {
	private HypixelLocraw() {
	}

	/** Hypixel needs a moment after the world loads before /locraw answers with the new server. */
	static final long ASK_DELAY_MS = 2_000;
	/** A reply this soon after Origin's ask is that ask's answer, hidden from chat. */
	static final long REPLY_WINDOW_MS = 10_000;

	private static final Pattern FIELD = Pattern.compile("\"(server|gametype|mode)\"\\s*:\\s*\"([^\"]*)\"");
	// a /play id is Hypixel's own lower-case word: anything else is never sent as a command
	private static final Pattern PLAY_ID = Pattern.compile("[a-z0-9_]{1,64}");

	private static Object world;
	private static long askAt, askedAt;
	private static String seen;

	/**
	 * Once per client tick. level = the current world (only its identity is used), wanted = Quickplay
	 * is on and the player is on Hypixel. True means: send /locraw now.
	 */
	public static synchronized boolean tick(Object level, boolean wanted, long now) {
		if (!wanted || level == null) {
			world = level;
			askAt = 0;
			return false;
		}
		if (level != world) {
			world = level;
			askAt = now + ASK_DELAY_MS;
		}
		if (askAt != 0 && now >= askAt) {
			askAt = 0;
			askedAt = now;
			return true;
		}
		return false;
	}

	/**
	 * An incoming system chat line (its plain text). A /locraw reply records the game (see takeSeen).
	 * False = hide it: the answer to Origin's own ask. Everything else is left alone (true).
	 */
	public static synchronized boolean onMessage(String plain, long now) {
		if (plain == null) {
			return true;
		}
		String t = plain.trim();
		if (!t.startsWith("{\"") || !t.endsWith("}") || !t.contains("\"server\"")) {
			return true;
		}
		String id = playId(t);
		if (id != null) {
			seen = id;
		}
		boolean ours = askedAt != 0 && now - askedAt <= REPLY_WINDOW_MS;
		if (ours) {
			askedAt = 0;
		}
		return !ours;
	}

	/** The /play id from the newest reply since the last call, or null (Quickplay's Play Again reads it). */
	public static synchronized String takeSeen() {
		String s = seen;
		seen = null;
		return s;
	}

	/** The /play id a /locraw reply names, or null: a lobby, SkyBlock, Housing, Limbo, or not a reply. */
	public static String playId(String json) {
		String server = null, gametype = null, mode = null;
		Matcher m = FIELD.matcher(json);
		while (m.find()) {
			if (m.group(1).equals("server")) {
				server = m.group(2);
			} else if (m.group(1).equals("gametype")) {
				gametype = m.group(2);
			} else {
				mode = m.group(2);
			}
		}
		if (server == null || mode == null || gametype == null) {
			return null;
		}
		String type = gametype.toUpperCase(Locale.ROOT);
		if (type.equals("SKYBLOCK") || type.equals("HOUSING") || type.equals("REPLAY") || type.equals("LIMBO")) {
			return null;
		}
		String id = mode.toLowerCase(Locale.ROOT);
		if (id.equals("lobby") || !PLAY_ID.matcher(id).matches()) {
			return null;
		}
		return id;
	}

	/** Test seam: forget everything. */
	public static synchronized void reset() {
		world = null;
		askAt = askedAt = 0;
		seen = null;
	}
}
