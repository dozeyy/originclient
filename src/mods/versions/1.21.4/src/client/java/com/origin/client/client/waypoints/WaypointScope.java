package com.origin.client.client.waypoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

// Pure logic for per-world waypoints (no Minecraft or Gson types, Java 8 source level,
// so the one file is byte-identical in every module and a standalone `javac` check can
// run it -- tools/waypoint-scope-check). Waypoints.java does the Minecraft-facing part.
//
// A "scope" is one world or one server. Singleplayer = version + world seed + save FOLDER
// name (not the display name: that one is renamable and not unique); a Realm = its Realms id;
// online = the normalised address.
// Each scope persists to its own file config/originclient-waypoints-<scope>.json, named
// so the launcher's Settings Sync (originclient*.json) keeps picking them up.
public final class WaypointScope {

	public static final String FILE_PREFIX = "originclient-waypoints-";
	public static final String LEGACY_FILE = "originclient-waypoints.json";
	/** Where the pre-per-world global list is kept after migration (never matches the sync regex). */
	public static final String LEGACY_BACKUP = "originclient-waypoints.json.legacy";

	private WaypointScope() {
	}

	/** Scope key of a singleplayer save, from its folder name (the pre-version key; see below). */
	public static String singleplayer(String saveFolder) {
		String f = saveFolder == null ? "" : saveFolder.trim();
		return "sp:" + (f.isEmpty() ? "unknown" : f);
	}

	/**
	 * Scope key of a singleplayer save in one Minecraft version: "sp:<version>:<folder>".
	 * Settings Sync carries every waypoint file to every version's instance, where a
	 * same-named save ("New World") is a different world -- keyed by folder alone, its
	 * waypoints showed up in all of them. A blank version falls back to the old key.
	 */
	public static String singleplayer(String saveFolder, String mcVersion) {
		String v = mcVersion == null ? "" : mcVersion.trim();
		String plain = singleplayer(saveFolder);
		return v.isEmpty() ? plain : "sp:" + v + ":" + plain.substring(3);
	}

	/**
	 * Scope key of one singleplayer WORLD: "sp:<version>#<seed tag>:<folder>" (MAP-2).
	 * Deleting "New World" and creating another frees and reuses the same folder name, so a
	 * key from version + folder alone gave the new world the deleted one's beams and death
	 * marker; the seed tells the two apart. The tag sits between version and folder because
	 * a folder name may hold ':' or '#' and a version never does. Only 8 hex digits of a
	 * mixed hash leave the game (file names travel with Settings Sync), never the seed.
	 * A blank version keeps the old unversioned key.
	 */
	public static String singleplayer(String saveFolder, String mcVersion, long seed) {
		String v = mcVersion == null ? "" : mcVersion.trim();
		String versioned = singleplayer(saveFolder, mcVersion);
		return v.isEmpty() ? versioned : "sp:" + v + "#" + seedTag(seed) + versioned.substring(3 + v.length());
	}

	/** 8 hex digits of a SplitMix64-mixed seed: tells worlds apart without revealing the seed. */
	static String seedTag(long seed) {
		long z = seed + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return String.format("%08x", (int) z);
	}

	/**
	 * The folder key ("sp:<version>:<folder>") a seeded key of {@code mcVersion} used before
	 * the seed was added, or null when {@code key} isn't one.
	 */
	public static String unseeded(String key, String mcVersion) {
		String v = mcVersion == null ? "" : mcVersion.trim();
		String head = "sp:" + v + "#";
		if (key == null || v.isEmpty() || !key.startsWith(head)) {
			return null;
		}
		int colon = key.indexOf(':', head.length());
		return colon < 0 ? null : "sp:" + v + key.substring(colon);
	}

	/**
	 * Scope key of a Realm (MAP-7): "realm:id:<Realms server id>". A Realm's join address is
	 * the host it runs on right now and changes when Mojang moves it, which used to start an
	 * empty list; its id never changes, survives a rename and is unique (two Realms can share
	 * a name, and vanilla shows "???" for one it can't name). The id comes from the Realms
	 * join itself (RealmsConnectMixin); only when that wasn't seen ({@code id <= 0}) is the
	 * Realm keyed by its name, "realm:name:<name>". Null when neither is known (the caller
	 * keeps the address key).
	 */
	public static String realm(long id, String name) {
		if (id > 0) {
			return "realm:id:" + id;
		}
		String n = name == null ? "" : name.trim();
		return n.isEmpty() ? null : "realm:name:" + n;
	}

