package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the cosmetics catalog (cosmetics v2, cosmetics/FORMAT.md):
 *
 *   assets/originclient/cosmetics/catalog.json          every item's meta, shop order
 *   assets/originclient/cosmetics/&lt;id&gt;/item.json        the item's art (model, cape, anim, fx, theme)
 *   assets/originclient/cosmetics/&lt;id&gt;/texture.png     its texture (or theme preview)
 *
 * both written by tools/cosmetics/build.mjs. The list is read once (on first
 * use, from whatever thread asks: the service thread parses ids, the render
 * thread draws); each item.json is read lazily when something needs the art
 * and cached. Everything is fail-soft: no catalog = no cosmetics, a bad or
 * missing item.json = that item is absent (one log line, never per frame),
 * ids the catalog doesn't know are ignored by every caller.
 */
public final class CosmeticCatalog {
	private CosmeticCatalog() {
	}

	private static final String BASE = "cosmetics/";
	private static final long RETRY_MS = 5_000L;

	private static volatile Map<String, Cosmetics.Item> items = Map.of();
	private static volatile List<Cosmetics.Item> ordered = List.of();
	private static volatile boolean loaded = false;
	private static volatile long lastTryMs = 0;

	private static final Map<String, Optional<CosmeticDef>> DEFS = new ConcurrentHashMap<>();
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	/** Every catalog item in shop order. */
	public static List<Cosmetics.Item> items() {
		ensureLoaded();
		return ordered;
	}

	/** The catalog entry for `id`, or null if the catalog doesn't know it. */
	public static Cosmetics.Item item(String id) {
		if (id == null) {
			return null;
		}
		ensureLoaded();
		return items.get(id);
	}

	/** The item's parsed art, or null if it isn't in the catalog or its item.json is missing / unusable. */
	public static CosmeticDef def(String id) {
		if (id == null || item(id) == null) {
			return null;
		}
		return DEFS.computeIfAbsent(id, CosmeticCatalog::loadDef).orElse(null);
	}

	/** Where an item's texture.png lives (resolved by the texture manager like any resource). */
	public static Identifier texture(String id) {
		return Identifier.fromNamespaceAndPath("originclient", BASE + id + "/texture.png");
	}

	/** Re-reads everything on the next ask (resource reload / dev). */
	public static void invalidate() {
		loaded = false;
		DEFS.clear();
		LOGGED.clear();
	}

	// ------------------------------------------------------------- loading

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastTryMs < RETRY_MS) {
			return;   // not there a moment ago (resources still loading?): don't hammer the disk
		}
		lastTryMs = now;
		try {
			JsonElement root = readJson(Identifier.fromNamespaceAndPath("originclient", BASE + "catalog.json"));
			if (root == null) {
				return;
			}
			Map<String, Cosmetics.Item> map = new LinkedHashMap<>();
			for (JsonObject o : entries(root)) {
				Cosmetics.Item it = Cosmetics.Item.fromJson(o);
				if (it != null && !map.containsKey(it.id())) {
					map.put(it.id(), it);
				} else if (it == null) {
					logOnce("catalog:" + o, "Origin cosmetics: skipping a catalog entry without a usable id/kind");
				}
			}
			items = map;
			ordered = List.copyOf(map.values());
			loaded = true;
		} catch (Throwable t) {
			// Not final: the first read often comes from the service's background thread while the
			// game is still loading its resources, and a pack swapped out mid-read throws. Giving up
			// here left the whole session with no cosmetics, so the next ask after RETRY_MS tries again.
			logOnce("catalog", "Origin cosmetics: couldn't read catalog.json; will retry", t);
		}
	}

	/** catalog.json is "every item's meta, in shop order": accept a bare array, {"items": [...]} or {id: meta}. */
	private static List<JsonObject> entries(JsonElement root) {
		List<JsonObject> out = new ArrayList<>();
		if (root instanceof JsonArray arr) {
			for (JsonElement e : arr) {
				if (e instanceof JsonObject o) {
					out.add(o);
				}
			}
		} else if (root instanceof JsonObject obj) {
			if (obj.get("items") != null && !obj.get("items").isJsonPrimitive()) {
				return entries(obj.get("items"));
			}
			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				if (e.getValue() instanceof JsonObject o) {
					if (!o.has("id")) {
						o = o.deepCopy();
						o.addProperty("id", e.getKey());
					}
					out.add(o);
				}
			}
		}
		return out;
	}

	private static Optional<CosmeticDef> loadDef(String id) {
		try {
			JsonElement root = readJson(Identifier.fromNamespaceAndPath("originclient", BASE + id + "/item.json"));
			if (root instanceof JsonObject obj) {
				CosmeticDef def = CosmeticDef.parse(obj, items.get(id));
				if (def != null) {
					return Optional.of(def);
				}
			}
			logOnce("def:" + id, "Origin cosmetics: " + id + " has a missing or unusable item.json; it is skipped");
		} catch (Throwable t) {
			logOnce("def:" + id, "Origin cosmetics: " + id + " item.json failed to load; it is skipped", t);
		}
		return Optional.empty();
	}

	/** Parses a resource as JSON; null if it doesn't exist (a malformed file throws). */
	private static JsonElement readJson(Identifier file) throws Exception {
		// No resource manager yet (the service thread asks very early in boot, before Minecraft has made
		// one): count it as "missing" so the RETRY_MS retry picks the catalog up once resources exist.
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.getResourceManager() == null) {
			return null;
		}
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
		if (res.isEmpty()) {
			return null;
		}
		try (InputStream in = res.get().open(); InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r);
		}
	}

	private static void logOnce(String key, String msg) {
		if (LOGGED.add(key)) {
			OriginClient.LOGGER.warn(msg);
		}
	}

	private static void logOnce(String key, String msg, Throwable t) {
		if (LOGGED.add(key)) {
			OriginClient.LOGGER.warn(msg, t);
		}
	}
}
