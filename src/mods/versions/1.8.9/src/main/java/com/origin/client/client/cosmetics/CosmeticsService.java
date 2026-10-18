package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.origin.client.OriginClient;
import com.origin.client.client.net.OriginHttp;
import com.origin.client.client.social.SocialClient;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
 *
 * 1.8.9: requests open through net/OriginHttp (the old jre-legacy lacks the
 * CA roots Cloudflare uses); the signed-in account is Minecraft.getSession();
 * Gson 2.2.4 has no JsonArray.add(String), so ids go in as JsonPrimitives.
 */
public final class CosmeticsService {
	private CosmeticsService() {
	}

	/**
	 * What a player wears; null = nothing in that slot. `face`, `back` and
	 * `theme` are newer than `cape` / `head`: the two-argument form is kept
	 * for callers (and old service replies) that only know those two.
	 */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Look {
		private final String cape, head, face, back, theme;

		public Look(String cape, String head, String face, String back, String theme) {
			this.cape = cape;
			this.head = head;
			this.face = face;
			this.back = back;
			this.theme = theme;
		}

		public Look(String cape, String head) {
			this(cape, head, null, null, null);
		}

		public String cape() {
			return this.cape;
		}

		public String head() {
			return this.head;
		}

		public String face() {
			return this.face;
		}

		public String back() {
			return this.back;
		}

		public String theme() {
			return this.theme;
		}

		// 1.8.9: Java 8 has no switch expressions, statement switches
		public String get(Cosmetics.Slot slot) {
			switch (slot) {
				case CAPE:
					return cape;
				case HEAD:
					return head;
				case FACE:
					return face;
				case BACK:
					return back;
				default:
					return theme;
			}
		}

		private static final Cosmetics.Slot[] WORN = {Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.BACK};

		/** True if something worn here (wings, a mask...) says the cape must not be drawn. */
		public boolean hidesCape() {
			for (Cosmetics.Slot slot : WORN) {
				Cosmetics.Item it = Cosmetics.get(get(slot));
				if (it != null && it.hidesCape()) {
					return true;
				}
			}
			return false;
		}

		/** How far (model px) the cape must sit further back so worn items don't poke through it. */
		public float capeOffset() {
			float off = 0f;
			for (Cosmetics.Slot slot : WORN) {
				Cosmetics.Item it = Cosmetics.get(get(slot));
				if (it != null) {
					off = Math.max(off, it.capeOffset());
				}
			}
			return off;
		}

		/** A copy with one slot changed (null = take it off). */
		public Look with(Cosmetics.Slot slot, String id) {
			switch (slot) {
				case CAPE:
					return new Look(id, head, face, back, theme);
				case HEAD:
					return new Look(cape, id, face, back, theme);
				case FACE:
					return new Look(cape, head, id, back, theme);
				case BACK:
					return new Look(cape, head, face, id, theme);
				default:
					return new Look(cape, head, face, back, id);
			}
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
					&& java.util.Objects.equals(this.head, other.head)
					&& java.util.Objects.equals(this.face, other.face)
					&& java.util.Objects.equals(this.back, other.back)
					&& java.util.Objects.equals(this.theme, other.theme);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(cape, head, face, back, theme);
		}

		public static final Look NONE = new Look(null, null, null, null, null);
	}

	/** The signed-in player's own cosmetics; `plus` = an Origin+ member. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Mine {
		private final List<String> owned;
		private final Look wearing;
		private final boolean plus;

		public Mine(List<String> owned, Look wearing, boolean plus) {
			this.owned = owned;
			this.wearing = wearing;
			this.plus = plus;
		}

		public Mine(List<String> owned, Look wearing) {
			this(owned, wearing, false);
		}

		public boolean plus() {
			return this.plus;
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
					&& java.util.Objects.equals(this.wearing, other.wearing) && this.plus == other.plus;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(owned, wearing, plus);
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
		private final boolean plus;
		private final long freshUntil;

		private Entry(Look look, boolean plus, long freshUntil) {
			this.look = look;
			this.plus = plus;
			this.freshUntil = freshUntil;
		}

		public Look look() {
			return this.look;
		}

		public boolean plus() {
			return this.plus;
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
			return java.util.Objects.equals(this.look, other.look) && this.plus == other.plus
					&& this.freshUntil == other.freshUntil;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(look, plus, freshUntil);
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
	/** The last read of your own cosmetics failed (the screen says so instead of "Loading..." forever). */
	private static volatile boolean mineFailed = false;
	/** What you just picked, shown straight away while the save is in flight. */
	private static volatile Look picked = null;

