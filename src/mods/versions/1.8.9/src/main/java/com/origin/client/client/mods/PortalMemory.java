package com.origin.client.client.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal Calculator's memory: every nether portal the player has stood in or
 * next to, kept per world (a singleplayer save or a server address) and per
 * dimension. Saved to originclient-portals.json in the config dir, the same
 * Gson + atomic-replace way Waypoints saves its list.
 *
 * A portal is stored as the box of its portal blocks (not the obsidian frame):
 * vanilla's link search looks at portal BLOCKS, so the box is exactly what the
 * prediction in PortalCalc needs. Only portals the player has seen are known;
 * the HUD says "known portals" for that reason.
 *
 * 1.8.9: the config dir is the game folder's config/ (Forge's own config
 * folder -- FabricLoader.getConfigDir on the other versions points at the same
 * place inside each instance), and the dimension ids are written in the modern
 * "minecraft:overworld" / "minecraft:the_nether" form (Waypoints.dimName) so the
 * file reads the same on every version.
 */
public final class PortalMemory {

	/** One remembered portal. Public fields so Gson round-trips it directly. */
	public static final class Portal {
		public String dimension = "minecraft:overworld";
		public String axis = "x";          // the portal block's horizontal axis ("x" or "z")
		public int minX, minY, minZ, maxX, maxY, maxZ;
		public long seen;                   // last time the player was at it (epoch ms)
		public boolean entered;             // the player has stood inside it (Auto Waypoint fires once)

		boolean overlaps(Portal o, int pad) {
			return o.dimension.equals(dimension)
					&& o.minX <= maxX + pad && o.maxX >= minX - pad
					&& o.minY <= maxY + pad && o.maxY >= minY - pad
					&& o.minZ <= maxZ + pad && o.maxZ >= minZ - pad;
		}

		boolean sameBox(Portal o) {
			return o.dimension.equals(dimension) && o.axis.equals(axis)
					&& o.minX == minX && o.minY == minY && o.minZ == minZ
					&& o.maxX == maxX && o.maxY == maxY && o.maxZ == maxZ;
		}

		/** Bottom-centre block of the portal: the spot shown to the player. */
		public int labelX() {
			return Math.floorDiv(minX + maxX, 2);
		}

		public int labelZ() {
			return Math.floorDiv(minZ + maxZ, 2);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, List<Portal>> BY_WORLD = new HashMap<>();
	private static boolean loaded = false;
	private static boolean dirty = false;

	private PortalMemory() {
	}

	private static Path file() {
		return Minecraft.getMinecraft().mcDataDir.toPath().resolve("config").resolve("originclient-portals.json");
	}

	/** The live list for one world (created on first use). */
	public static synchronized List<Portal> forWorld(String worldKey) {
		ensureLoaded();
		List<Portal> list = BY_WORLD.get(worldKey);
		if (list == null) {
			list = new ArrayList<>();
			BY_WORLD.put(worldKey, list);
		}
		return list;
	}

	/**
	 * Records a portal seen right now and returns the stored entry. A remembered
	 * portal it touches is the same portal (rebuilt, lit again, or seen from
	 * another side) and is replaced, so the list never holds two entries for one
	 * portal.
	 */
	public static synchronized Portal remember(String worldKey, Portal p) {
		List<Portal> list = forWorld(worldKey);
		for (int i = list.size() - 1; i >= 0; i--) {
			Portal o = list.get(i);
			if (!o.overlaps(p, 1)) {
				continue;
			}
			if (o.sameBox(p)) {
				// The usual case -- standing at a portal we already know. It is
				// re-seen every few ticks, so only the timestamp moves and the
				// file is written later (flush), not on every scan.
				o.seen = p.seen;
				dirty = true;
				return o;
			}
			p.entered |= o.entered;
			list.remove(i);
		}
		list.add(p);
		save();
		return p;
	}

	/** Marks a field change on a stored portal for the next flush. */
	public static synchronized void touch() {
		dirty = true;
	}

	/** Forgets a portal the player has seen broken. */
	public static synchronized void forget(String worldKey, Portal p) {
		if (forWorld(worldKey).remove(p)) {
			save();
		}
	}

	/** Writes pending timestamp updates (called when leaving a world). */
	public static synchronized void flush() {
		if (dirty) {
			save();
		}
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file();
			if (Files.exists(f)) {
				Map<String, List<Portal>> m = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8),
						new TypeToken<Map<String, List<Portal>>>() {
						}.getType());
				if (m != null) {
					for (Map.Entry<String, List<Portal>> e : m.entrySet()) {
						if (e.getKey() != null && e.getValue() != null) {
							List<Portal> clean = new ArrayList<>();
							for (Portal p : e.getValue()) {
								if (p != null && p.dimension != null) {
									clean.add(p);
								}
							}
							BY_WORLD.put(e.getKey(), clean);
						}
					}
				}
			}
		} catch (Exception e) {
			// Corrupt or unreadable memory starts empty, with evidence in the log.
			com.origin.client.OriginClient.LOGGER.warn("Could not load Origin portal memory", e);
		}
	}

	private static void save() {
		dirty = false;
		Path destination = file();
		Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
		try {
			Files.createDirectories(destination.getParent());
			Files.write(temp, GSON.toJson(BY_WORLD).getBytes(StandardCharsets.UTF_8));   // Java 8: no Files.writeString
			try {
				Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			com.origin.client.OriginClient.LOGGER.warn("Could not save Origin portal memory", e);
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (Exception ignored) {
			}
		}
	}
}
