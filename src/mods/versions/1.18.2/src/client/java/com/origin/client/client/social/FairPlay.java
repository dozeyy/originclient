package com.origin.client.client.social;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

import java.util.Locale;

/**
 * The fair-play guard (docs/SOCIAL.md; Will, 2026-10-12: "ban them overall on
 * all multiplayer as a good baseline"). Everything that reveals where a party
 * member is or how they're doing -- B (tinted names + through-wall markers),
 * C's health / distance / direction, D (minimap + locator dots) and F (pings)
 * -- works ONLY in a world a party member's own game hosts: singleplayer opened
 * to LAN, and the players who joined it (or a server) over a private LAN
 * address. On every other multiplayer server nothing is sent (partyState stays
 * null) and nothing is drawn; party chat, warp, friends and DMs still work.
 *
 * THE one check: {@link #positionKey}. Everything else asks it.
 */
public final class FairPlay {
	private FairPlay() {
	}

	/**
	 * Where party positions may be shared right now, as the "server" both sides
	 * report ("lan:<port>"; the service drops a :25565 port, so "lan" for that
	 * port) -- or null when positions are off here.
	 *
	 * The host of a LAN world has no address of its own, so the key is the
	 * world's port, which is the one thing the host and everyone who joined it
	 * share. Two different LAN worlds on the same port in two different houses
	 * would share a key; that needs both in one party AND in the same dimension
	 * to show anything, and it never leaves LAN worlds.
	 */
	public static String positionKey(Minecraft mc) {
		if (mc.level == null) {
			return null;
		}
		if (mc.hasSingleplayerServer()) {
			IntegratedServer server = mc.getSingleplayerServer();
			// not opened to LAN = nobody else can be in this world
			return server != null && server.isPublished() && server.getPort() > 0 ? key(server.getPort()) : null;
		}
		ServerData sd = mc.getCurrentServer();
		if (sd == null || mc.isConnectedToRealms() || sd.ip == null) {
			return null;
		}
		String host = host(sd.ip);
		return host != null && isPrivate(host) ? key(port(sd.ip)) : null;
	}

	/** True on a multiplayer server where positions are off (the Friends window says so). */
	public static boolean positionsOffHere(Minecraft mc) {
		return mc.level != null && !mc.hasSingleplayerServer() && positionKey(mc) == null;
	}

	/** A private LAN / loopback address (IP literals and "localhost" only, never a looked-up name). */
	public static boolean isPrivate(String host) {
		if (host == null) {
			return false;
		}
		if (host.equals("localhost") || host.equals("::1")) {
			return true;
		}
		String[] p = host.split("\\.");
		if (p.length != 4) {
			return false;
		}
		int[] o = new int[4];
		try {
			for (int i = 0; i < 4; i++) {
				o[i] = Integer.parseInt(p[i]);
				if (o[i] < 0 || o[i] > 255) {
					return false;
				}
			}
		} catch (NumberFormatException e) {
			return false;
		}
		return o[0] == 10 || o[0] == 127
				|| (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
				|| (o[0] == 192 && o[1] == 168);
	}

	private static String key(int port) {
		return port == 25565 ? "lan" : "lan:" + port;
	}

	/** "192.168.1.5:54321" -> "192.168.1.5"; "[::1]:25565" -> "::1"; null for blank. */
	public static String host(String server) {
		if (server == null || server.isBlank()) {
			return null;
		}
		String h = server.trim().toLowerCase(Locale.ROOT);
		if (h.startsWith("[")) {
			int end = h.indexOf(']');
			return end > 0 ? h.substring(1, end) : h;
		}
		int colon = h.lastIndexOf(':');
		if (colon > 0 && h.indexOf(':') == colon) {
			h = h.substring(0, colon);
		}
		while (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		return h;
	}

	private static int port(String server) {
		String s = server.trim();
		int colon = s.lastIndexOf(':');
		int close = s.lastIndexOf(']');
		if (colon > close && (s.startsWith("[") || s.indexOf(':') == colon)) {
			try {
				return Integer.parseInt(s.substring(colon + 1));
			} catch (NumberFormatException ignored) {
				// fall through to the default
			}
		}
		return 25565;
	}
}