	private static final java.util.concurrent.atomic.AtomicBoolean STARTED = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Called once from client init. 1.8.9: idempotent -- both the foundation's
	 * init and SocialHooks may start it, and a second tick loop would double
	 * every request.
	 */
	public static void start() {
		if (!STARTED.compareAndSet(false, true)) {
			return;
		}
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

	/** True when the last attempt to read your own cosmetics failed and none are loaded. */
	public static boolean mineFailed() {
		return mineFailed && mine == null;
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

	/**
	 * What YOU wear right now (a pick still being saved counts), or NONE while the
	 * service isn't ready. For things that only ever concern the local player,
	 * like the menu theme. Render thread; never blocks.
	 */
	public static Look myLook() {
		if (status != Status.READY) {
			return Look.NONE;
		}
		Look p = picked;
		if (p != null) {
			return p;
		}
		Mine m = mine;
		return m != null ? m.wearing() : Look.NONE;
	}

	/**
	 * Is this player an Origin+ member (for the badge)? Same cache and lookup
	 * queue as {@link #lookOf}; unknown / not looked up yet / old service
	 * without the field = false. Render thread; never blocks.
	 */
	public static boolean isPlus(UUID uuid) {
		if (uuid == null || status != Status.READY) {
			return false;
		}
		if (uuid.equals(selfId())) {
			Mine m = mine;
			return m != null && m.plus();
		}
		Entry e = LOOKS.get(uuid);
		if ((e == null || e.freshUntil() < System.currentTimeMillis()) && WANTED.size() < MAX_WANTED) {
			WANTED.add(uuid);
		}
		return e != null && e.plus();
	}

	/** {@link #isPlus(UUID)} for a service-style id string (dashed or not); bad input = false. */
	public static boolean isPlus(String uuid) {
		if (uuid == null) {
			return false;
		}
		try {
			String t = uuid.trim();
			if (t.length() == 32) {
				t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
						+ t.substring(16, 20) + "-" + t.substring(20);
			}
			return isPlus(UUID.fromString(t));
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Re-reads your own cosmetics (e.g. when the Cosmetics screen opens, after a purchase).
	 * If the service wasn't reachable earlier (game started offline), looks for it again
	 * now instead of waiting out the 10-minute rediscovery in tick().
	 */
	public static void refreshMine() {
		WORKER.execute(() -> {
			if (status != Status.READY) {
				discover();
			}
			refreshMineNow();
		});
	}

	/**
	 * Wear `look` (every slot; only the changed ones are sent). Completes with null on success, or a message
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

	// 1.8.9: Session.getProfile() parses the uuid string on every call, and lookOf
	// runs per player per frame, so the parsed id is kept per session.
	private static volatile Session selfSession = null;
	private static volatile UUID selfUuid = null;

	/** The signed-in account's uuid (null for an unparsable / offline id). */
	public static UUID selfId() {
		Session s = Minecraft.getMinecraft().getSession();
		if (s == null) {
			return null;
		}
		if (s != selfSession) {
			GameProfile p = null;
			try {
				p = s.getProfile();
			} catch (Throwable ignored) {
				// no usable id
			}
			selfUuid = p != null ? p.getId() : null;
			selfSession = s;
		}
		return selfUuid;
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
				mineFailed = true;
				return;
			}
			List<String> owned = new ArrayList<>();
			if (o.get("owned") instanceof JsonArray) {
				for (JsonElement el : (JsonArray) o.get("owned")) {
					if (el != null && el.isJsonPrimitive()) {
						owned.add(el.getAsString());   // unknown ids are harmless: the screen only shows catalog items
					}
				}
			}
			mine = new Mine(owned, lookFrom(o), plusFrom(o));
			mineFailed = false;
		} catch (Exception e) {
			mineFailed = true;
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
		batch.forEach(u -> ids.add(new JsonPrimitive(undashed(u))));   // Gson 2.2.4: no add(String)
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
				LOOKS.put(u, new Entry(p != null ? lookFrom(p) : Look.NONE, p != null && plusFrom(p), now + FRESH_MS));
			}
		} catch (Exception e) {
			for (UUID u : batch) {
				Entry old = LOOKS.get(u);
				LOOKS.put(u, new Entry(old != null ? old.look() : Look.NONE, old != null && old.plus(), now + RETRY_MS));
			}
		}
	}

	private static void putIfChanged(JsonObject body, String slot, String now, String was, boolean always) {
		if (always || !java.util.Objects.equals(now, was)) {
			body.addProperty(slot, now);
		}
	}

	private static String wearNow(Look look) {
		if (status != Status.READY) {
			return "Cosmetics aren't available right now.";
		}
		JsonObject body = new JsonObject();
		// Only the slots that change: a string wears it, null takes it off, and an
		// absent key leaves it as saved. That keeps what the service saved but isn't
		// showing - a lapsed member's Origin+ cape comes back when they rejoin -
		// instead of overwriting it with the null this client was shown.
		Mine m0 = mine;
		Look was = m0 != null ? m0.wearing() : null;
		putIfChanged(body, "cape", look.cape(), was == null ? null : was.cape(), was == null);
		putIfChanged(body, "head", look.head(), was == null ? null : was.head(), was == null);
		putIfChanged(body, "face", look.face(), was == null ? null : was.face(), was == null);
		putIfChanged(body, "back", look.back(), was == null ? null : was.back(), was == null);
		putIfChanged(body, "theme", look.theme(), was == null ? null : was.theme(), was == null);
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
				mine = new Mine(m != null ? m.owned() : java.util.Collections.<String>emptyList(), lookFrom(o),
						m != null && m.plus());
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

	/**
	 * Reads a look from a service object: the slots may sit under "equipped"
	 * ({equipped:{cape,head,face,back,theme}}) or directly on it (the batch
	 * reply). Each slot is read on its own, so one odd value costs only that slot.
	 */
	private static Look lookFrom(JsonObject o) {
		if (o == null) {
			return Look.NONE;
		}
		if (o.get("equipped") instanceof JsonObject) {
			o = (JsonObject) o.get("equipped");
		}
		return new Look(known(o, "cape", Cosmetics.Slot.CAPE), known(o, "head", Cosmetics.Slot.HEAD),
				known(o, "face", Cosmetics.Slot.FACE), known(o, "back", Cosmetics.Slot.BACK),
				known(o, "theme", Cosmetics.Slot.THEME));
	}

	// `plus` is newer than the service's first release: absent / odd = not a member.
	private static boolean plusFrom(JsonObject o) {
		try {
			JsonElement e = o != null ? o.get("plus") : null;
			return e != null && e.isJsonPrimitive() && e.getAsBoolean();
		} catch (Exception ignored) {
			return false;
		}
	}

	// An id string for one slot. An id the catalog knows to belong to ANOTHER slot is dropped; an id
	// it doesn't know is kept (the catalog may still be loading, and renderers ignore unknown ids).
	private static String known(JsonObject o, String key, Cosmetics.Slot slot) {
		try {
			JsonElement e = o.get(key);
			if (e == null || !e.isJsonPrimitive()) {
				return null;
			}
			String id = e.getAsString();
			// 1.8.9: Java 8 has no String.isBlank
			if (id.trim().isEmpty() || id.length() > 64) {
				return null;
			}
			Cosmetics.Item item = Cosmetics.get(id);
			return item != null && item.slot() != slot ? null : id;
		} catch (Exception ex) {
			return null;
		}
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

	// Java 8: no java.net.http, so plain HttpURLConnection --
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
		HttpURLConnection c = OriginHttp.open(url);   // 1.8.9: Origin's trust roots (old JRE)
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
