package com.origin.client.client.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// The waypoint store + operations. PER WORLD / SERVER (Will 2026-10-20): the list in
// memory is always the one for the world or server the player is in right now; leaving
// it keeps the file, joining another swaps the list, so waypoints never leak between
// worlds. Each waypoint still carries its dimension (the renderer only draws the ones in
// the current dimension; changing dimension does NOT change the scope). 1.8.9 stores
// dimensions under the modern names (0 overworld, -1 the_nether, 1 the_end). The scope key and
// file naming live in WaypointScope; the old single global list is adopted once by the
// first world joined after the update (WaypointScope.adoptLegacy).
// Saved to originclient-waypoints-<scope>.json in the config dir (Gson, like ModsConfig).
public final class Waypoints {

	// One waypoint. Public mutable fields so Gson round-trips it directly and the
	// edit UI can set them in place. Defaults here ARE the "sensible out-of-the-box"
	// quick-create defaults (white, beam + text + distance on, no block highlight).
	public static final class Waypoint {
		public String id = UUID.randomUUID().toString();
		public String name = "Waypoint";
		public String group = "";
		public int x, y, z;
		public String dimension = "minecraft:overworld";
		public int color = 0xFFFFFFFF;      // waypoint colour: beam + block highlight
		public boolean enabled = true;      // per-waypoint on/off toggle
		public boolean showIcon = true;     // the ◆ marker at the waypoint
		public boolean showText = true;
		public boolean showBeam = true;
		public boolean highlightBlock = false;
		public boolean showDistance = true;
		// Style — every visual part has its OWN colour and scale so each is
		// independently tunable (icon vs beam vs text). Gson leaves these
		// initializer defaults in place for waypoints saved before the fields
		// existed; unknown old keys (e.g. the retired "scale") are ignored.
		public int iconColor = 0xFFFFFFFF;
		public int textColor = 0xFFFFFFFF;
		public int textBgColor = 0xC0000000;
		public double iconScale = 1.0;
		public double textScale = 1.0;
		public double distScale = 1.0;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// The CURRENT scope's waypoints. Replaced (never cleared in place) on a scope switch,
	// so a caller still iterating the old list can't hit a ConcurrentModificationException.
	private static List<Waypoint> ALL = new ArrayList<>();
	// Scope key of ALL (null = not in a world: nothing is shown and nothing is saved).
	private static String loadedKey = null;
	private static boolean initialized = false;
	// Hot-path guard: the world is re-resolved only when the client level object changes
	// (weak, so a left world can still be collected). A dimension change replaces the
	// level too, but resolves to the same key, so it costs one string compare.
	private static WeakReference<Object> levelRef = new WeakReference<>(null);

	private Waypoints() {
	}

	private static Path configDir() {
		return com.origin.client.OriginClient.configDir();
	}

	private static Path fileFor(String key) {
		return configDir().resolve(WaypointScope.fileName(key));
	}

	public static synchronized List<Waypoint> all() {
		ensureLoaded();
		return ALL;
	}

	/** Cheap on the hot path: re-resolves the world only when the client level changed. */
	public static synchronized void ensureLoaded() {
		Minecraft mc = Minecraft.getMinecraft();
		Object level = mc.theWorld;
		if (initialized && levelRef.get() == level) {
			return;
		}
		initialized = true;
		levelRef = new WeakReference<>(level);
		String key = level == null ? null : currentKey(mc);
		if (key == null ? loadedKey == null : key.equals(loadedKey)) {
			return;
		}
		switchTo(key);
	}

	/** "sp:<save folder>" / "mp:<address>" / "lan:<host>" for the world the player is in. */
	private static String currentKey(Minecraft mc) {
		try {
			IntegratedServer sp = mc.getIntegratedServer();
			if (sp != null) {
				// 1.8.9: the save's folder name (MinecraftServer.getFolderName) -- the same
				// name the other versions take from the world path's last segment.
				return WaypointScope.singleplayer(sp.getFolderName());
			}
			ServerData sd = mc.getCurrentServerData();
			if (sd != null && sd.serverIP != null) {
				return WaypointScope.server(sd.serverIP);
			}
		} catch (Throwable ignored) {
			// fall through to the shared bucket
		}
		return "other:unknown";
	}

