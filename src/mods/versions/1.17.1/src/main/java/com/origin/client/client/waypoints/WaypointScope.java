package com.origin.client.client.waypoints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

// Pure logic for per-world waypoints (no Minecraft or Gson types, Java 8 source level,
// so the one file is byte-identical in every module and a standalone `javac` check can
// run it -- tools/waypoint-scope-check). Waypoints.java does the Minecraft-facing part.
//
// A "scope" is one world or one server. Singleplayer = the save FOLDER name (not the
// display name: that one is renamable and not unique); online = the normalised address.
// Each scope persists to its own file config/originclient-waypoints-<scope>.json, named
// so the launcher's Settings Sync (originclient*.json) keeps picking them up.
public final class WaypointScope {

	public static final String FILE_PREFIX = "originclient-waypoints-";
	public static final String LEGACY_FILE = "originclient-waypoints.json";
	/** Where the pre-per-world global list is kept after migration (never matches the sync regex). */
	public static final String LEGACY_BACKUP = "originclient-waypoints.json.legacy";

	private WaypointScope() {
	}

	/** Scope key of a singleplayer save, from its folder name. */
	public static String singleplayer(String saveFolder) {
		String f = saveFolder == null ? "" : saveFolder.trim();
		return "sp:" + (f.isEmpty() ? "unknown" : f);
	}

	/**
	 * Scope key of a server/Realm address as typed in the server list: lower-cased,
	 * trailing dot and the default :25565 dropped, IPv6 brackets handled. A LAN or
	 * loopback host ("Open to LAN" picks a new random port every session) is keyed by
	 * host alone so it doesn't fragment.
	 */
	public static String server(String address) {
		String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
		if (a.isEmpty()) {
			return "mp:unknown";
		}
		String host = a;
		String port = "";
		if (a.startsWith("[")) {
			int close = a.indexOf(']');
			if (close > 0) {
				host = a.substring(1, close);
				String rest = a.substring(close + 1);
				if (rest.startsWith(":")) {
					port = rest.substring(1);
				}
			}
		} else {
			int first = a.indexOf(':');
			if (first >= 0 && first == a.lastIndexOf(':')) {   // exactly one colon = host:port
				host = a.substring(0, first);
				port = a.substring(first + 1);
			}                                                   // 0 or 2+ colons = bare host / bare IPv6
		}
		while (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		if (host.isEmpty()) {
			return "mp:unknown";
		}
		if (isLan(host)) {
			return "lan:" + host;
		}
		boolean defaultPort = port.isEmpty() || port.equals("25565");
		return "mp:" + (defaultPort ? host : host + ":" + port);
	}

	/** Loopback / private IPv4 / localhost / IPv6 loopback. */
	static boolean isLan(String host) {
		if (host.equals("localhost") || host.equals("::1")) {
			return true;
		}
		String[] p = host.split("\\.");
		if (p.length != 4) {
			return false;
		}
		int a;
		int b;
		try {
			for (String s : p) {
				int v = Integer.parseInt(s);
				if (v < 0 || v > 255) {
					return false;
				}
			}
			a = Integer.parseInt(p[0]);
			b = Integer.parseInt(p[1]);
		} catch (NumberFormatException e) {
			return false;
		}
		return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31);
	}

	/**
	 * File name for a scope key: a readable sanitised prefix plus 8 hex digits of the
	 * key's hash, so two keys that sanitise alike ("a b" / "a_b") still get different files.
	 */
	public static String fileName(String key) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < key.length() && sb.length() < 48; i++) {
			char c = key.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
			sb.append(ok ? c : '_');
		}
		return FILE_PREFIX + sb + "-" + String.format("%08x", key.hashCode()) + ".json";
	}

	/**
	 * First-run migration of the old single global list. The first world/server joined
	 * after the update adopts it (the realistic owner for the common one-world player),
	 * and the old file is parked as {@link #LEGACY_BACKUP}, so nothing is lost and it is
	 * never shown in a second world. Crash-safe in either order: the copy is atomic and
	 * never overwrites an existing scope file, and a retry just finishes the park.
	 * Once a backup exists a re-appearing legacy file (Settings Sync restoring it) is
	 * ignored, so adoption can only ever happen once.
	 *
	 * @return true if the legacy list was copied into {@code scopeFile}
	 */
	public static boolean adoptLegacy(Path configDir, Path scopeFile) throws IOException {
		Path legacy = configDir.resolve(LEGACY_FILE);
		Path backup = configDir.resolve(LEGACY_BACKUP);
		if (!Files.isRegularFile(legacy) || Files.exists(backup)) {
			return false;
		}
		boolean copied = false;
		if (!Files.exists(scopeFile)) {
			Path tmp = scopeFile.resolveSibling(scopeFile.getFileName() + ".tmp");
			Files.createDirectories(scopeFile.getParent());
			Files.copy(legacy, tmp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(tmp, scopeFile, StandardCopyOption.REPLACE_EXISTING);
			copied = true;
		}
		Files.move(legacy, backup);
		return copied;
	}
}
