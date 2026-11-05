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
 * The same reply also names where on the network the player is, for waypoints kept per Hypixel
 * game and map (JG-8): {@link #placeIn} gives "gametype/map" ("bedwars/lighthouse", "skyblock/hub",
 * "bedwars/lobby", "limbo") for the current world, WaypointScope.hypixel turns it into the list's key.
 *
 * Asking: once per world (each Hypixel server switch loads a new world), ASK_DELAY_MS after it
 * appears, while Quickplay or Waypoints is on (Waypoints says so through {@link #waypointsWant}).
 * ONE ask serves both: Origin never sends /locraw twice for a world. The reply to Origin's own
 * ask is hidden from chat; a /locraw the player types shows as usual. Game thread only (tick and
 * chat both run there); synchronized anyway.
 */
public final class HypixelLocraw {
	private HypixelLocraw() {
	}

	/** Hypixel needs a moment after the world loads before /locraw answers with the new server. */
	static final long ASK_DELAY_MS = 2_000;
	/** Only the FIRST reply this soon after Origin's ask is that ask's answer, hidden from chat. Hypixel
	 *  answers within a second; kept short so a /locraw the player types after an ask Hypixel never
	 *  answered (or another mod's reply) is not mistaken for Origin's and hidden. */
	static final long REPLY_WINDOW_MS = 3_000;

	private static final Pattern FIELD = Pattern.compile("\"(server|gametype|mode)\"\\s*:\\s*\"([^\"]*)\"");
	private static final Pattern ANY_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");

	/** Set every tick by Hypixel.tick: on Hypixel with Waypoints on, so the world's /locraw is asked
	 *  for the waypoint place even with Quickplay off. */
	public static volatile boolean waypointsWant;
	// a /play id is Hypixel's own lower-case word: anything else is never sent as a command
	private static final Pattern PLAY_ID = Pattern.compile("[a-z0-9_]{1,64}");

	private static Object world;
	private static long askAt, askedAt;
	private static boolean asked;  // Origin already sent its one /locraw for the current world
	private static String seen;
	private static String place;   // where the current world is on Hypixel; null = not known (yet)

	/**
	 * Once per client tick. level = the current world (only its identity is used), wanted = Quickplay
	 * is on and the player is on Hypixel. True means: send /locraw now.
	 */
	public static synchronized boolean tick(Object level, boolean wanted, long now) {
		wanted = wanted || waypointsWant;
		if (level != world) {
			// a new world: the old place is not this one's, and it has not been asked about yet
			world = level;
			place = null;
			asked = false;
			askAt = 0;
		}
		if (!wanted || level == null) {
			askAt = 0;   // a pending ask is dropped, not spent: it is scheduled again once wanted
			return false;
		}
		// Scheduled whenever the world is wanted and still unasked, not only on the tick it appears
		// (N92-3): Hypixel.tick sets waypointsWant AFTER this tick runs on the first tick in a world,
		// and Waypoints can be turned on mid-world. A place already known (a /locraw the player typed)
		// needs no ask.
		if (!asked && askAt == 0 && place == null) {
			askAt = now + ASK_DELAY_MS;
		}
		if (askAt != 0 && now >= askAt) {
			askAt = 0;
			asked = true;
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
		String p = placeOf(t);
		if (p != null) {
			place = p;
		}
		// Any reply ends Origin's ask: only the first one inside the window is hidden, never a second.
		boolean ours = askedAt != 0 && now - askedAt <= REPLY_WINDOW_MS;
		askedAt = 0;
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

	/** Where on Hypixel the world {@code level} is ("bedwars/lighthouse"), or null when not known for it. */
	public static synchronized String placeIn(Object level) {
		return level != null && level == world ? place : null;
	}

	/**
	 * The place a /locraw reply names, lower-case: "gametype/map", else "gametype/mode", or
	 * "gametype/lobby" in a lobby; "limbo"; null when it isn't a reply.
	 */
	public static String placeOf(String json) {
		if (json == null) {
			return null;
		}
		String s = json.trim();
		if (!s.startsWith("{") || !s.endsWith("}") || !s.contains("\"server\"")) {
			return null;
		}
		java.util.Map<String, String> f = new java.util.HashMap<>();
		Matcher m = ANY_FIELD.matcher(s);
		while (m.find()) {
			f.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
		}
		String server = clean(f.get("server"));
		String game = clean(f.get("gametype"));
		if (server == null) {
			return null;
		}
		if (game == null) {
			return server.startsWith("limbo") ? "limbo" : null;
		}
		String where = clean(f.get("map"));
		if (where == null) {
			where = f.containsKey("lobbyname") ? "lobby" : clean(f.get("mode"));
		}
		return where == null ? game : game + "/" + where;
	}

	private static String clean(String v) {
		if (v == null) {
			return null;
		}
		String t = v.trim().toLowerCase(Locale.ROOT);
		return t.isEmpty() || t.equals("null") ? null : t;
	}

	/** Test seam: forget everything. */
	public static synchronized void reset() {
		world = null;
		askAt = askedAt = 0;
		asked = false;
		seen = null;
		place = null;
		waypointsWant = false;
	}
}
