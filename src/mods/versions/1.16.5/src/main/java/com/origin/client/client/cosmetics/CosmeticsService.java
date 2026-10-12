package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.OriginClient;
import com.origin.client.client.social.SocialClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the Origin cosmetics service (services/cosmetics). Rules:
 *  - never on the game thread: one background thread does every request;
 *  - render code only ever reads caches ({@link #lookOf}), which queue a
 *    lookup for players not seen yet, batched up to 100 per request;
 *  - what someone wears is cached for 5 minutes (a failed lookup retries
 *    after 1), so a full server costs a few requests, not one per frame;
 *  - changing what YOU wear uses Origin's sign-in, shared with friends
 *    (social/SocialClient#tokenFor, docs/SOCIAL.md "Identity v2"): a Bearer
 *    token from Mojang-signed proofs; no Mojang or Microsoft token ever
 *    reaches Origin;
 *  - everything fails soft: no service, no network or an offline account
 *    just means no cosmetics, never an error in game.
 */
public final class CosmeticsService {
	private CosmeticsService() {
	}

	/** What a player wears; null = nothing in that slot. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Look {
		private final String cape;
		private final String head;

		public Look(String cape, String head) {
			this.cape = cape;
			this.head = head;
		}

		public String cape() {
			return this.cape;
		}

		public String head() {
			return this.head;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Look)) {
				return false;
			}
			Look other = (Look) obj;
			return java.util.Objects.equals(this.cape, other.cape)
					&& java.util.Objects.equals(this.head, other.head);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(cape, head);
		}

		public static final Look NONE = new Look(null, null);
	}

	/** The signed-in player's own cosmetics. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Mine {
		private final List<String> owned;
		private final Look wearing;

		public Mine(List<String> owned, Look wearing) {
			this.owned = owned;
			this.wearing = wearing;
		}

		public List<String> owned() {
			return this.owned;
		}

		public Look wearing() {
			return this.wearing;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Mine)) {
				return false;
			}
			Mine other = (Mine) obj;
			return java.util.Objects.equals(this.owned, other.owned)
					&& java.util.Objects.equals(this.wearing, other.wearing);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(owned, wearing);
		}
	}

	public enum Status {
		/** still finding the service */
		STARTING,
		/** the store isn't switched on yet (empty api in service.json) */
		OFF,
		/** service.json or the service couldn't be reached */
		UNREACHABLE,
		READY
	}

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Entry {
		private final Look look;
		private final long freshUntil;

		private Entry(Look look, long freshUntil) {
			this.look = look;
			this.freshUntil = freshUntil;
		}

		public Look look() {
			return this.look;
		}

		public long freshUntil() {
			return this.freshUntil;
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
			return java.util.Objects.equals(this.look, other.look)
					&& this.freshUntil == other.freshUntil;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(look, freshUntil);
		}
	}

	private static final long FRESH_MS = 5L * 60L * 1000L;
	private static final long RETRY_MS = 60L * 1000L;
	private static final long REDISCOVER_MS = 10L * 60L * 1000L;
	private static final int BATCH = 100;
	private static final int MAX_WANTED = 500;

	private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Origin Cosmetics");
		t.setDaemon(true);
		return t;
	});

	private static volatile String api = null;
	private static volatile Status status = Status.STARTING;
	private static volatile long discoveredAt = 0;

	private static final Map<UUID, Entry> LOOKS = new ConcurrentHashMap<>();
	private static final Set<UUID> WANTED = ConcurrentHashMap.newKeySet();

	private static volatile Mine mine = null;
	/** What you just picked, shown straight away while the save is in flight. */
	private static volatile Look picked = null;

	/** Called once from client init. */
	public static void start() {
		WORKER.execute(() -> {
			discover();
			refreshMineNow();
		});
		WORKER.scheduleWithFixedDelay(CosmeticsService::tick, 2, 2, TimeUnit.SECONDS);
	}

	public static Status status() {
		return status;
	}

	public static Mine mine() {
		return mine;
	}

	/**
	 * The service's base URL once found (null while starting, switched off or
	 * unreachable). Friends / parties (social/SocialClient) live on the same
	 * Worker, so they share this one resolution, -Dorigin.cosmetics.api included.
	 */
	public static String api() {
		return status == Status.READY ? api : null;
	}

	/** What a player is wearing, for rendering. Render thread; never blocks. */
	public static Look lookOf(UUID uuid) {
		if (uuid == null || status != Status.READY) {
			return Look.NONE;
		}
		if (uuid.equals(selfId())) {
			Look p = picked;
			if (p != null) {
				return p;
			}
			Mine m = mine;
			return m != null ? m.wearing() : Look.NONE;
		}
		Entry e = LOOKS.get(uuid);
		if ((e == null || e.freshUntil() < System.currentTimeMillis()) && WANTED.size() < MAX_WANTED) {
			WANTED.add(uuid);
		}
		return e != null ? e.look() : Look.NONE;
	}

	/** Re-reads your own cosmetics (e.g. when the Cosmetics screen opens, after a purchase). */
	public static void refreshMine() {
		WORKER.execute(CosmeticsService::refreshMineNow);
	}

	/**
	 * Wear `look` (both slots). Completes with null on success, or a message
	 * to show the player. Your own view switches immediately.
	 */
	public static CompletableFuture<String> wear(Look look) {
		picked = look;
		CompletableFuture<String> done = new CompletableFuture<>();
		WORKER.execute(() -> {
			String error = wearNow(look);
			if (error != null) {
				picked = null;
			}
			done.complete(error);
		});
		return done;
	}

	// ------------------------------------------------------------- internals

	private static UUID selfId() {
		User user = Minecraft.getInstance().getUser();
		return user != null ? user.getGameProfile().getId() : null;
	}

	private static void tick() {
		try {
			if (status != Status.READY && System.currentTimeMillis() - discoveredAt > REDISCOVER_MS) {
				discover();
				refreshMineNow();
			}
			if (status == Status.READY && !WANTED.isEmpty()) {
				lookupBatch();
			}
		} catch (Throwable t) {
			// the worker must survive anything a single round throws
		}
	}

	private static void discover() {
		discoveredAt = System.currentTimeMillis();
		// A launch flag points the mod at a local/test service without
		// touching the live pointer: -Dorigin.cosmetics.api=http://...
		String override = System.getProperty("origin.cosmetics.api");
		if (override != null && !override.trim().isEmpty()) {
			api = trimSlash(override);
			status = Status.READY;
			return;
		}
		try {
			JsonObject o = getJson(Cosmetics.SERVICE_POINTER);
			String a = o != null && o.has("api") && !o.get("api").isJsonNull() ? o.get("api").getAsString().trim() : "";
			api = a.isEmpty() ? null : trimSlash(a);
			status = a.isEmpty() ? Status.OFF : Status.READY;
		} catch (Exception e) {
			status = Status.UNREACHABLE;
		}
	}

	private static void refreshMineNow() {
		UUID self = selfId();
		if (status != Status.READY || self == null) {
			return;
		}
		try {
			JsonObject o = getJson(api + "/v1/players/" + undashed(self));
			if (o == null) {
				return;
			}
			List<String> owned = new ArrayList<>();
			if (o.get("owned") instanceof JsonArray) {
				for (JsonElement el : (JsonArray) o.get("owned")) {
					if (Cosmetics.get(el.getAsString()) != null) {
						owned.add(el.getAsString());
					}
				}
			}
			mine = new Mine(owned, lookFrom(o.getAsJsonObject("equipped")));
		} catch (Exception e) {
			OriginClient.LOGGER.debug("Origin cosmetics: couldn't load your cosmetics", e);
		}
	}

	private static void lookupBatch() throws Exception {
		List<UUID> batch = new ArrayList<>();
		Iterator<UUID> it = WANTED.iterator();
		while (it.hasNext() && batch.size() < BATCH) {
			batch.add(it.next());
			it.remove();
		}
		JsonObject body = new JsonObject();
		JsonArray ids = new JsonArray();
		batch.forEach(u -> ids.add(undashed(u)));
		body.add("uuids", ids);
		long now = System.currentTimeMillis();
		try {
			Reply r = postJson(api + "/v1/players/equipped", body);
			if (r.status() != 200) {
				throw new IllegalStateException("HTTP " + r.status());
			}
			JsonObject players = r.response().getAsJsonObject("players");
			for (UUID u : batch) {
				JsonObject p = players != null ? players.getAsJsonObject(undashed(u)) : null;
				LOOKS.put(u, new Entry(p != null ? lookFrom(p) : Look.NONE, now + FRESH_MS));
			}
		} catch (Exception e) {
			for (UUID u : batch) {
				Entry old = LOOKS.get(u);
				LOOKS.put(u, new Entry(old != null ? old.look() : Look.NONE, now + RETRY_MS));
			}
		}
	}

	private static String wearNow(Look look) {
		if (status != Status.READY) {
			return "Cosmetics aren't available right now.";
		}
		JsonObject body = new JsonObject();
		body.addProperty("cape", look.cape());
		body.addProperty("head", look.head());
		try {
			// Origin's own sign-in, the one friends use (it signs in first if needed).
			String token = SocialClient.tokenFor(api, null);
			Reply r = postJson(api + "/v1/equip", body, token);
			if (r.status() == 401) {
				// the token ran out on the service's side: sign in again, once
				r = postJson(api + "/v1/equip", body, SocialClient.tokenFor(api, token));
			}
			if (r.status() == 200) {
				JsonObject o = r.response();
				Mine m = mine;
				mine = new Mine(m != null ? m.owned() : java.util.Collections.<String>emptyList(), lookFrom(o.getAsJsonObject("equipped")));
				picked = null;
				return null;
			}
			if (r.status() == 403) {
				return "You don't own that one yet.";
			}
			JsonElement err = r.response().get("error");
			if (r.status() == 400 && err != null && err.isJsonPrimitive()) {
				return err.getAsString();
			}
			return "Couldn't save. Try again.";
		} catch (SocialClient.NoToken e) {
			switch (e.why) {
				case SIGNED_OUT:
					return "Sign in with a Microsoft account to wear cosmetics.";
				case NO_ONLINE_PLAY:
					return "Your Microsoft account doesn't allow online play, so cosmetics can't be changed.";
				case REFUSED:
					return e.sentence != null ? e.sentence : "Couldn't check your account. Try again.";
				case UNAVAILABLE:
					return "Cosmetics aren't available right now.";
				default:
					return "Couldn't check your account with Minecraft. Try again.";
			}
		} catch (Exception e) {
			return "Couldn't reach Origin. Check your connection.";
		}
	}

	private static Look lookFrom(JsonObject o) {
		if (o == null) {
			return Look.NONE;
		}
		return new Look(known(o, "cape"), known(o, "head"));
	}

	// Only ids this build can draw; anything newer is treated as "nothing".
	private static String known(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		String id = e.getAsString();
		return Cosmetics.get(id) != null ? id : null;
	}

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Reply {
		private final int status;
		private final JsonObject response;

		private Reply(int status, JsonObject response) {
			this.status = status;
			this.response = response;
		}

		public int status() {
			return this.status;
		}

		public JsonObject response() {
			return this.response;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Reply)) {
				return false;
			}
			Reply other = (Reply) obj;
			return this.status == other.status
					&& java.util.Objects.equals(this.response, other.response);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(status, response);
		}
	}

	// Java 8 (1.16.5's runtime): no java.net.http, so plain HttpURLConnection --
	// the same timeouts and headers 1.21.1's HttpClient requests carry. It
	// follows same-protocol redirects by default (HttpClient's NORMAL policy
	// also refuses https -> http, as this does).
	private static JsonObject getJson(String url) throws Exception {
		HttpURLConnection c = open(url);
		try {
			c.setRequestMethod("GET");
			int code = c.getResponseCode();
			if (code != 200) {
				throw new IllegalStateException("HTTP " + code);
			}
			return new JsonParser().parse(body(c)).getAsJsonObject();
		} finally {
			c.disconnect();
		}
	}

	private static Reply postJson(String url, JsonObject body) throws Exception {
		return postJson(url, body, null);
	}

	/** `bearer` = Origin's sign-in token (only /v1/equip needs one), else null. */
	private static Reply postJson(String url, JsonObject body, String bearer) throws Exception {
		HttpURLConnection c = open(url);
		try {
			c.setRequestMethod("POST");
			c.setRequestProperty("Content-Type", "application/json");
			if (bearer != null) {
				c.setRequestProperty("Authorization", "Bearer " + bearer);
			}
			c.setDoOutput(true);
			try (OutputStream out = c.getOutputStream()) {
				out.write(body.toString().getBytes(StandardCharsets.UTF_8));
			}
			int code = c.getResponseCode();
			JsonObject o = null;
			try {
				o = new JsonParser().parse(body(c)).getAsJsonObject();
			} catch (Exception ignored) {
				// error bodies aren't needed beyond the status code
			}
			return new Reply(code, o != null ? o : new JsonObject());
		} finally {
			c.disconnect();
		}
	}

	private static HttpURLConnection open(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000);
		c.setReadTimeout(10000);
		c.setInstanceFollowRedirects(true);
		c.setRequestProperty("User-Agent", "OriginClient (will@willhenry.me)");
		c.setRequestProperty("Accept", "application/json");
		return c;
	}

	// The response body (the error stream for a 4xx/5xx), or "" if there is none.
	private static String body(HttpURLConnection c) throws java.io.IOException {
		InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
		if (in == null) {
			return "";
		}
		try (InputStream s = in) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			int n;
			while ((n = s.read(b)) > 0) {
				buf.write(b, 0, n);
			}
			return new String(buf.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static String undashed(UUID u) {
		return u.toString().replace("-", "");
	}

	private static String trimSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}
