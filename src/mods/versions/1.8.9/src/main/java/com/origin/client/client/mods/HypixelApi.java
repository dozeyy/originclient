package com.origin.client.client.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.net.OriginHttp;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.net.HttpURLConnection;
import java.util.Iterator;
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
 *
 * 1.8.9: the connection comes from net/OriginHttp -- 1.8.9's legacy Java 8
 * runtime lacks the CA roots api.hypixel.net's certificate chains to, so a plain
 * HttpURLConnection would fail every lookup with a PKIX error. Gson is 2.2.4
 * (new JsonParser().parse, as here).
 */
public final class HypixelApi {
	private HypixelApi() {
	}

	/** What the Level Head / tab features show for one player. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Stats {
		private final int networkLevel;
		private final int bedwarsStar;
		private final int finalKills;
		private final int finalDeaths;
		private final int wins;
		private final int losses;

		public Stats(int networkLevel, int bedwarsStar, int finalKills, int finalDeaths, int wins, int losses) {
			this.networkLevel = networkLevel;
			this.bedwarsStar = bedwarsStar;
			this.finalKills = finalKills;
			this.finalDeaths = finalDeaths;
			this.wins = wins;
			this.losses = losses;
		}

		public int networkLevel() {
			return networkLevel;
		}

		public int bedwarsStar() {
			return bedwarsStar;
		}

		public int finalKills() {
			return finalKills;
		}

		public int finalDeaths() {
			return finalDeaths;
		}

		public int wins() {
			return wins;
		}

		public int losses() {
			return losses;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Stats)) {
				return false;
			}
			Stats other = (Stats) obj;
			return this.networkLevel == other.networkLevel
					&& this.bedwarsStar == other.bedwarsStar
					&& this.finalKills == other.finalKills
					&& this.finalDeaths == other.finalDeaths
					&& this.wins == other.wins
					&& this.losses == other.losses;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(networkLevel, bedwarsStar, finalKills, finalDeaths, wins, losses);
		}
	}

	// One cache slot per player: stats (null = not a Hypixel player / failed) + when
	// it may be asked again.
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Entry {
		private final Stats stats;
		private final long retryAt;

		private Entry(Stats stats, long retryAt) {
			this.stats = stats;
			this.retryAt = retryAt;
		}

		public Stats stats() {
			return stats;
		}

		public long retryAt() {
			return retryAt;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Entry)) {
				return false;
			}
			Entry other = (Entry) obj;
			return java.util.Objects.equals(this.stats, other.stats)
					&& this.retryAt == other.retryAt;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(stats, retryAt);
		}
	}

	private static final long FRESH_MS = 10L * 60L * 1000L;
	private static final long RETRY_MS = 60L * 1000L;
	private static final int MAX_QUEUE = 200;

	private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
	private static final LinkedHashSet<UUID> QUEUE = new LinkedHashSet<>();
	// Java 8: no java.net.http client, so one HttpURLConnection per lookup
	// (same timeout, no redirects), opened through OriginHttp's trust store.
	private static final int TIMEOUT_MS = 10_000;

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
				Iterator<UUID> it = QUEUE.iterator();
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
			HttpURLConnection res = OriginHttp.open("https://api.hypixel.net/v2/player?uuid=" + id);
			res.setConnectTimeout(TIMEOUT_MS);
			res.setReadTimeout(TIMEOUT_MS);
			res.setInstanceFollowRedirects(false);
			res.setRequestMethod("GET");
			res.setRequestProperty("API-Key", k);
			res.setRequestProperty("User-Agent", "OriginClient");
			int code = res.getResponseCode();
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
			CACHE.put(id, new Entry(parse(body(res)), now + FRESH_MS));
			// Spend the remaining quota evenly over the window instead of in a burst.
			long remaining = header(res, "RateLimit-Remaining", 10);
			return remaining <= 2 ? resetMs + 500L : 250L;
		} catch (Throwable t) {
			CACHE.put(id, new Entry(null, now + RETRY_MS));
			return 2_000L;
		}
	}

	private static long header(HttpURLConnection res, String name, long def) {
		try {
			String v = res.getHeaderField(name);
			return v == null ? def : Long.parseLong(v.trim());
		} catch (RuntimeException e) {
			return def;
		}
	}

	private static String body(HttpURLConnection res) throws java.io.IOException {
		return OriginHttp.readBody(res.getInputStream());
	}

	private static Stats parse(String body) {
		JsonObject root = new JsonParser().parse(body).getAsJsonObject();   // Gson 2.2.4 here: no static parseString
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
		Minecraft mc = Minecraft.getMinecraft();
		mc.addScheduledTask(() -> {
			if (mc.thePlayer != null) {
				mc.thePlayer.addChatMessage(new ChatComponentText(msg));
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
