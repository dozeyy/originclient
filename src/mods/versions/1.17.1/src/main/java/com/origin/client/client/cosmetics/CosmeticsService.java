package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
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
 *  - changing what YOU wear is proven with Minecraft's own server-join check
 *    (Mojang joinServer here, hasJoined on the service): the access token
 *    only ever goes to Mojang, as when joining any server;
 *  - everything fails soft: no service, no network or an offline account
 *    just means no cosmetics, never an error in game.
 */
public final class CosmeticsService {
	private CosmeticsService() {
	}

	/** What a player wears; null = nothing in that slot. */
	public record Look(String cape, String head) {
		public static final Look NONE = new Look(null, null);
	}

	/** The signed-in player's own cosmetics. */
	public record Mine(List<String> owned, Look wearing) {
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

	private record Entry(Look look, long freshUntil) {
	}

	private static final long FRESH_MS = 5L * 60L * 1000L;
	private static final long RETRY_MS = 60L * 1000L;
	private static final long REDISCOVER_MS = 10L * 60L * 1000L;
	private static final int BATCH = 100;
	private static final int MAX_WANTED = 500;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
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
		if (override != null && !override.isBlank()) {
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
			if (o.get("owned") instanceof JsonArray arr) {
				for (JsonElement el : arr) {
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
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		// A fresh random "server id" proves this request comes from whoever is
		// signed in: we tell Mojang we're joining it, the service asks Mojang
		// who did. Offline/test accounts can't do this.
		byte[] raw = new byte[20];
		new SecureRandom().nextBytes(raw);
		String serverId = hex(raw);
		try {
			// authlib of this era takes the GameProfile (the UUID overload is 1.20.2+).
			mc.getMinecraftSessionService().joinServer(user.getGameProfile(), user.getAccessToken(), serverId);
		} catch (AuthenticationException e) {
			return "Sign in with a Microsoft account to wear cosmetics.";
		} catch (Exception e) {
			return "Couldn't check your account with Minecraft. Try again.";
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", user.getName());
		body.addProperty("serverId", serverId);
		body.addProperty("cape", look.cape());
		body.addProperty("head", look.head());
		try {
			Reply r = postJson(api + "/v1/equip", body);
			if (r.status() == 200) {
				JsonObject o = r.response();
				Mine m = mine;
				mine = new Mine(m != null ? m.owned() : List.of(), lookFrom(o.getAsJsonObject("equipped")));
				picked = null;
				return null;
			}
			if (r.status() == 403) {
				return "You don't own that one yet.";
			}
			return "Couldn't save. Try again.";
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

	private record Reply(int status, JsonObject response) {
	}

	private static JsonObject getJson(String url) throws Exception {
		HttpResponse<String> r = HTTP.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + r.statusCode());
		}
		return new JsonParser().parse(r.body()).getAsJsonObject();
	}

	private static Reply postJson(String url, JsonObject body) throws Exception {
		HttpResponse<String> r = HTTP.send(request(url)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build(), HttpResponse.BodyHandlers.ofString());
		JsonObject o = null;
		try {
			o = new JsonParser().parse(r.body()).getAsJsonObject();
		} catch (Exception ignored) {
			// error bodies aren't needed beyond the status code
		}
		return new Reply(r.statusCode(), o != null ? o : new JsonObject());
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "OriginClient (will@willhenry.me)")
				.header("Accept", "application/json");
	}

	// HexFormat is Java 17; this module runs on Java 16.
	private static String hex(byte[] raw) {
		StringBuilder sb = new StringBuilder(raw.length * 2);
		for (byte b : raw) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	private static String undashed(UUID u) {
		return u.toString().replace("-", "");
	}

	private static String trimSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}
