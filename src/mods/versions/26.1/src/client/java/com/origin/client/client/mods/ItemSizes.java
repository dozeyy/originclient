package com.origin.client.client.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Persistent per-item dropped-item render scales for the Item Size Customizer.
 * A plain id→scale map (only NON-default entries are stored, so the file stays
 * small), saved to {@code config/originclient-itemsizes.json}. The dropped-item
 * render hook (ItemEntityScaleMixin) asks {@link #get(Item)} for every dropped
 * item every frame, so that path is an identity-map hit by Item: no id string, no
 * lock. Edits from the grid screen write through immediately.
 */
public final class ItemSizes {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Float> SIZES = new HashMap<>();
	private static volatile boolean loaded = false;
	private static Path file;

	public static final float MIN = 0.25f, MAX = 3.0f, DEFAULT = 1.0f;

	// Render-path cache by Item identity, filled on the first ask per item and
	// emptied whenever a size changes. Only touched on the client thread (the
	// render hook and the Item Size screen), so it needs no lock.
	private static final Map<Item, Float> BY_ITEM = new IdentityHashMap<>();

	private ItemSizes() {
	}

	/** Scale for a dropped item's Item (the per-frame render path). */
	public static float get(Item item) {
		Float v = BY_ITEM.get(item);
		if (v == null) {
			v = get(BuiltInRegistries.ITEM.getKey(item));
			BY_ITEM.put(item, v);
		}
		return v;
	}

	/** Scale for `id`, or 1.0 if the player never customised it. */
	public static float get(Identifier id) {
		ensureLoaded();
		Float v = SIZES.get(id.toString());
		return v == null ? DEFAULT : v;
	}

	public static boolean isCustom(Identifier id) {
		ensureLoaded();
		return SIZES.containsKey(id.toString());
	}

	/** Set (and persist) a scale; storing ~1.0 removes the entry (back to default). */
	public static void set(Identifier id, float scale) {
		ensureLoaded();
		scale = Math.max(MIN, Math.min(MAX, scale));
		if (Math.abs(scale - DEFAULT) < 0.01f) {
			SIZES.remove(id.toString());
		} else {
			SIZES.put(id.toString(), scale);
		}
		BY_ITEM.clear();
		save();
	}

	// Slider drags only: changes the size in memory and marks it unsaved. A drag
	// fires on every mouse move and save() rewrites the whole file; the screen
	// calls flush() once on release and when it closes.
	private static boolean dirty;

	public static void setLive(Identifier id, float scale) {
		ensureLoaded();
		scale = Math.max(MIN, Math.min(MAX, scale));
		if (Math.abs(scale - DEFAULT) < 0.01f) {
			SIZES.remove(id.toString());
		} else {
			SIZES.put(id.toString(), scale);
		}
		BY_ITEM.clear();
		dirty = true;
	}

	public static void flush() {
		if (dirty) {
			dirty = false;
			save();
		}
	}

	public static void reset(Identifier id) {
		set(id, DEFAULT);
	}

	// The volatile check keeps every lookup off the lock once the file is loaded.
	private static void ensureLoaded() {
		if (!loaded) {
			load();
		}
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		BY_ITEM.clear();
		try {
			file = FabricLoader.getInstance().getConfigDir().resolve("originclient-itemsizes.json");
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					Map<String, Float> m = GSON.fromJson(r, new TypeToken<Map<String, Float>>() {
					}.getType());
					if (m != null) {
						SIZES.putAll(m);
					}
				}
			}
		} catch (Throwable t) {
			// Kept as originclient-itemsizes.json.corrupt so the next save can't destroy it.
			com.origin.client.OriginClient.LOGGER.warn("Item sizes failed to load; kept the file as .corrupt, starting empty", t);
			com.origin.client.client.ConfigFiles.setAside(file);
		}
	}

	private static void save() {
		dirty = false;
		if (file == null) {
			return;
		}
		try {
			// tmp + atomic replace, like every Origin config: a kill mid-write (the launcher
			// can end the game) keeps the last good file instead of an empty one.
			ModsConfig.writeAtomically(file, GSON.toJsonTree(SIZES).getAsJsonObject());
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Item sizes failed to save", t);
		}
	}
}
