package com.origin.client.client.mods;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where on Hypixel the player is: the game and map, for waypoints kept per game and map
 * (JG-8). mc.hypixel.net is one address for every lobby, minigame map and SkyBlock island,
 * all in "the overworld", so a waypoint list per address showed a SkyBlock spot in every
 * Bed Wars map, and a death in one game left a red beam in the next.
 *
 * Hypixel answers the /locraw command with one JSON line naming the game and map
 * ({"server":"mini12A","gametype":"BEDWARS","mode":"BEDWARS_EIGHT_ONE","map":"Lighthouse"}).
 * Origin asks once a moment after each server switch (a new world object), only while
 * Waypoints is on, and hides the reply it asked for. The place is
 * "gametype/map", "gametype/mode" or "gametype/lobby", lower-case ("bedwars/lighthouse",
 * "skyblock/hub", "main/lobby", "limbo"). Until it is known, {@link #place} says null and
 * the waypoint scope falls back to the server plus dimension (WaypointScope.hypixel).
 *
 * Pure Java 8, no Minecraft types: the same file ships in every module, pre-1.20 and 1.8.9
 * included (tools/ingame-guard-tests checks the copies and the parsing). Called on the
 * client thread only (Hypixel.tick and the chat hook), so no locking.
 */
public final class HypixelLocation {
	/** Wait after a server switch before asking: right at the switch Hypixel can still answer for the old server. */
	static final long ASK_DELAY_MS = 1500L;
	/** A reply this long after the ask is still ours to hide; also when one retry goes out. */
	static final long REPLY_MS = 5000L;

	private static final Pattern FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");

	private static int levelId;       // identity of the world the place belongs to
	private static String place;      // null = not known (yet)
	private static long askAt;        // when to send /locraw; 0 = nothing to send
	private static long askedAt;      // when it was sent; 0 = no reply expected
	private static boolean retried;

	private HypixelLocation() {
	}

	/**
	 * Every client tick while on Hypixel. {@code levelIdNow} identifies the current world
	 * (System.identityHashCode of the client level): a new one is a server switch, which
	 * forgets the place. {@code wanted}: Waypoints is on (nothing is asked otherwise; a
	 * pending ask waits until it is). True = send /locraw now.
	 */
	public static boolean tick(int levelIdNow, boolean wanted, long now) {
		if (levelIdNow != levelId) {
			levelId = levelIdNow;
			place = null;
			askAt = now + ASK_DELAY_MS;
			askedAt = 0;
			retried = false;
		}
		if (place == null && askedAt != 0 && now - askedAt > REPLY_MS && !retried) {
			retried = true;                // no answer (Hypixel busy, "slow down"): one more try
			askAt = now;
			askedAt = 0;
		}
		if (askAt != 0 && wanted && now >= askAt) {
			askAt = 0;
			askedAt = now;
			return true;
		}
		return false;
	}

	/**
	 * A chat line from the server. A /locraw reply sets the place (also when the player
	 * typed /locraw); true = it is the reply Origin asked for, so hide it.
	 */
	public static boolean take(String line, long now) {
		String p = parse(line);
		if (p == null) {
			return false;
		}
		place = p;
		boolean ours = askedAt != 0 && now - askedAt <= REPLY_MS;
		askedAt = 0;
		return ours;
	}

	/** The place in the world {@code levelIdNow}, or null when not known for that world. */
	public static String place(int levelIdNow) {
		return levelIdNow == levelId ? place : null;
	}

	/** Left Hypixel: forget everything. */
	public static void clear() {
		levelId = 0;
		place = null;
		askAt = 0;
		askedAt = 0;
		retried = false;
	}

	/** A /locraw reply -> its place; null when {@code line} isn't one. */
	public static String parse(String line) {
		if (line == null) {
			return null;
		}
		String s = line.trim();
		if (!s.startsWith("{") || !s.endsWith("}") || !s.contains("\"server\"")) {
			return null;
		}
		Map<String, String> f = new HashMap<>();
		Matcher m = FIELD.matcher(s);
		while (m.find()) {
			f.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
		}
		String server = clean(f.get("server"));
		String game = clean(f.get("gametype"));
		if (server == null) {
			return null;
		}
		if (game == null) {
			return server.startsWith("limbo") ? "limbo" : null;   // "limbo"; anything else is unknown
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
}
