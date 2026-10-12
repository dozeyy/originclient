package com.origin.client.client.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Hypixel public API (api.hypixel.net/v2/player), called with the PLAYER'S OWN
 * key from the Hypixel mod's options. Rules this follows (Hypixel's API policy):
 *  - one request at a time, from one background thread -- never the game thread;
 *  - every answer cached for 10 minutes (failures retried after 1 minute), so a
 *    lobby full of players costs one request each, not one per frame;
 *  - the per-key rate limit is honoured from the RateLimit-Remaining / -Reset
 *    headers, and a 429 backs off until the window resets;
 *  - a rejected key (403) stops all requests until the player enters a new one,
 *    with a single chat notice;
 *  - only real Mojang accounts (version-4 UUIDs) are looked up -- NPCs and
 *    Hypixel's nicked players are skipped.
 * The key is sent only in the API-Key header to api.hypixel.net and is never
 * logged (nothing here logs a request or its headers).
 */
public final class HypixelApi {
	private HypixelApi() {
	}

	/** What the Level Head / tab features show for one player. */
	public record Stats(int networkLevel, int bedwarsStar, int finalKills, int finalDeaths, int wins, int losses) {
	}

	// One cache slot per player: stats (null = not a Hypixel player / failed) + when
	// it may be asked again.
	private record Entry(Stats stats, long retryAt) {
	}

	private static final long FRESH_MS = 10L * 60L * 1000L;
	private static final long RETRY_MS = 60L * 1000L;
	private static final int MAX_QUEUE = 200;

	private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
	private static final LinkedHashSet<UUID> QUEUE = new LinkedHashSet<>();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	private static Thread worker;
	// The key that Hypixel rejected (null = none): no requests while it's still the
	// key in the options. Typing a different key clears the block.
	private static volatile String rejectedKey = null;

	/** Cached stats for a player, or null while unknown. Queues a lookup on first
	 *  sight (and when the cached answer goes stale). Safe to call every frame. */
	public static Stats get(UUID id) {
		if (id == null || id.version() != 4) {
			return null;
		}
		Entry e = CACHE.get(id);
		long now = System.currentTimeMillis();
		if (e == null || now >= e.retryAt()) {
			enqueue(id);
		}
		return e == null ? null : e.stats();
	}

	// The key from the options, copied in by Hypixel.tick() on the game thread so
	// the worker never touches the (unsynchronised) config maps itself.
	static volatile String optionKey = "";

	private static String key() {
		String k = optionKey;
		return k == null || k.isEmpty() || k.equals(rejectedKey) ? null : k;
	}

	private static void enqueue(UUID id) {
		if (key() == null) {
			return;
		}
		synchronized (QUEUE) {
			if (QUEUE.size() >= MAX_QUEUE || !QUEUE.add(id)) {
				return;
			}
			QUEUE.notifyAll();
			if (worker == null || !worker.isAlive()) {
				worker = new Thread(HypixelApi::run, "Origin Hypixel API");
				worker.setDaemon(true);
				worker.start();
			}
		}
	}

	private static void run() {
		while (true) {
			UUID id;
			synchronized (QUEUE) {
				while (QUEUE.isEmpty()) {
					try {
						QUEUE.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				var it = QUEUE.iterator();
				id = it.next();
				it.remove();
			}
			String k = key();
			if (k == null || !Hypixel.onHypixel()) {
				continue;   // left Hypixel or no key: drop the backlog quietly
			}
			Entry fresh = CACHE.get(id);
			if (fresh != null && System.currentTimeMillis() < fresh.retryAt()) {
				continue;   // answered while it sat in the queue
			}
			long waitMs = fetch(id, k);
			if (waitMs > 0) {
				try {
					Thread.sleep(waitMs);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/** One lookup; returns how long to pause before the next (rate limiting). */
	private static long fetch(UUID id, String k) {
		long now = System.currentTimeMillis();
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/player?uuid=" + id))
					.timeout(Duration.ofSeconds(10))
					.header("API-Key", k)
					.header("User-Agent", "OriginClient")
					.GET().build();
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			int code = res.statusCode();
			long resetMs = header(res, "RateLimit-Reset", 60) * 1000L;
			if (code == 403) {
				rejectedKey = k;
				synchronized (QUEUE) {
					QUEUE.clear();
				}
				notifyPlayer("Hypixel rejected your API key. Paste a valid key in Origin's Hypixel mod settings.");
				return 0;
			}
			if (code == 429) {
				CACHE.put(id, new Entry(null, now + RETRY_MS));
				return Math.max(5_000L, resetMs);
			}
			if (code != 200) {
				CACHE.put(id, new Entry(null, now + RETRY_MS));
				return 1_000L;
			}
			CACHE.put(id, new Entry(parse(res.body()), now + FRESH_MS));
			// Spend the remaining quota evenly over the window instead of in a burst.
			long remaining = header(res, "RateLimit-Remaining", 10);
			return remaining <= 2 ? resetMs + 500L : 250L;
		} catch (Throwable t) {
			CACHE.put(id, new Entry(null, now + RETRY_MS));
			return 2_000L;
		}
	}

	private static long header(HttpResponse<?> res, String name, long def) {
		try {
			return res.headers().firstValue(name).map(Long::parseLong).orElse(def);
		} catch (RuntimeException e) {
			return def;
		}
	}

	private static Stats parse(String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		JsonElement pe = root.get("player");
		if (pe == null || !pe.isJsonObject()) {
			return null;   // never joined Hypixel (or a nick)
		}
		JsonObject p = pe.getAsJsonObject();
		// Network level from experience: Hypixel's own formula.
		double exp = num(p, "networkExp");
		int level = (int) Math.floor(Math.sqrt(2.0 * exp + 30625.0) / 50.0 - 2.5);
		JsonObject ach = obj(p, "achievements");
		JsonObject bw = obj(obj(p, "stats"), "Bedwars");
		return new Stats(Math.max(1, level),
				(int) num(ach, "bedwars_level"),
				(int) num(bw, "final_kills_bedwars"),
				(int) num(bw, "final_deaths_bedwars"),
				(int) num(bw, "wins_bedwars"),
				(int) num(bw, "losses_bedwars"));
	}

	private static JsonObject obj(JsonObject o, String k) {
		if (o == null) {
			return null;
		}
		JsonElement e = o.get(k);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static double num(JsonObject o, String k) {
		if (o == null) {
			return 0;
		}
		try {
			JsonElement e = o.get(k);
			return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	private static void notifyPlayer(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal(msg), false);
			}
		});
	}

	/** Drops every cached answer (leaving a server). */
	static void clear() {
		CACHE.clear();
		synchronized (QUEUE) {
			QUEUE.clear();
		}
	}
}