	// Leaving a world: its list is flushed (cheap insurance for an in-place edit that
	// never reached save()). Joining one: adopt the legacy list if this is the first
	// world after the update, then load the scope's file.
	private static void switchTo(String key) {
		if (loadedKey != null && !ALL.isEmpty()) {
			write(loadedKey, ALL);
		}
		loadedKey = key;
		List<Waypoint> next = new ArrayList<>();
		if (key != null) {
			Path f = fileFor(key);
			try {
				WaypointScope.adoptLegacy(configDir(), f);
			} catch (Exception e) {
				com.origin.client.OriginClient.LOGGER.warn("Could not migrate the old Origin waypoint list", e);
			}
			try {
				if (Files.exists(f)) {
					Waypoint[] arr = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), Waypoint[].class);
					if (arr != null) {
						next.addAll(Arrays.asList(arr));
					}
				}
			} catch (Exception e) {
				// Corrupt/unreadable data fails safe, but leave evidence in the log, and set
				// the file aside so the next save can't overwrite the only copy.
				com.origin.client.OriginClient.LOGGER.warn("Could not load Origin waypoints for " + key, e);
				try {
					Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
				}
			}
		}
		ALL = next;
	}

	public static synchronized void save() {
		ensureLoaded();
		if (loadedKey == null) {
			com.origin.client.OriginClient.LOGGER.warn("Origin waypoints: save skipped, not in a world");
			return;
		}
		write(loadedKey, ALL);
	}

	private static void write(String key, List<Waypoint> list) {
		Path destination = fileFor(key);
		Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
		try {
			Files.createDirectories(destination.getParent());
			Files.write(temp, GSON.toJson(list).getBytes(StandardCharsets.UTF_8));   // Java 8: no Files.writeString
			try {
				Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			com.origin.client.OriginClient.LOGGER.warn("Could not save Origin waypoints", e);
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (Exception ignored) {
			}
		}
	}

	/** Full create (from the create menu). Returns the new waypoint (already saved). */
	public static synchronized Waypoint create(String name, int x, int y, int z, String dim, int color) {
		ensureLoaded();
		Waypoint w = new Waypoint();
		w.name = name;
		w.x = x;
		w.y = y;
		w.z = z;
		w.dimension = dim;
		w.color = color;
		ALL.add(w);
		save();
		return w;
	}

	/** Commits a fully-built waypoint (the editor's create-draft: nothing exists
	 *  until the player clicks Done, then this adds + saves it). */
	public static synchronized void add(Waypoint w) {
		ensureLoaded();
		ALL.add(w);
		save();
	}

	/** Next free "<base> N" name (N auto-increments past existing ones). */
	public static synchronized String nextName(String base) {
		ensureLoaded();
		Set<String> names = new HashSet<>();
		for (Waypoint w : ALL) {
			names.add(w.name);
		}
		int n = 1;
		while (names.contains(base + " " + n)) {
			n++;
		}
		return base + " " + n;
	}

	public static synchronized void remove(Waypoint w) {
		ensureLoaded();
		ALL.remove(w);
		save();
	}

	public static String currentDim() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.theWorld != null ? dimName(mc.theWorld.provider.getDimensionId()) : "minecraft:overworld";
	}

	/** 1.8.9: a dimension id under its modern name (modded ids as "dim:<N>", like Social.dim). */
	public static String dimName(int id) {
		switch (id) {
			case 0:
				return "minecraft:overworld";
			case -1:
				return "minecraft:the_nether";
			case 1:
				return "minecraft:the_end";
			default:
				return "dim:" + id;   // same spelling as Social.dim
		}
	}

	/** Death waypoint: a red, beam-on waypoint at the death spot. */
	public static synchronized void onDeath(int x, int y, int z, String dim) {
		Waypoint w = create(nextName("Death"), x, y, z, dim, 0xFFE05555);
		w.showBeam = true;
		w.showText = true;
		save();
	}
}