	/**
	 * Copies the list a key's world/server had under an older key scheme, once, before the
	 * key's own file is first read. Nothing is ever deleted: the old files stay as backups.
	 * <ul>
	 * <li>seeded singleplayer key: the world's folder-key list (itself first filled from the
	 * pre-version key), but only for the FIRST world seen in that folder -- see
	 * {@link #adoptFolderList};</li>
	 * <li>Realm key: the list under the Realm's current address ({@code realmAddressKey});</li>
	 * <li>Hypixel SkyBlock place: the old all-of-Hypixel list ({@link #hypixelPrevious});</li>
	 * <li>versioned (unseeded) singleplayer key: the pre-version key (seed unknown).</li>
	 * </ul>
	 */
	public static void adoptEarlier(Path configDir, String key, String mcVersion, String realmAddressKey) throws IOException {
		Path own = configDir.resolve(fileName(key));
		String folderKey = unseeded(key, mcVersion);
		if (folderKey != null) {
			String preVersion = unversioned(folderKey, mcVersion);
			Path folderFile = configDir.resolve(fileName(folderKey));
			if (preVersion != null) {
				adoptPrevious(configDir.resolve(fileName(preVersion)), folderFile);
			}
			adoptFolderList(folderFile, own, key);
			return;
		}
		String previous = key.startsWith("realm:") ? realmAddressKey : unversioned(key, mcVersion);
		if (previous == null) {
			previous = hypixelPrevious(key);
		}
		if (previous != null) {
			adoptPrevious(configDir.resolve(fileName(previous)), own);
		}
	}

	/**
	 * A world copies its folder's pre-seed list only if it is the first world seen in that
	 * folder since the update: the "<folder file>.claimed" note beside the folder file names
	 * that world. A later world in the same reused folder (the old one deleted, the new one
	 * left at the default name) starts empty instead of inheriting the old beams (MAP-2).
	 * The note is written before the copy, so a crash between the two only retries the
	 * same world's copy. It never matches Settings Sync's originclient*.json pattern: it is
	 * about this game folder's own saves.
	 *
	 * @return true if {@code folderFile} was copied into {@code scopeFile}
	 */
	public static boolean adoptFolderList(Path folderFile, Path scopeFile, String scopeKey) throws IOException {
		if (!Files.isRegularFile(folderFile)) {
			return false;
		}
		Path claim = folderFile.resolveSibling(folderFile.getFileName() + ".claimed");
		if (Files.isRegularFile(claim)) {
			String owner = new String(Files.readAllBytes(claim), StandardCharsets.UTF_8).trim();
			if (!owner.equals(scopeKey)) {
				return false;
			}
		} else {
			Path tmp = claim.resolveSibling(claim.getFileName() + ".tmp");
			Files.write(tmp, scopeKey.getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, claim, StandardCopyOption.REPLACE_EXISTING);
		}
		return adoptPrevious(folderFile, scopeFile);
	}

	/**
	 * The key a versioned singleplayer key used before the version was added (the one to
	 * migrate from), or null when {@code key} isn't a singleplayer key of {@code mcVersion}.
	 */
	public static String unversioned(String key, String mcVersion) {
		String v = mcVersion == null ? "" : mcVersion.trim();
		String head = "sp:" + v + ":";
		if (key == null || v.isEmpty() || !key.startsWith(head)) {
			return null;
		}
		return "sp:" + key.substring(head.length());
	}

	/**
	 * One-time move to the versioned key: a world whose versioned file doesn't exist yet
	 * starts from a COPY of its old unversioned file. The old file stays (other versions
	 * migrate from it too, and it is the backup); once the new file exists -- written even
	 * when the list is empty -- this never copies again. Atomic: tmp file, then move.
	 *
	 * @return true if {@code previousFile} was copied into {@code scopeFile}
	 */
	public static boolean adoptPrevious(Path previousFile, Path scopeFile) throws IOException {
		if (previousFile == null || previousFile.equals(scopeFile)
				|| !Files.isRegularFile(previousFile) || Files.exists(scopeFile)) {
			return false;
		}
		Path tmp = scopeFile.resolveSibling(scopeFile.getFileName() + ".tmp");
		Files.createDirectories(scopeFile.getParent());
		Files.copy(previousFile, tmp, StandardCopyOption.REPLACE_EXISTING);
		Files.move(tmp, scopeFile, StandardCopyOption.REPLACE_EXISTING);
		return true;
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

	/**
	 * Scope key on Hypixel (JG-8): one address is every lobby, minigame map and SkyBlock
	 * island, so the list is kept per place: "mp:mc.hypixel.net/bedwars/lighthouse" (the
	 * place from /locraw, util/HypixelLocraw). Until the place is known, server plus
	 * dimension ("mp:mc.hypixel.net/minecraft:overworld"; a place never holds a ':').
	 */
	public static String hypixel(String serverKey, String place, String dimension) {
		String where = place != null && !place.isEmpty() ? place
				: (dimension == null || dimension.isEmpty() ? "unknown" : dimension);
		return serverKey + "/" + where;
	}

	/**
	 * The key a new Hypixel SkyBlock place starts from: the old one-list-for-all-of-Hypixel
	 * key, so waypoints made before lists were split stay where SkyBlock players look for
	 * them (copied once per place, the old file is kept: WaypointScope.adoptPrevious).
	 * Minigame maps start empty (a copy there would bring back the stray beams); null then.
	 */
	public static String hypixelPrevious(String key) {
		int slash = key == null ? -1 : key.indexOf('/');
		if (slash <= 0 || !key.startsWith("skyblock/", slash + 1)) {
			return null;
		}
		return key.substring(0, slash);
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
