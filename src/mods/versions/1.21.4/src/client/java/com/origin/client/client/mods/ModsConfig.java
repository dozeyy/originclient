package com.origin.client.client.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
	// named config profiles: profileName -> a frozen {mods, hud} snapshot. A
	// LinkedHashMap so the list keeps the order profiles were created in.
	static final java.util.LinkedHashMap<String, JsonObject> PROFILES = new java.util.LinkedHashMap<>();

	private static boolean loaded = false;

	private ModsConfig() {
	}

	static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				JsonObject root = GSON.fromJson(reader, JsonObject.class);
				if (root != null) {
					parseInto(root);
				}
			} catch (IOException | RuntimeException e) {
				com.origin.client.OriginClient.LOGGER.warn("Failed to read originclient-mods.json, using defaults", e);
			}
			return;
		}
		// Fresh instance — no user config yet. Seed from the bundled default so a
		// brand-new client starts with Origin's curated settings + HUD layout
		// instead of the bare schema defaults. The moment the user changes
		// anything, save() writes their own originclient-mods.json, which then
		// wins on every subsequent load (this branch is skipped once it exists).
		loadSeedDefaults();
	}

	// Loads the bundled default-mods-config.json (shipped in the jar) into the
	// in-memory stores. A missing or unparseable seed falls through silently to
	// the schema defaults, so a bad/absent resource can never break startup.
	private static void loadSeedDefaults() {
		try (InputStream in = ModsConfig.class.getResourceAsStream("/assets/originclient/default-mods-config.json")) {
			if (in == null) {
				// A shipped jar should always carry its seed; if it doesn't, a
				// fresh instance silently getting bare schema defaults would be
				// baffling — surface it instead.
				com.origin.client.OriginClient.LOGGER.warn("No bundled default-mods-config.json on classpath; new instance falls back to schema defaults");
				return;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject root = GSON.fromJson(reader, JsonObject.class);
				if (root != null) {
					parseInto(root);
				}
			}
			com.origin.client.OriginClient.LOGGER.info("Seeded new instance from bundled default-mods-config.json");
		} catch (IOException | RuntimeException e) {
			com.origin.client.OriginClient.LOGGER.warn("Failed to read bundled default-mods-config.json", e);
		}
	}

	// Reads a parsed config root (mods / hud / meta blocks) into VALUES/HUD/META.
	// Shared by the user-file load and the bundled-seed load.
	private static void parseInto(JsonObject root) {
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
				// save() writes 5 values [anchor, dx, dy, scale, bg]; pre-bg
				// files have 4. Accept >= 4 and keep EVERY value (so bg
				// round-trips). The old "== 4" guard silently dropped every
				// 5-element entry on load, resetting all moved HUD elements to
				// their defaults on relaunch — the "positions not saved" bug.
				if (arr != null && arr.size() >= 4) {
					double[] vals = new double[arr.size()];
					for (int i = 0; i < arr.size(); i++) {
						vals[i] = arr.get(i).getAsDouble();
					}
					HUD.put(id, vals);
				}
			}
		}
		JsonObject meta = root.getAsJsonObject("meta");
		if (meta != null) {
			for (String k : meta.keySet()) {
				META.put(k, meta.get(k));
			}
		}
		JsonObject profiles = root.getAsJsonObject("profiles");
		if (profiles != null) {
			for (String name : profiles.keySet()) {
				JsonElement snap = profiles.get(name);
				if (snap != null && snap.isJsonObject()) {
					PROFILES.put(name, snap.getAsJsonObject());
				}
			}
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
			JsonObject profiles = new JsonObject();
			for (var e : PROFILES.entrySet()) {
				profiles.add(e.getKey(), e.getValue());
			}
			root.add("profiles", profiles);

			writeAtomically(PATH, root);
		} catch (IOException | RuntimeException e) {
			com.origin.client.OriginClient.LOGGER.warn("Failed to save originclient-mods.json", e);
		}
	}

	// ---- profiles ----
	// A profile is a frozen {mods, hud} snapshot of the settings store — NOT the
	// menu meta (background opacity etc.), which is a menu preference, not part of
	// a gameplay loadout. Snapshots are deep JSON copies, so applying one and then
	// editing settings can never mutate the stored profile.

	static synchronized JsonObject snapshotCurrent() {
		ensureLoaded();
		JsonObject snap = new JsonObject();
		JsonObject mods = new JsonObject();
		for (var e : VALUES.entrySet()) {
			JsonObject o = new JsonObject();
			for (var v : e.getValue().entrySet()) {
				o.add(v.getKey(), deepCopy(v.getValue()));
			}
			mods.add(e.getKey(), o);
		}
		snap.add("mods", mods);
		JsonObject hud = new JsonObject();
		for (var e : HUD.entrySet()) {
			var arr = new com.google.gson.JsonArray();
			for (double d : e.getValue()) {
				arr.add(d);
			}
			hud.add(e.getKey(), arr);
		}
		snap.add("hud", hud);
		return snap;
	}

	static synchronized void restoreFrom(JsonObject snap) {
		ensureLoaded();
		VALUES.clear();
		HUD.clear();
		if (snap != null) {
			// parseInto only ever ADDS keys, so clearing first is what makes a
			// profile an exact swap (a setting present in the old state but absent
			// in the profile must not linger).
			parseInto(snap);
		}
		save();
	}

	static synchronized void saveProfile(String name) {
		PROFILES.put(name, snapshotCurrent());
		save();
	}

	static synchronized boolean applyProfile(String name) {
		JsonObject snap = PROFILES.get(name);
		if (snap == null) {
			return false;
		}
		restoreFrom(snap);
		return true;
	}

	static synchronized void deleteProfile(String name) {
		if (PROFILES.remove(name) != null) {
			save();
		}
	}

	static synchronized java.util.List<String> profileNames() {
		ensureLoaded();
		return new java.util.ArrayList<>(PROFILES.keySet());
	}

	// Gson JsonElements are mutable and shared by reference; a profile must own an
	// independent copy so later edits to the live store can't reach into it.
	private static JsonElement deepCopy(JsonElement e) {
		return e == null ? com.google.gson.JsonNull.INSTANCE : e.deepCopy();
	}

	// Crash-safe write: serialize to a sibling .tmp, then atomically rename it
	// over the real file. A hard kill (the launcher can terminate the game,
	// which also skips the on-exit flush) mid-write can only ever leave a stale
	// .tmp — the real config keeps its last-good contents instead of being
	// truncated to garbage and resetting every setting on next load.
	static void writeAtomically(Path path, JsonObject root) throws IOException {
		Files.createDirectories(path.getParent());
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}
		try {
			Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
