package com.origin.client.client.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-item dropped-item render scales for the Item Size Customizer.
 * A plain id→scale map (only NON-default entries are stored, so the file stays
 * small), saved to {@code config/originclient-itemsizes.json}. The dropped-item
 * render hook (ItemEntityScaleMixin) reads {@link #get} every frame, so lookups
 * are a cheap HashMap hit; edits from the grid screen write through immediately.
 */
public final class ItemSizes {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Float> SIZES = new HashMap<>();
	private static volatile boolean loaded = false;
	private static Path file;

	public static final float MIN = 0.25f, MAX = 3.0f, DEFAULT = 1.0f;

	private ItemSizes() {
	}

	/** Scale for `id`, or 1.0 if the player never customised it. */
	public static float get(ResourceLocation id) {
		ensureLoaded();
		Float v = SIZES.get(id.toString());
		return v == null ? DEFAULT : v;
	}

	public static boolean isCustom(ResourceLocation id) {
		ensureLoaded();
		return SIZES.containsKey(id.toString());
	}

	/** Set (and persist) a scale; storing ~1.0 removes the entry (back to default). */
	public static void set(ResourceLocation id, float scale) {
		ensureLoaded();
		scale = Math.max(MIN, Math.min(MAX, scale));
		if (Math.abs(scale - DEFAULT) < 0.01f) {
			SIZES.remove(id.toString());
		} else {
			SIZES.put(id.toString(), scale);
		}
		save();
	}

	public static void reset(ResourceLocation id) {
		set(id, DEFAULT);
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
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
			com.origin.client.OriginClient.LOGGER.warn("Item sizes failed to load; starting empty", t);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(SIZES, w);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Item sizes failed to save", t);
		}
	}
}
