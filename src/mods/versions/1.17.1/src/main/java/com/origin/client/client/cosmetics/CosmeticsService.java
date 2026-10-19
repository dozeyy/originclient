package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.OriginClient;
import com.origin.client.client.social.SocialClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

	/**
	 * Where each worn slot sits relative to its default spot, in model px
	 * (FORMAT.md "Position tweaks"): players move items to suit their skin
	 * (Will, 2026-10-18). Every slot moves up (+) / down (-); head and face
	 * items also move sideways (+ = toward the player's left) and forward
	 * (+ = toward the face) (Will, 2026-10-19). Saved with what they wear, so
	 * everyone sees it.
	 */
	public record Adjust(float cape, float head, float face, float back, float headX, float headZ, float faceX,
			float faceZ) {
		public static final Adjust NONE = new Adjust(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
		/** One click of an arrow, and the furthest either way on each axis (the service clamps the same). */
		public static final float STEP = 0.5f, MAX = 4f;

		/** The directions a slot's item moves in. Y = up, X = the player's left, Z = toward the face. */
		public enum Axis {
			Y, X, Z
		}

		public Adjust(float cape, float head, float face, float back) {
			this(cape, head, face, back, 0f, 0f, 0f, 0f);
		}

		/** Head and face items move every way; the cape and back items only up / down. */
		public static boolean movesFreely(Cosmetics.Slot slot) {
			return slot == Cosmetics.Slot.HEAD || slot == Cosmetics.Slot.FACE;
		}

		/** The height tweak (+ = up). */
		public float get(Cosmetics.Slot slot) {
			return get(slot, Axis.Y);
		}

		public float get(Cosmetics.Slot slot, Axis axis) {
			return switch (axis) {
				case Y -> switch (slot) {
					case CAPE -> cape;
					case HEAD -> head;
					case FACE -> face;
					case BACK -> back;
					case THEME -> 0f;
				};
				case X -> slot == Cosmetics.Slot.HEAD ? headX : slot == Cosmetics.Slot.FACE ? faceX : 0f;
				case Z -> slot == Cosmetics.Slot.HEAD ? headZ : slot == Cosmetics.Slot.FACE ? faceZ : 0f;
			};
		}

		/** A copy with one slot's height moved to `v` (rounded to a STEP, clamped to MAX). */
		public Adjust with(Cosmetics.Slot slot, float v) {
			return with(slot, Axis.Y, v);
		}

		/** A copy with one slot moved to `v` along `axis`; an axis the slot can't move in is ignored. */
		public Adjust with(Cosmetics.Slot slot, Axis axis, float v) {
			float r = clean(v);
			if (axis != Axis.Y && !movesFreely(slot)) {
				return this;
			}
			boolean h = slot == Cosmetics.Slot.HEAD;
			return switch (axis) {
				case Y -> switch (slot) {
					case CAPE -> new Adjust(r, head, face, back, headX, headZ, faceX, faceZ);
					case HEAD -> new Adjust(cape, r, face, back, headX, headZ, faceX, faceZ);
					case FACE -> new Adjust(cape, head, r, back, headX, headZ, faceX, faceZ);
					case BACK -> new Adjust(cape, head, face, r, headX, headZ, faceX, faceZ);
					case THEME -> this;
				};
				case X -> h ? new Adjust(cape, head, face, back, r, headZ, faceX, faceZ)
						: new Adjust(cape, head, face, back, headX, headZ, r, faceZ);
				case Z -> h ? new Adjust(cape, head, face, back, headX, r, faceX, faceZ)
						: new Adjust(cape, head, face, back, headX, headZ, faceX, r);
			};
		}

		/** The JSON key of one slot's axis: the slot name for Y ("head"), plus X / Z ("headX", "faceZ"). */
		static String key(Cosmetics.Slot slot, Axis axis) {
			return axis == Axis.Y ? slot.key : slot.key + axis.name();
		}

		static float clean(float v) {
			if (!Float.isFinite(v)) {
				return 0f;
			}
			return Math.round(Math.max(-MAX, Math.min(MAX, v)) / STEP) * STEP;
		}
	}

	/**
	 * What a player wears; null = nothing in that slot. `face`, `back` and
	 * `theme` are newer than `cape` / `head`, and `adjust` newer still: the
	 * shorter forms are kept for callers (and old service replies) that only
	 * know those.
	 */
	public record Look(String cape, String head, String face, String back, String theme, Adjust adjust) {
		public static final Look NONE = new Look(null, null, null, null, null, Adjust.NONE);

		public Look {
			adjust = adjust != null ? adjust : Adjust.NONE;
		}

		public Look(String cape, String head, String face, String back, String theme) {
			this(cape, head, face, back, theme, Adjust.NONE);
		}

		public Look(String cape, String head) {
			this(cape, head, null, null, null, Adjust.NONE);
		}

		public String get(Cosmetics.Slot slot) {
			return switch (slot) {
				case CAPE -> cape;
				case HEAD -> head;
				case FACE -> face;
				case BACK -> back;
				case THEME -> theme;
			};
		}

		/** The height tweak (model px, + = up) for whatever is worn in `slot`. */
		public float adjust(Cosmetics.Slot slot) {
			return adjust.get(slot);
		}

		/** The position tweak (model px) along `axis` for whatever is worn in `slot`. */
		public float adjust(Cosmetics.Slot slot, Adjust.Axis axis) {
			return adjust.get(slot, axis);
		}

		/** True if something worn here (wings, a mask...) says the cape must not be drawn. */
		public boolean hidesCape() {
			for (Cosmetics.Slot slot : new Cosmetics.Slot[] {Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.BACK}) {
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
			for (Cosmetics.Slot slot : new Cosmetics.Slot[] {Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.BACK}) {
				Cosmetics.Item it = Cosmetics.get(get(slot));
				if (it != null) {
					off = Math.max(off, it.capeOffset());
				}
			}
			return off;
		}

		/** A copy with one slot changed (null = take it off). The height tweaks stay. */
		public Look with(Cosmetics.Slot slot, String id) {
			return switch (slot) {
				case CAPE -> new Look(id, head, face, back, theme, adjust);
				case HEAD -> new Look(cape, id, face, back, theme, adjust);
				case FACE -> new Look(cape, head, id, back, theme, adjust);
				case BACK -> new Look(cape, head, face, id, theme, adjust);
				case THEME -> new Look(cape, head, face, back, id, adjust);
			};
		}

		/** A copy with different height tweaks. */
		public Look withAdjust(Adjust a) {
			return new Look(cape, head, face, back, theme, a);
		}

		/** Everything worn on the character taken off (the menu theme and height tweaks stay). */
		public Look bare() {
			return new Look(null, null, null, null, theme, adjust);
		}
	}

	/** The signed-in player's own cosmetics; `plus` = an Origin+ member. */
	public record Mine(List<String> owned, Look wearing, boolean plus) {
		public Mine(List<String> owned, Look wearing) {
			this(owned, wearing, false);
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

	/** `origin` = in game on Origin lately (the name badge). */
	private record Entry(Look look, boolean plus, boolean origin, long freshUntil) {
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
	/** The last read of your own cosmetics failed (the screen says so instead of "Loading..." forever). */
	private static volatile boolean mineFailed = false;
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

	/** DEV-ONLY (dev-shots world set, OriginDevWorld): what your own player wears, without the service. */
	private static volatile Look devLook = null;

	/** DEV-ONLY: dresses your own player in `look` (null = back to the service); ignored outside dev shots. */
	public static void devWear(Look look) {
		if (com.origin.client.client.OriginDevShots.enabled()) {
			devLook = look;
		}
	}

	/** What a player is wearing, for rendering. Render thread; never blocks. */
	public static Look lookOf(UUID uuid) {
		Look dev = devLook;
		if (dev != null && uuid != null && uuid.equals(selfId())) {
			return dev;
		}
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

	/**
	 * Does this player get the Origin badge by their name? Anyone in game on
	 * Origin (Will, 2026-10-18: not only Origin+ members): you always, others
	 * when the service saw their game lately, or when they're a member. Same
	 * cache and lookup queue as {@link #lookOf}. Render thread; never blocks.
	 */
	public static boolean usesOrigin(UUID uuid) {
		if (uuid == null) {
			return false;
		}
		if (uuid.equals(selfId())) {
			return true;   // you are using Origin right now, service or not
		}
		if (status != Status.READY) {
			return false;
		}
		Entry e = LOOKS.get(uuid);
		if ((e == null || e.freshUntil() < System.currentTimeMillis()) && WANTED.size() < MAX_WANTED) {
			WANTED.add(uuid);
		}
		return e != null && (e.origin() || e.plus());
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
	 * Shows `look` as yours straight away WITHOUT saving it - the Cosmetics
	 * screen's height arrows, which save once the player stops clicking
	 * ({@link #wear} follows).
	 */
	public static void show(Look look) {
		picked = look;
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
			if (error != null && picked == look) {
				picked = null;   // only if nothing newer was shown meanwhile (a height nudge during the save)
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
				mineFailed = true;
				return;
			}
			List<String> owned = new ArrayList<>();
			if (o.get("owned") instanceof JsonArray arr) {
				for (JsonElement el : arr) {
					if (el != null && el.isJsonPrimitive()) {
						owned.add(el.getAsString());   // unknown ids are harmless: the screen only shows catalog items
					}
				}
			}
			Look worn = lookFrom(o);
			if (!o.has("adjust")) {
				// a service from before height tweaks: keep what was set this session
				Mine m = mine;
				worn = worn.withAdjust(m != null ? m.wearing().adjust() : Adjust.NONE);
			}
			mine = new Mine(owned, worn, flag(o, "plus"));
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
				LOOKS.put(u, new Entry(p != null ? lookFrom(p) : Look.NONE, p != null && flag(p, "plus"),
						p != null && flag(p, "origin"), now + FRESH_MS));
			}
		} catch (Exception e) {
			for (UUID u : batch) {
				Entry old = LOOKS.get(u);
				LOOKS.put(u, new Entry(old != null ? old.look() : Look.NONE, old != null && old.plus(),
						old != null && old.origin(), now + RETRY_MS));
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
		if (was == null || !look.adjust().equals(was.adjust())) {
			body.add("adjust", adjustJson(look.adjust()));
		}
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
				Look saved = lookFrom(o);
				if (!o.has("adjust")) {
					saved = saved.withAdjust(look.adjust());   // a service from before height tweaks: keep them for this session
				}
				mine = new Mine(m != null ? m.owned() : List.of(), saved, m != null && m.plus());
				if (picked == look) {
					picked = null;   // a newer pick/nudge shown meanwhile stays on screen until its own save lands
				}
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
		Adjust adjust = adjustFrom(o.get("adjust"));
		if (o.get("equipped") instanceof JsonObject eq) {
			o = eq;
		}
		return new Look(known(o, "cape", Cosmetics.Slot.CAPE), known(o, "head", Cosmetics.Slot.HEAD),
				known(o, "face", Cosmetics.Slot.FACE), known(o, "back", Cosmetics.Slot.BACK),
				known(o, "theme", Cosmetics.Slot.THEME), adjust);
	}

	private static final Cosmetics.Slot[] ADJUST_SLOTS = {Cosmetics.Slot.CAPE, Cosmetics.Slot.HEAD, Cosmetics.Slot.FACE, Cosmetics.Slot.BACK};

	/** {"cape": 1.5, "head": -0.5, "headX": 1, "faceZ": 0.5, ...}; absent / odd values = 0 (default spot). */
	private static Adjust adjustFrom(JsonElement e) {
		Adjust a = Adjust.NONE;
		if (e instanceof JsonObject ao) {
			for (Cosmetics.Slot slot : ADJUST_SLOTS) {
				for (Adjust.Axis axis : Adjust.Axis.values()) {
					a = a.with(slot, axis, (float) Cosmetics.num(ao, Adjust.key(slot, axis), 0));
				}
			}
		}
		return a;
	}

	private static JsonObject adjustJson(Adjust a) {
		JsonObject o = new JsonObject();
		for (Cosmetics.Slot slot : ADJUST_SLOTS) {
			for (Adjust.Axis axis : Adjust.Axis.values()) {
				if (a.get(slot, axis) != 0f) {
					o.addProperty(Adjust.key(slot, axis), a.get(slot, axis));
				}
			}
		}
		return o;
	}

	// `plus` and `origin` are newer than the service's first release: absent / odd = false.
	private static boolean flag(JsonObject o, String key) {
		try {
			JsonElement e = o != null ? o.get(key) : null;
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
			if (id.isBlank() || id.length() > 64) {
				return null;
			}
			Cosmetics.Item item = Cosmetics.get(id);
			return item != null && item.slot() != slot ? null : id;
		} catch (Exception ex) {
			return null;
		}
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
		return postJson(url, body, null);
	}

	/** `bearer` = Origin's sign-in token (only /v1/equip needs one), else null. */
	private static Reply postJson(String url, JsonObject body, String bearer) throws Exception {
		HttpRequest.Builder rb = request(url)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (bearer != null) {
			rb.header("Authorization", "Bearer " + bearer);
		}
		HttpResponse<String> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
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

	private static String undashed(UUID u) {
		return u.toString().replace("-", "");
	}

	private static String trimSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}
