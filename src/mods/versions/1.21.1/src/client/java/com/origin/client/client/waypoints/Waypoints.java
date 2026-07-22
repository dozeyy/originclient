package com.origin.client.client.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// The waypoint store + operations. One flat, persisted list; each waypoint carries
// its dimension, so the in-world renderer only draws the ones in the current one.
// Saved to originclient-waypoints.json in the config dir (Gson, like ModsConfig).
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
	private static final List<Waypoint> ALL = new ArrayList<>();
	private static boolean loaded = false;

	private Waypoints() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("originclient-waypoints.json");
	}

	public static synchronized List<Waypoint> all() {
		ensureLoaded();
		return ALL;
	}

	public static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file();
			if (Files.exists(f)) {
				Waypoint[] arr = GSON.fromJson(Files.readString(f), Waypoint[].class);
				if (arr != null) {
					ALL.addAll(Arrays.asList(arr));
				}
			}
		} catch (Exception ignored) {
			// corrupt/missing file → start empty
		}
	}

	public static synchronized void save() {
		try {
			Files.writeString(file(), GSON.toJson(ALL));
		} catch (Exception ignored) {
		}
	}

	/** Full create (from the create menu). Returns the new waypoint (already saved). */
	public static Waypoint create(String name, int x, int y, int z, String dim, int color) {
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
	public static String nextName(String base) {
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

	public static void remove(Waypoint w) {
		ensureLoaded();
		ALL.remove(w);
		save();
	}

	public static String currentDim() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.dimension().location().toString() : "minecraft:overworld";
	}

	/** Death waypoint: a red, beam-on waypoint at the death spot. */
	public static void onDeath(int x, int y, int z, String dim) {
		Waypoint w = create(nextName("Death"), x, y, z, dim, 0xFFE05555);
		w.showBeam = true;
		w.showText = true;
		save();
	}
}
