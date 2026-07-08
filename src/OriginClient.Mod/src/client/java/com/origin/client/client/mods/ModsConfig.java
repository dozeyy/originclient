package com.origin.client.client.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

// Persistence for the whole mod system: per-mod enabled flags + option
// values, HUD element layout (anchor/offset/scale), and menu meta (panel
// backing visibility). Its OWN file — originclient.json stays untouched for
// the legacy flags and the launcher's originUiEnabled bridge field.
public final class ModsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("originclient-mods.json");

	// modId -> (optionKey -> value). "enabled" is reserved for the mod flag.
	static final Map<String, Map<String, JsonElement>> VALUES = new HashMap<>();
	// hud elementId -> [anchor, dx, dy, scale]
	static final Map<String, double[]> HUD = new HashMap<>();
	// menu-level extras (panel backing visible, etc.)
	static final Map<String, JsonElement> META = new HashMap<>();

	private static boolean loaded = false;

	private ModsConfig() {
	}

	static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!Files.exists(PATH)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);
			if (root == null) {
				return;
			}
			JsonObject mods = root.getAsJsonObject("mods");
			if (mods != null) {
				for (String id : mods.keySet()) {
					JsonObject o = mods.getAsJsonObject(id);
					Map<String, JsonElement> m = new HashMap<>();
					for (String k : o.keySet()) {
						m.put(k, o.get(k));
					}
					VALUES.put(id, m);
				}
			}
			JsonObject hud = root.getAsJsonObject("hud");
			if (hud != null) {
				for (String id : hud.keySet()) {
					var arr = hud.getAsJsonArray(id);
					if (arr != null && arr.size() == 4) {
						HUD.put(id, new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble(),
								arr.get(2).getAsDouble(), arr.get(3).getAsDouble()});
					}
				}
			}
			JsonObject meta = root.getAsJsonObject("meta");
			if (meta != null) {
				for (String k : meta.keySet()) {
					META.put(k, meta.get(k));
				}
			}
		} catch (IOException | RuntimeException e) {
			com.origin.client.OriginClient.LOGGER.warn("Failed to read originclient-mods.json, using defaults", e);
		}
	}

	static synchronized void save() {
		try {
			JsonObject root = new JsonObject();
			JsonObject mods = new JsonObject();
			for (var e : VALUES.entrySet()) {
				JsonObject o = new JsonObject();
				for (var v : e.getValue().entrySet()) {
					o.add(v.getKey(), v.getValue());
				}
				mods.add(e.getKey(), o);
			}
			root.add("mods", mods);
			JsonObject hud = new JsonObject();
			for (var e : HUD.entrySet()) {
				var arr = new com.google.gson.JsonArray();
				for (double d : e.getValue()) {
					arr.add(d);
				}
				hud.add(e.getKey(), arr);
			}
			root.add("hud", hud);
			JsonObject meta = new JsonObject();
			for (var e : META.entrySet()) {
				meta.add(e.getKey(), e.getValue());
			}
			root.add("meta", meta);

			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException | RuntimeException e) {
			com.origin.client.OriginClient.LOGGER.warn("Failed to save originclient-mods.json", e);
		}
	}
}
