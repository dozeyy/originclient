package com.origin.client.client.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

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
// the current dimension; changing dimension does NOT change the scope). The scope key and
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
		// true = the automatic death marker; the next death replaces it
		public boolean death = false;
		// Saved format (WaypointScope.WAYPOINT_FORMAT, written on every save); 0 = saved before
		// the field existed, which gets the one-time marker colour repair on load.
		public int format = 0;
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
	// The Hypixel place (util/HypixelLocraw) the current key was made with; null = none.
	private static String loadedPlace = null;
	// The address key of the Realm the current key names (its list before Realms were keyed
	// by name, MAP-7); null when not on a Realm. Set by currentKey, read by switchTo.
	private static String realmAddressKey = null;
	// The Realms server id of the last Realm joined (RealmsConnectMixin); 0 = none seen.
	private static volatile long joinedRealmId = 0;
	private static boolean initialized = false;
	// Hot-path guard: the world is re-resolved only when the client level object changes
	// (weak, so a left world can still be collected). A dimension change replaces the
	// level too, but resolves to the same key, so it costs one string compare.
	private static WeakReference<Object> levelRef = new WeakReference<>(null);

	private Waypoints() {
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
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
		Minecraft mc = Minecraft.getInstance();
		Object level = mc.level;
		// On Hypixel the place (game + map, from /locraw) arrives a moment after the world
		// and picks the list, so a new place re-resolves too (JG-8). One compare per call.
		String place = level == null ? null
				: com.origin.client.client.util.HypixelLocraw.placeIn(level);
		if (initialized && levelRef.get() == level && java.util.Objects.equals(place, loadedPlace)) {
			return;
		}
		initialized = true;
		loadedPlace = place;
		levelRef = new WeakReference<>(level);
		String key = level == null ? null : currentKey(mc);
		if (key == null ? loadedKey == null : key.equals(loadedKey)) {
			return;
		}
		switchTo(key);
	}

	/** "sp:<version>#<seed>:<save folder>" / "realm:<name>" / "mp:<address>" / "lan:<host>" for the world the player is in. */
	private static String currentKey(Minecraft mc) {
		realmAddressKey = null;
		try {
			IntegratedServer sp = mc.getSingleplayerServer();
			if (sp != null) {
				Path dir = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
				return singleplayerKey(String.valueOf(dir.getFileName()), sp);
			}
			ServerData sd = mc.getCurrentServer();
			if (sd != null && sd.ip != null) {
				String server = WaypointScope.server(sd.ip);
				// A Realm by its Realms id (its name only if the join wasn't seen): its address is the
				// host it runs on today and changes when Mojang moves it, which used to start an empty
				// list (MAP-7).
				String realm = mc.isConnectedToRealms() ? WaypointScope.realm(joinedRealmId, sd.name) : null;
				if (realm != null) {
					realmAddressKey = server;
					return realm;
				}
				// Hypixel: one list per game and map, not one for the whole network (JG-8).
				// A known place also means Hypixel when it was joined by a numeric IP.
				if (loadedPlace != null || com.origin.client.client.mods.HypixelDetect.isHypixelHost(sd.ip)) {
					return WaypointScope.hypixel(server, loadedPlace, currentDim());
				}
				return server;
			}
		} catch (Throwable ignored) {
			// fall through to the shared bucket
		}
		return "other:unknown";
	}

	/** RealmsConnectMixin: a Realm is being joined. Its id keys its waypoint list (MAP-7). */
	public static void joiningRealm(long realmsServerId) {
		joinedRealmId = realmsServerId;
	}

	/** A world's key with its seed (MAP-2); the folder key if the seed can't be read. */
	private static String singleplayerKey(String folder, IntegratedServer sp) {
		try {
			return WaypointScope.singleplayer(folder, mcVersion(), sp.overworld().getSeed());
		} catch (Throwable t) {
			return WaypointScope.singleplayer(folder, mcVersion());
		}
	}

	/** This game's Minecraft version ("1.19.4"); "" if Fabric can't say (old sp key then). */
	/** Shared with Portal Calc's memory, which keys singleplayer worlds the same way. */
	public static String mcVersion() {
		try {
			return FabricLoader.getInstance().getModContainer("minecraft")
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		} catch (Throwable t) {
			return "";
		}
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
				// start from the list this world/server had under an older key (version, seed,
				// Realm name, Hypixel place): copied once, the old file stays as the backup
				WaypointScope.adoptEarlier(configDir(), key, mcVersion(), realmAddressKey);
			} catch (Exception e) {
				com.origin.client.OriginClient.LOGGER.warn("Could not copy this world's earlier Origin waypoints", e);
			}
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
						markerColourFromBeam(next);
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

	// ONE-TIME repair of waypoints saved before the marker took the beam's colour (MAP-4): their
	// marker kept the white default while the map dot and compass tick (which now draw the marker
	// colour) showed the beam's, so such a waypoint takes its beam's colour as its marker colour
	// instead of turning white on the map; an old death marker's label turns red too, like a new
	// one. Only waypoints of an older format (WaypointScope.needsMarkerRepair): every save writes
	// the current format, so a white marker the player picks afterwards is never undone.
	private static void markerColourFromBeam(List<Waypoint> list) {
		for (Waypoint w : list) {
			if (w == null) {
				continue;
			}
			if (WaypointScope.needsMarkerRepair(w.format, w.iconColor, w.color, w.death)) {
				w.iconColor = w.color;
				if (w.death && w.textColor == 0xFFFFFFFF) {
					w.textColor = w.color;
				}
			}
			w.format = WaypointScope.WAYPOINT_FORMAT;
		}
	}

	public static synchronized void save() {
		ensureLoaded();
		if (loadedKey == null) {
			com.origin.client.OriginClient.LOGGER.debug("Origin waypoints: save skipped, not in a world"); // normal when the screen closes from the title
			return;
		}
		write(loadedKey, ALL);
	}

	private static void write(String key, List<Waypoint> list) {
		Path destination = fileFor(key);
		for (Waypoint w : list) {
			if (w != null) {
				w.format = WaypointScope.WAYPOINT_FORMAT;   // repaired or new: never repaired again
			}
		}
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
		w.iconColor = color;   // the marker (diamond, gem, map dot) wears the beam's colour (MAP-4)
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
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.dimension().location().toString() : "minecraft:overworld";
	}

	/** Death waypoint: a red, beam-on waypoint at the death spot. Only the LATEST death
	 *  is kept: the previous "Death" waypoint is replaced, so they never pile up. The
	 *  "Death N" ones earlier builds numbered up are cleared with it. Only Origin's own
	 *  markers go: the death flag, or (from before the flag) the auto name AND the death
	 *  red -- a waypoint the player named "Death" in another colour stays. */
	public static synchronized void onDeath(int x, int y, int z, String dim) {
		ensureLoaded();
		ALL.removeIf(old -> old.death || (old.color == DEATH_COLOR && isDeathName(old.name)));
		Waypoint w = create("Death", x, y, z, dim, DEATH_COLOR);
		w.death = true;
		// one red marker everywhere: diamond, Locator Bar gem, map dot and label (MAP-4)
		w.iconColor = DEATH_COLOR;
		w.textColor = DEATH_COLOR;
		w.showBeam = true;
		w.showText = true;
		save();
	}

	private static final int DEATH_COLOR = 0xFFE05555;

	private static boolean isDeathName(String name) {
		return name != null && name.matches("Death( \\d+)?");
	}
}
