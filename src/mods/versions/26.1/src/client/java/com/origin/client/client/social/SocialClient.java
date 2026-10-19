package com.origin.client.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.origin.client.OriginClient;
import com.origin.client.client.OriginDevShots;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.social.SocialState.Friend;
import com.origin.client.client.social.SocialState.Me;
import com.origin.client.client.social.SocialState.Member;
import com.origin.client.client.social.SocialState.MemberState;
import com.origin.client.client.social.SocialState.Message;
import com.origin.client.client.social.SocialState.Party;
import com.origin.client.client.social.SocialState.PartyInvite;
import com.origin.client.client.social.SocialState.PartyMessage;
import com.origin.client.client.social.SocialState.Ping;
import com.origin.client.client.social.SocialState.Request;
import com.origin.client.client.social.SocialState.Warp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The game's half of Origin friends / chat / parties (docs/SOCIAL.md is the
 * contract; the service is services/cosmetics). Rules:
 *  - never on the game thread: ONE background thread does every request, in
 *    order, so a sync and an action can never race each other;
 *  - identity is sign-in v2 (MojangProof: the player certificate + signed
 *    profile Mojang issues, checked offline by the service). The access token
 *    only ever goes to Mojang; Origin's bearer token lives in memory only, and
 *    cosmetics' /v1/equip borrows it ({@link #signInForService});
 *  - the client thread only READS snapshots ({@link #state()}, chats) and
 *    writes plain presence values ({@link #setPresence}); toasts and chat lines
 *    reach it through {@link #EVENTS}, drained in Social.tick;
 *  - adaptive polling inside the contract's request budget (4 s while the
 *    Friends window is open, the party shares your LAN world or a DM/invite
 *    just arrived; 20 s in a party; 30 s idle; 60 s once the game window has
 *    been unfocused 5 minutes). In a party the 20 s poll is a HELD sync: the
 *    service keeps it open up to 20 s and answers the moment party chat or a
 *    DM lands, so /originpc lines show in a few seconds for the same requests. The
 *    held request waits off the worker (sendAsync), so actions never queue
 *    behind it; any action or the window opening drops it for a fresh sync;
 *  - everything fails soft: no service, no endpoints yet, no network or an
 *    offline account just means a calm message in the Friends window, never an
 *    error in game. Failures back off (15 s doubling to 5 min).
 */
public final class SocialClient {
	private SocialClient() {
	}

	public enum Status {
		/** still finding the service / signing in */
		STARTING,
		/** offline / cracked / test account, or Mojang won't vouch for it ({@link #signedOutReason}) */
		SIGNED_OUT,
		/** the service has no friends endpoints yet (or the store pointer is off) */
		COMING_SOON,
		/** no network / service down, and nothing loaded yet */
		UNREACHABLE,
		READY
	}

	/** Dev shots only: no network at all; SocialDemo fills the state. */
	static final boolean DEMO = OriginDevShots.enabled();

	// ---- the contract's request budget ----
	private static final long LIVE_MS = 4_000, PARTY_MS = 20_000, IDLE_MS = 30_000, AWAY_MS = 60_000;
	private static final long AWAY_AFTER_MS = 5 * 60_000, HOT_MS = 2 * 60_000;
	private static final long MISSING_RETRY_MS = 5 * 60_000, SIGNED_OUT_RETRY_MS = 10 * 60_000;
	private static final long WAKE_FLOOR_WHEN_DOWN_MS = 60_000;
	/**
	 * Held sync (docs/SOCIAL.md "Held sync"): the wait asked for; the soonest
	 * re-hold after an early answer that brought news; how much longer than the
	 * wait the request may take before it times out; how much shorter than the
	 * wait an answer must be to count as early; and when a lost hold is dropped.
	 */
	private static final long HOLD_MS = 20_000, HOLD_REARM_MS = 2_000;
	private static final long HOLD_TIMEOUT_MARGIN_MS = 15_000, HOLD_EARLY_SLACK_MS = 1_000;
	private static final long HELD_GIVE_UP_MS = HOLD_MS + 2 * HOLD_TIMEOUT_MARGIN_MS;
	private static final int CHAT_KEEP = 200;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Origin Social");
		t.setDaemon(true);
		return t;
	});

	// ---- what the screens read (written by the worker) ----
	private static volatile Status status = Status.STARTING;
	/** While SIGNED_OUT: why, as a sentence (null = plain "sign in with Microsoft"). */
	private static volatile String signedOutReason = null;
	/** READY, but the last sync failed (the window says "Reconnecting..."). */
	private static volatile boolean stale = false;
	private static volatile SocialState state = SocialState.EMPTY;
	/** DMs per friend uuid, oldest first. */
	private static final Map<String, List<Message>> CHATS = new ConcurrentHashMap<>();
	private static final List<PartyMessage> PARTY_CHAT = new CopyOnWriteArrayList<>();
	private static final Map<String, Integer> UNREAD = new ConcurrentHashMap<>();
	/** Appear-offline as just clicked, shown until the next sync confirms it. */
	private static volatile Boolean appearOfflinePending = null;
	/** Pings you just sent, shown at once until the service echoes them. */
	private static final List<Ping> LOCAL_PINGS = new CopyOnWriteArrayList<>();

	/** Things the client thread turns into toasts / chat lines (Social.tick drains it). */
	static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();

	enum EventKind { MESSAGE, REQUEST, INVITE, WARP, PARTY_CHAT }

	record Event(EventKind kind, String uuid, String name, String text, String partyId, Warp warp) {
	}

	// ---- presence (written by the client thread every tick) ----
	record PartyStateOut(String server, String dim, double x, double y, double z, float health, float maxHealth) {
	}

	record Presence(String activity, String server, String version, PartyStateOut party) {
	}

	private static volatile Presence presence = new Presence("menu", null, null, null);
	private static volatile boolean windowOpen = false;
	private static volatile String openChat = null;
	private static volatile long unfocusedSince = 0;
	private static volatile long hotUntil = 0;
	private static volatile boolean wake = false;

	// ---- worker-only ----
	private static String token = null;
	private static long tokenUntil = 0;
	private static JsonElement cursor = new JsonPrimitive(0);
	/** The friends list's version; when the service says it's unchanged it leaves the list out. */
	private static JsonElement friendsEtag = null;
	/** The last sync asked for our position (another online member is on our server). */
	private static boolean wantState = true;
	private static boolean firstSync = true;
	private static long nextSyncAt = 0, lastAttemptAt = 0;
	/** A held /v1/sync is out (its answer arrives via heldDone); holdGen tells a dropped one's answer apart. */
	private static boolean holding = false;
	private static long holdGen = 0, heldStartedAt = 0;
	/** The sendAsync future itself, so dropping a hold aborts the request. */
	private static CompletableFuture<HttpResponse<String>> heldCall = null;
	/** The presence the out-standing hold carried: if the player moves on, the hold is dropped (tick). */
	private static Presence heldPresence = null;
	/** The last answer's `held` (-1 = not a held answer) and the wait that was asked for. */
	private static long lastHeld = -1, lastWait = 0;
	/** The last answer brought something (a DM, a party line, a changed friends list or party). */
	private static boolean lastNews = false;
	private static int failures = 0;
	private static final Set<String> seenRequests = new HashSet<>();
	private static final Set<String> seenInvites = new HashSet<>();
	private static long seenWarpAt = -1;
	private static final Map<String, Long> pingUntil = new HashMap<>();
	/** Party lines this game already printed in chat (sent with /pc), so the echo isn't printed twice. */
	private static final List<String> ECHOED = new CopyOnWriteArrayList<>();

	/** Called once from client init. */
	public static void start() {
		if (DEMO) {
			status = Status.READY;
			return;
		}
		WORKER.scheduleWithFixedDelay(SocialClient::tick, 3, 1, TimeUnit.SECONDS);
	}

	// ------------------------------------------------------------------ reads

	public static Status status() {
		return status;
	}

	/**
	 * While SIGNED_OUT, the sentence explaining why when it isn't simply "no
	 * Microsoft account" (online play disabled, or the service's own reason).
	 */
	public static String signedOutReason() {
		return signedOutReason;
	}

	public static boolean stale() {
		return stale;
	}

	public static SocialState state() {
		return state;
	}

	public static List<Message> chat(String friendUuid) {
		List<Message> l = CHATS.get(friendUuid);
		return l == null ? List.of() : l;
	}

	public static List<PartyMessage> partyChat() {
		return PARTY_CHAT;
	}

	public static int unread(String friendUuid) {
		return UNREAD.getOrDefault(friendUuid, 0);
	}

	/** Unread DMs + incoming requests + party invites: the dot on the Friends tab / button. */
	public static int attention() {
		SocialState s = state;
		int n = s.incoming().size() + s.invites().size();
		for (int v : UNREAD.values()) {
			n += v;
		}
		return n;
	}

	public static boolean appearOffline() {
		Boolean p = appearOfflinePending;
		if (p != null) {
			return p;
		}
		Me me = state.me();
		return me != null && me.appearOffline();
	}

	/** Active pings, including ones you just sent. */
	public static List<Ping> pings() {
		List<Ping> out = new ArrayList<>(state.pings());
		out.addAll(LOCAL_PINGS);
		long now = System.currentTimeMillis();
		out.removeIf(p -> p.localUntil() < now);
		return out;
	}

	// ------------------------------------------------------- client-thread writes

	static void setPresence(Presence p) {
		presence = p;
	}

	static void setFocused(boolean focused, long now) {
		if (focused) {
			unfocusedSince = 0;
		} else if (unfocusedSince == 0) {
			unfocusedSince = now;
		}
	}

	/** The Friends window is open (live polling); `chatWith` = the friend whose chat is showing. */
	public static void setWindow(boolean open, String chatWith) {
		if (open && !windowOpen) {
			wake = true;
		}
		windowOpen = open;
		openChat = open ? chatWith : null;
		if (chatWith != null && open) {
			UNREAD.remove(chatWith);
		}
	}

	/** Ask for a sync as soon as the budget allows (an action just changed something). */
	public static void syncSoon() {
		wake = true;
	}

	// ------------------------------------------------------------------ actions
	// Every action runs on the worker, completes with null on success or a
	// player-facing sentence, and wakes a sync so the result shows at once.

	public static CompletableFuture<String> setAppearOffline(boolean v) {
		appearOfflinePending = v;
		JsonObject b = new JsonObject();
		b.addProperty("appearOffline", v);
		return act("/v1/settings", b, err -> {
			if (err != null) {
				appearOfflinePending = null;
			}
		});
	}

	public static CompletableFuture<String> addFriend(String name) {
		JsonObject b = new JsonObject();
		b.addProperty("name", name.trim());
		return act("/v1/friends/request", b, null);
	}

	public static CompletableFuture<String> respond(String uuid, boolean accept) {
		JsonObject b = new JsonObject();
		b.addProperty("uuid", uuid);
		b.addProperty("accept", accept);
		return act("/v1/friends/respond", b, null);
	}

	public static CompletableFuture<String> removeFriend(String uuid) {
		return act("/v1/friends/remove", uuidBody(uuid), null);
	}

	public static CompletableFuture<String> block(String uuid) {
		return act("/v1/friends/block", uuidBody(uuid), null);
	}

	public static CompletableFuture<String> unblock(String uuid) {
		return act("/v1/friends/unblock", uuidBody(uuid), null);
	}

	public static CompletableFuture<String> sendMessage(String to, String text) {
		String t = clip(text);
		String me = state.me() != null ? state.me().uuid() : "";
		Message pending = new Message("local-" + System.nanoTime(), me, to, t, System.currentTimeMillis(), true);
		chatList(to).add(pending);
		JsonObject b = new JsonObject();
		b.addProperty("to", to);
		b.addProperty("text", t);
		return act("/v1/messages/send", b, err -> {
			if (err != null) {
				chatList(to).remove(pending);
			}
		});
	}

	public static CompletableFuture<String> createParty() {
		return act("/v1/party/create", new JsonObject(), null);
	}

	/** Invites a friend; the service starts a party (you lead) if you aren't in one. */
	public static CompletableFuture<String> inviteToParty(String uuid) {
		return act("/v1/party/invite", uuidBody(uuid), null);
	}

	public static CompletableFuture<String> acceptInvite(String partyId) {
		JsonObject b = new JsonObject();
		b.addProperty("partyId", partyId);
		return act("/v1/party/accept", b, null);
	}

	public static CompletableFuture<String> declineInvite(String partyId) {
		JsonObject b = new JsonObject();
		b.addProperty("partyId", partyId);
		return act("/v1/party/decline", b, null);
	}

	public static CompletableFuture<String> leaveParty() {
		return act("/v1/party/leave", new JsonObject(), null);
	}

	public static CompletableFuture<String> kick(String uuid) {
		return act("/v1/party/kick", uuidBody(uuid), null);
	}

	public static CompletableFuture<String> promote(String uuid) {
		return act("/v1/party/promote", uuidBody(uuid), null);
	}

	/** Party chat. `echoedInChat` = the caller already printed the line in vanilla chat (/pc). */
	public static CompletableFuture<String> partyChat(String text, boolean echoedInChat) {
		String t = clip(text);
		Me me = state.me();
		PartyMessage pending = new PartyMessage("local-" + System.nanoTime(), me != null ? me.uuid() : "",
				me != null ? me.name() : "", t, System.currentTimeMillis(), true);
		PARTY_CHAT.add(pending);
		if (echoedInChat) {
			ECHOED.add(t);
		}
		JsonObject b = new JsonObject();
		b.addProperty("text", t);
		return act("/v1/party/chat", b, err -> {
			if (err != null) {
				PARTY_CHAT.remove(pending);
				ECHOED.remove(t);
			}
		});
	}

	public static CompletableFuture<String> ping(String server, String dim, double x, double y, double z, String label) {
		Me me = state.me();
		LOCAL_PINGS.add(new Ping("local-" + System.nanoTime(), me != null ? me.uuid() : "", me != null ? me.name() : "You",
				server, dim, x, y, z, label, System.currentTimeMillis() + 60_000));
		JsonObject b = new JsonObject();
		b.addProperty("server", server);
		b.addProperty("dim", dim);
		b.addProperty("x", x);
		b.addProperty("y", y);
		b.addProperty("z", z);
		if (label != null && !label.isEmpty()) {
			b.addProperty("label", label.length() > 32 ? label.substring(0, 32) : label);
		}
		return act("/v1/party/ping", b, err -> {
			if (err != null) {
				LOCAL_PINGS.clear();
			}
		});
	}

	public static CompletableFuture<String> warp(String server, String version) {
		JsonObject b = new JsonObject();
		b.addProperty("server", server);
		b.addProperty("version", version);
		return act("/v1/party/warp", b, null);
	}

	// ---------------------------------------------------------------- demo hooks

	static void demoInstall(SocialState s, Map<String, List<Message>> chats, List<PartyMessage> partyChat,
							Map<String, Integer> unread) {
		state = s;
		status = Status.READY;
		CHATS.clear();
		chats.forEach((k, v) -> CHATS.put(k, new CopyOnWriteArrayList<>(v)));
		PARTY_CHAT.clear();
		PARTY_CHAT.addAll(partyChat);
		UNREAD.clear();
		UNREAD.putAll(unread);
	}

	// ------------------------------------------------------------- the loop

	private static void tick() {
		try {
			long now = System.currentTimeMillis();
			if (holding) {
				// A held sync answers by itself. Something newer (an action, the window
				// opening, a new server / world) drops it for a fresh sync now, so
				// friends see where you are at once; a lost one is dropped too.
				boolean moved = !samePlace(presence, heldPresence);
				if (!wake && !moved && now - heldStartedAt < HELD_GIVE_UP_MS) {
					return;
				}
				if (moved) {
					wake = true;
				}
				dropHeld();
			}
			boolean down = status == Status.SIGNED_OUT || status == Status.COMING_SOON || status == Status.UNREACHABLE;
			boolean woke = wake && (!down || now - lastAttemptAt > WAKE_FLOOR_WHEN_DOWN_MS);
			if (now < nextSyncAt && !woke) {
				return;
			}
			wake = false;
			lastAttemptAt = now;
			String api = CosmeticsService.api();
			if (api == null) {
				status = switch (CosmeticsService.status()) {
					case OFF -> Status.COMING_SOON;
					case UNREACHABLE -> Status.UNREACHABLE;
					default -> Status.STARTING;
				};
				nextSyncAt = now + 15_000;
				return;
			}
			long wait = holdWait(now);
			if (wait > 0) {
				try {
					startHeld(api, wait, now);
				} catch (Exception e) {
					failed(now, e);
				}
				return;
			}
			settle(now, () -> syncNow(api));
		} catch (Throwable t) {
			// the worker must survive anything a single round throws
			holding = false;
			nextSyncAt = System.currentTimeMillis() + 60_000;
		}
	}

	private interface Round {
		void run() throws Exception;
	}

	/** One sync round's bookkeeping: status, failures and when the next round runs. Worker only. */
	private static void settle(long started, Round round) {
		try {
			round.run();
		} catch (Exception e) {
			failed(System.currentTimeMillis(), e);
			return;
		}
		signedOutReason = null;
		failures = 0;
		stale = false;
		status = Status.READY;
		long t = System.currentTimeMillis();
		long h = lastHeld;
		lastHeld = -1;
		if (h >= 0 && h < lastWait - HOLD_EARLY_SLACK_MS && lastNews) {
			// The service answered a held sync early because something arrived. Hold
			// again straight away (at most one round per HOLD_REARM_MS, so a busy
			// chat can't turn into a request storm). An early answer that brought
			// nothing new keeps the ordinary cadence, whatever the service did.
			nextSyncAt = Math.max(started + HOLD_REARM_MS, t);
		} else {
			// A quiet hold counts from when it started: in a party that is "now".
			nextSyncAt = (h >= 0 ? started : t) + interval(t);
		}
	}

	private static void failed(long now, Exception e) {
		if (e instanceof Missing) {
			status = Status.COMING_SOON;
			nextSyncAt = now + MISSING_RETRY_MS;
		} else if (e instanceof SignedOut so) {
			signedOutReason = so.sentence;
			status = Status.SIGNED_OUT;
			nextSyncAt = now + SIGNED_OUT_RETRY_MS;
		} else {
			failures++;
			if (status == Status.READY) {
				stale = true;
			} else {
				status = Status.UNREACHABLE;
			}
			long backoff = Math.min(5 * 60_000L, 15_000L << Math.min(5, failures - 1));
			nextSyncAt = now + Math.max(interval(now), backoff);
			OriginClient.LOGGER.debug("Origin social: sync failed ({} in a row)", failures, e);
		}
	}

	private static long interval(long now) {
		boolean live = windowOpen || (state.party() != null && wantState) || now < hotUntil;
		long base = live ? LIVE_MS : state.party() != null ? PARTY_MS : IDLE_MS;
		long away = unfocusedSince;
		if (away > 0 && now - away > AWAY_AFTER_MS) {
			base = AWAY_MS;
		}
		return base;
	}

	/**
	 * How long to ask the service to hold this sync (0 = an ordinary one). In a
	 * party, while nothing polls fast already: party chat (/originpc) then shows
	 * within a couple of seconds instead of waiting for the next 20 s poll, for
	 * the same number of requests. An older service ignores `wait` and answers at
	 * once (no `held` in the answer), which simply keeps the old cadence.
	 */
	private static long holdWait(long now) {
		boolean live = windowOpen || wantState || now < hotUntil;
		return state.party() != null && !firstSync && !live ? HOLD_MS : 0;
	}

	/** Sends a held /v1/sync without blocking the worker; its answer comes back through {@link #heldDone}. */
	private static void startHeld(String api, long wait, long now) throws Exception {
		if (token == null || System.currentTimeMillis() > tokenUntil) {
			signIn(api);
		}
		String sent = token;
		Presence p = presence;
		HttpRequest req = request(api + "/v1/sync", syncBody(wait, p), sent, wait + HOLD_TIMEOUT_MARGIN_MS);
		CompletableFuture<HttpResponse<String>> call = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		// Only now is a hold really out (a throw above leaves `holding` false, so tick carries on).
		long gen = ++holdGen;
		holding = true;
		heldStartedAt = now;
		heldPresence = p;
		heldCall = call;
		call.whenComplete((resp, err) -> WORKER.execute(() -> heldDone(gen, now, wait, sent, resp, err)));
	}

	private static void heldDone(long gen, long started, long wait, String sent, HttpResponse<String> resp, Throwable err) {
		if (gen != holdGen || !holding) {
			return; // dropped for a newer sync
		}
		holding = false;
		heldCall = null;
		settle(started, () -> {
			if (err != null) {
				throw err instanceof Exception ex ? ex : new IllegalStateException(err);
			}
			Reply r = reply(resp);
			if (r.status() == 401) {
				// token ran out: an ordinary sync signs in again and retries once
				String api = CosmeticsService.api();
				if (api == null) {
					throw new IllegalStateException("no service");
				}
				if (sent.equals(token)) {
					token = null;
				}
				syncNow(api);
				return;
			}
			lastWait = wait;
			finishSync(r);
		});
	}

	private static void dropHeld() {
		holding = false;
		holdGen++;   // its answer, if it still comes, is ignored (heldDone)
		CompletableFuture<HttpResponse<String>> c = heldCall;
		heldCall = null;
		heldPresence = null;
		if (c != null) {
			c.cancel(true);   // the sendAsync future itself: aborts the request (JDK 16+)
		}
	}

	private static void syncNow(String api) throws Exception {
		finishSync(authed(api, "/v1/sync", syncBody(0, presence)));
	}

	/** Same activity, server, version and party-world key: what a hold's presence write showed is still true. */
	private static boolean samePlace(Presence a, Presence b) {
		if (a == null || b == null) {
			return a == b;
		}
		return java.util.Objects.equals(a.activity(), b.activity()) && java.util.Objects.equals(a.server(), b.server())
				&& java.util.Objects.equals(a.version(), b.version())
				&& java.util.Objects.equals(a.party() == null ? null : a.party().server(), b.party() == null ? null : b.party().server());
	}

	private static void finishSync(Reply r) throws Exception {
		if (r.status() == 404) {
			throw new Missing();
		}
		if (r.status() != 200) {
			throw new IllegalStateException("HTTP " + r.status());
		}
		JsonObject o = r.body();
		lastHeld = o.has("held") && !o.get("held").isJsonNull() ? lng(o, "held") : -1;
		if (bool(o, "stopped")) {
			// A newer hold of this account ended this one (a second Origin window on the
			// same account): a bare answer with nothing to apply; keep the 20 s cadence.
			lastHeld = -1;
			lastNews = false;
			return;
		}
		Party before = state.party();
		JsonObject pO = obj(o, "party");
		// Chat or a different party is news (hold again soon). A refreshed friends list
		// alone is not, EXCEPT when it is why a hold was answered at once (held 0: our
		// friendsEtag was stale, e.g. a friend changed server): this answer carries the
		// fresh etag, so the next hold can start right away and can't repeat this.
		lastNews = arr(o, "messages").size() > 0 || arr(o, "partyMessages").size() > 0
				|| (lastHeld == 0 && o.has("friends"))
				|| !java.util.Objects.equals(before == null ? null : before.id(), pO == null ? null : str(pO, "id"));
		apply(o);
	}

	private static JsonObject syncBody(long wait, Presence p) {
		JsonObject body = new JsonObject();
		body.add("since", cursor);
		if (friendsEtag != null) {
			body.add("friendsEtag", friendsEtag);
		}
		if (wait > 0) {
			body.addProperty("wait", wait);
		}
		JsonObject pres = new JsonObject();
		pres.addProperty("client", "game");
		pres.addProperty("activity", p.activity());
		if ("server".equals(p.activity()) && p.server() != null) {
			pres.addProperty("server", p.server());
		} else {
			pres.add("server", JsonNull.INSTANCE);
		}
		pres.addProperty("version", p.version());
		body.add("presence", pres);
		// The position only while the service wants it (another member is in our LAN
		// world); otherwise just the world's key (`stateServer`), so it can tell us
		// when someone joins. Both off unless FairPlay allows positions here.
		PartyStateOut here = state.party() != null ? p.party() : null;
		PartyStateOut ps = here != null && wantState ? here : null;
		if (here != null && !wantState) {
			body.addProperty("stateServer", here.server());
		}
		if (ps != null) {
			JsonObject o = new JsonObject();
			o.addProperty("server", ps.server());
			o.addProperty("dim", ps.dim());
			o.addProperty("x", round1(ps.x()));
			o.addProperty("y", round1(ps.y()));
			o.addProperty("z", round1(ps.z()));
			o.addProperty("health", round1(ps.health()));
			o.addProperty("maxHealth", round1(ps.maxHealth()));
			body.add("partyState", o);
		} else {
			body.add("partyState", JsonNull.INSTANCE);
		}
		return body;
	}

	// ------------------------------------------------------------- parsing

	private static void apply(JsonObject o) {
		long now = System.currentTimeMillis();
		if (o.has("cursor") && !o.get("cursor").isJsonNull()) {
			cursor = o.get("cursor");
		}
		if (o.has("friendsEtag") && !o.get("friendsEtag").isJsonNull()) {
			friendsEtag = o.get("friendsEtag");
		}
		// missing (an older service) = yes, as the original contract had it
		wantState = !o.has("wantState") || o.get("wantState").isJsonNull() || bool(o, "wantState");
		JsonObject meO = obj(o, "me");
		Me me = meO == null ? state.me() : new Me(uid(str(meO, "uuid")), str(meO, "name"), bool(meO, "appearOffline"));
		if (me != null && appearOfflinePending != null && appearOfflinePending == me.appearOffline()) {
			appearOfflinePending = null;
		}
		String self = me != null ? me.uuid() : "";

		// Lists the service left out (unchanged since friendsEtag) keep their last value.
		SocialState prev = state;
		List<Friend> friends = new ArrayList<>(o.has("friends") ? List.of() : prev.friends());
		for (JsonElement e : arr(o, "friends")) {
			JsonObject f = e.getAsJsonObject();
			friends.add(new Friend(uid(str(f, "uuid")), str(f, "name"), bool(f, "online"), str(f, "client"),
					str(f, "activity"), str(f, "server"), str(f, "version"), lng(f, "lastSeen")));
		}
		friends.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1)
				: String.CASE_INSENSITIVE_ORDER.compare(nz(a.name()), nz(b.name())));

		JsonObject req = obj(o, "requests");
		List<Request> incoming = req == null && !o.has("requests") ? prev.incoming() : requests(req, "incoming");
		List<Request> outgoing = req == null && !o.has("requests") ? prev.outgoing() : requests(req, "outgoing");
		List<Request> blocked = prev.blocked();
		if (o.has("blocked")) {
			blocked = new ArrayList<>();
			for (JsonElement e : arr(o, "blocked")) {
				JsonObject b = e.getAsJsonObject();
				blocked.add(new Request(uid(str(b, "uuid")), str(b, "name"), 0));
			}
		}

		Party party = null;
		JsonObject pO = obj(o, "party");
		if (pO != null) {
			List<Member> members = new ArrayList<>();
			for (JsonElement e : arr(pO, "members")) {
				JsonObject m = e.getAsJsonObject();
				JsonObject st = obj(m, "state");
				MemberState ms = st == null ? null : new MemberState(str(st, "server"), str(st, "dim"),
						dbl(st, "x"), dbl(st, "y"), dbl(st, "z"), (float) dbl(st, "health"), (float) dbl(st, "maxHealth"));
				members.add(new Member(uid(str(m, "uuid")), str(m, "name"), bool(m, "online"), ms, lng(m, "stateAt")));
			}
			party = new Party(str(pO, "id"), uid(str(pO, "leader")), List.copyOf(members));
		}
		// a different party (or none) starts a fresh party chat
		Party oldParty = state.party();
		if (!java.util.Objects.equals(oldParty == null ? null : oldParty.id(), party == null ? null : party.id())) {
			PARTY_CHAT.removeIf(m -> !m.pending());
		}

		List<PartyInvite> invites = new ArrayList<>();
		for (JsonElement e : arr(o, "partyInvites")) {
			JsonObject i = e.getAsJsonObject();
			invites.add(new PartyInvite(str(i, "partyId"), uid(str(i, "from")), str(i, "name"), lng(i, "at")));
		}

		Warp warp = null;
		JsonObject wO = obj(o, "warp");
		if (wO != null) {
			warp = new Warp(str(wO, "server"), str(wO, "version"), lng(wO, "at"), uid(str(wO, "from")), str(wO, "name"));
		}

		// pings: the service lists the ones still alive; keep each one's local deadline
		List<Ping> pings = new ArrayList<>();
		Set<String> alive = new HashSet<>();
		for (JsonElement e : arr(o, "pings")) {
			JsonObject pg = e.getAsJsonObject();
			String id = str(pg, "id");
			if (id == null) {
				continue;
			}
			alive.add(id);
			long life = Math.max(1_000, Math.min(60_000, lng(pg, "expiresAt") - lng(pg, "at")));
			long until = pingUntil.computeIfAbsent(id, k -> now + life);
			String from = uid(str(pg, "from"));
			pings.add(new Ping(id, from, str(pg, "name"), str(pg, "server"), str(pg, "dim"),
					dbl(pg, "x"), dbl(pg, "y"), dbl(pg, "z"), str(pg, "label"), until));
			if (from.equals(self)) {
				LOCAL_PINGS.clear();
			}
		}
		pingUntil.keySet().retainAll(alive);

		SocialState next = new SocialState(me, List.copyOf(friends), List.copyOf(incoming), List.copyOf(outgoing),
				List.copyOf(blocked), party, List.copyOf(pings), List.copyOf(invites), warp);

		// DMs, merged per conversation (pending copies replaced by the real ones)
		for (JsonElement e : arr(o, "messages")) {
			JsonObject m = e.getAsJsonObject();
			Message msg = new Message(str(m, "id"), uid(str(m, "from")), uid(str(m, "to")), nz(str(m, "text")), lng(m, "at"), false);
			boolean mine = msg.from().equals(self);
			String other = mine ? msg.to() : msg.from();
			List<Message> list = chatList(other);
			if (msg.id() != null && list.stream().anyMatch(x -> msg.id().equals(x.id()))) {
				continue;
			}
			if (mine) {
				for (Message x : list) {
					if (x.pending() && x.text().equals(msg.text())) {
						list.remove(x);
						break;
					}
				}
			}
			insertSorted(list, msg);
			while (list.size() > CHAT_KEEP) {
				list.remove(0);
			}
			if (!mine && !firstSync) {
				if (!(windowOpen && other.equals(openChat))) {
					UNREAD.merge(other, 1, Integer::sum);
				}
				hotUntil = now + HOT_MS;
				EVENTS.add(new Event(EventKind.MESSAGE, other, nameIn(next, other, str(m, "name")), msg.text(), null, null));
			}
		}

		// party chat
		for (JsonElement e : arr(o, "partyMessages")) {
			JsonObject m = e.getAsJsonObject();
			PartyMessage pm = new PartyMessage(str(m, "id"), uid(str(m, "from")), str(m, "name"), nz(str(m, "text")), lng(m, "at"), false);
			if (pm.id() != null && PARTY_CHAT.stream().anyMatch(x -> pm.id().equals(x.id()))) {
				continue;
			}
			boolean mine = pm.from().equals(self);
			boolean echoed = false;
			if (mine) {
				for (PartyMessage x : PARTY_CHAT) {
					if (x.pending() && x.text().equals(pm.text())) {
						PARTY_CHAT.remove(x);
						break;
					}
				}
				echoed = ECHOED.remove(pm.text());
			}
			PARTY_CHAT.add(pm);
			if (!firstSync && !echoed) {
				EVENTS.add(new Event(EventKind.PARTY_CHAT, pm.from(), pm.name(), pm.text(), null, null));
			}
		}
		while (PARTY_CHAT.size() > CHAT_KEEP) {
			PARTY_CHAT.remove(0);
		}

		// new requests / invites / warps -> toasts (never for the first load)
		for (Request r : incoming) {
			if (seenRequests.add(r.uuid()) && !firstSync) {
				hotUntil = now + HOT_MS;
				EVENTS.add(new Event(EventKind.REQUEST, r.uuid(), r.name(), null, null, null));
			}
		}
		seenRequests.retainAll(incoming.stream().map(Request::uuid).toList());
		for (PartyInvite i : invites) {
			if (seenInvites.add(i.partyId() + "|" + i.from()) && !firstSync) {
				hotUntil = now + HOT_MS;
				EVENTS.add(new Event(EventKind.INVITE, i.from(), i.name(), null, i.partyId(), null));
			}
		}
		if (warp != null && warp.at() != seenWarpAt) {
			seenWarpAt = warp.at();
			if (!firstSync && !warp.from().equals(self)) {
				EVENTS.add(new Event(EventKind.WARP, warp.from(), nameIn(next, warp.from(), warp.name()), null, null, warp));
			}
		}

		state = next;
		firstSync = false;
	}

	private static List<Request> requests(JsonObject req, String key) {
		List<Request> out = new ArrayList<>();
		for (JsonElement e : arr(req, key)) {
			JsonObject r = e.getAsJsonObject();
			out.add(new Request(uid(str(r, "uuid")), str(r, "name"), lng(r, "at")));
		}
		return out;
	}

	private static String nameIn(SocialState s, String uuid, String fallback) {
		String n = s.nameOf(uuid);
		return n != null ? n : fallback != null ? fallback : "A friend";
	}

	private static List<Message> chatList(String uuid) {
		return CHATS.computeIfAbsent(uuid == null ? "" : uuid, k -> new CopyOnWriteArrayList<>());
	}

	private static void insertSorted(List<Message> list, Message m) {
		// pending copies stay at the end, after every confirmed message
		int i = list.size();
		while (i > 0 && list.get(i - 1).pending()) {
			i--;
		}
		while (i > 0 && list.get(i - 1).at() > m.at()) {
			i--;
		}
		list.add(i, m);
	}

	// ------------------------------------------------------------- transport

	private static final class Missing extends Exception {
	}

	private static final class SignedOut extends Exception {
		/** null = no Microsoft account (offline / dev); else the sentence to show. */
		final String sentence;
		/** Mojang refused the player certificate (multiplayer disabled). */
		final boolean noOnlinePlay;

		SignedOut(String sentence, boolean noOnlinePlay) {
			this.sentence = sentence;
			this.noOnlinePlay = noOnlinePlay;
		}
	}

	/** Shown when Mojang won't issue this account a player certificate. */
	public static final String NO_ONLINE_PLAY = "Your Microsoft account doesn't allow online play, so friends can't be used.";

	private record Reply(int status, JsonObject body) {
	}

	/** One action on the worker: null on success, else the sentence to show. */
	private static CompletableFuture<String> act(String path, JsonObject body, java.util.function.Consumer<String> after) {
		CompletableFuture<String> done = new CompletableFuture<>();
		if (DEMO) {
			done.complete(null);
			return done;
		}
		WORKER.execute(() -> {
			String err;
			try {
				err = call(path, body);
			} catch (Throwable t) {
				err = "Couldn't reach Origin. Check your connection.";
			}
			if (after != null) {
				try {
					after.accept(err);
				} catch (Throwable ignored) {
					// bookkeeping only
				}
			}
			wake = true;
			done.complete(err);
		});
		return done;
	}

	/** Worker thread only. */
	private static String call(String path, JsonObject body) {
		String api = CosmeticsService.api();
		if (api == null) {
			return "Friends aren't available right now.";
		}
		try {
			Reply r = authed(api, path, body);
			if (r.status() == 200) {
				return null;
			}
			String msg = str(r.body(), "error");
			if (msg != null && !msg.isBlank()) {
				return msg;
			}
			return switch (r.status()) {
				case 404 -> "Friends aren't available yet.";
				case 429 -> "Slow down a little and try again.";
				case 403 -> "You can't do that.";
				default -> "Something went wrong. Try again.";
			};
		} catch (SignedOut e) {
			signedOutReason = e.sentence;
			status = Status.SIGNED_OUT;
			return e.sentence != null ? e.sentence : "Sign in with Microsoft to use friends.";
		} catch (Missing e) {
			status = Status.COMING_SOON;
			return "Friends aren't available yet.";
		} catch (Exception e) {
			return "Couldn't reach Origin. Check your connection.";
		}
	}

	/** POST with the bearer token; a 401 signs in again once and retries. */
	private static Reply authed(String api, String path, JsonObject body) throws Exception {
		if (token == null || System.currentTimeMillis() > tokenUntil) {
			signIn(api);
		}
		Reply r = post(api + path, body, token);
		if (r.status() == 401) {
			token = null;
			signIn(api);
			r = post(api + path, body, token);
		}
		return r;
	}

	/** Sign-in v2 (MojangProof) -> /v1/auth. Throws SignedOut / Missing / IOException. Worker thread only. */
	private static void signIn(String api) throws Exception {
		User user = Minecraft.getInstance().getUser();
		if (user == null || user.getProfileId() == null) {
			throw new SignedOut(null, false);
		}
		Reply r = null;
		// A refused proof is retried once with a freshly fetched certificate (rules out a
		// stale/expired cached one); a second refusal is the service's final word.
		for (int attempt = 0; attempt < 2; attempt++) {
			JsonObject b;
			try {
				b = MojangProof.authBody(user.getProfileId(), user.getName(), user.getAccessToken());
			} catch (MojangProof.Refused e) {
				throw e.noOnlinePlay ? new SignedOut(NO_ONLINE_PLAY, true) : new SignedOut(null, false);
			}
			r = post(api + "/v1/auth", b, null);
			if (r.status() != 401 && r.status() != 403) {
				break;
			}
			MojangProof.forget();
		}
		if (r.status() == 404) {
			throw new Missing();
		}
		if (r.status() == 401 || r.status() == 403) {
			String why = str(r.body(), "error");
			throw new SignedOut(why != null && !why.isBlank() ? why : null, false);
		}
		if (r.status() == 400) {
			// the service doesn't understand this sign-in: its sentence says to update Origin
			String why = str(r.body(), "error");
			throw new SignedOut(why != null && !why.isBlank() ? why : "Please update Origin to use friends.", false);
		}
		// anything else (503 = the service's Mojang keys aren't loaded yet) is a calm retry later
		String t = str(r.body(), "token");
		if (r.status() != 200 || t == null) {
			throw new IllegalStateException("auth HTTP " + r.status());
		}
		token = t;
		long exp = lng(r.body(), "expiresAt");
		// a few minutes' margin; a bad/absent expiresAt still re-auths daily
		long now = System.currentTimeMillis();
		tokenUntil = exp > now ? exp - 5 * 60_000 : now + 23L * 3600_000;
	}

	/** Why {@link #signInForService} gave no token. */
	public enum SignInFailure {
		/** offline / dev account */
		NO_ACCOUNT,
		/** Mojang won't issue a player certificate (multiplayer disabled) */
		NO_ONLINE_PLAY,
		/** the service refused the proof; `sentence` says why */
		REFUSED,
		/** the service isn't there / has no sign-in yet */
		MISSING,
		/** no network / Mojang or Origin down */
		UNREACHABLE
	}

	/** Origin's bearer token, or why there is none (`sentence` = the service's own words, may be null). */
	public record SignIn(String token, SignInFailure failure, String sentence) {
	}

	/**
	 * The one shared sign-in, for other Origin calls that need the bearer token
	 * (cosmetics' /v1/equip). Runs on this class's worker so it never races a
	 * sync, and blocks the CALLER up to 40 s - call it from a background thread,
	 * never from the game thread or this worker. `rejected` = a token the service
	 * just answered 401 to: it is dropped and a fresh sign-in made (null = reuse).
	 */
	public static SignIn signInForService(String rejected) {
		if (DEMO) {
			return new SignIn(null, SignInFailure.NO_ACCOUNT, null);
		}
		String api = CosmeticsService.api();
		if (api == null) {
			return new SignIn(null, SignInFailure.MISSING, null);
		}
		Future<SignIn> f = WORKER.submit(() -> {
			try {
				if (rejected != null && rejected.equals(token)) {
					token = null;
				}
				if (token == null || System.currentTimeMillis() > tokenUntil) {
					signIn(api);
				}
				return new SignIn(token, null, null);
			} catch (SignedOut e) {
				SignInFailure why = e.noOnlinePlay ? SignInFailure.NO_ONLINE_PLAY
						: e.sentence != null ? SignInFailure.REFUSED : SignInFailure.NO_ACCOUNT;
				return new SignIn(null, why, e.sentence);
			} catch (Missing e) {
				return new SignIn(null, SignInFailure.MISSING, null);
			} catch (Throwable e) {
				return new SignIn(null, SignInFailure.UNREACHABLE, null);
			}
		});
		try {
			return f.get(40, TimeUnit.SECONDS);
		} catch (Exception e) {
			return new SignIn(null, SignInFailure.UNREACHABLE, null);
		}
	}

	private static Reply post(String url, JsonObject body, String bearer) throws Exception {
		return reply(HTTP.send(request(url, body, bearer, 10_000), HttpResponse.BodyHandlers.ofString()));
	}

	private static HttpRequest request(String url, JsonObject body, String bearer, long timeoutMs) {
		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMillis(timeoutMs))
				.header("User-Agent", "OriginClient-social")
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (bearer != null) {
			rb.header("Authorization", "Bearer " + bearer);
		}
		return rb.build();
	}

	private static Reply reply(HttpResponse<String> r) {
		JsonObject o = null;
		try {
			JsonElement e = JsonParser.parseString(r.body());
			if (e.isJsonObject()) {
				o = e.getAsJsonObject();
			}
		} catch (Exception ignored) {
			// a non-JSON body (a 404 page from an older service) is just "no body"
		}
		return new Reply(r.statusCode(), o != null ? o : new JsonObject());
	}

	// ------------------------------------------------------------- json helpers

	private static JsonObject uuidBody(String uuid) {
		JsonObject b = new JsonObject();
		b.addProperty("uuid", uuid);
		return b;
	}

	private static String clip(String text) {
		String t = text == null ? "" : text.strip();
		return t.length() > 256 ? t.substring(0, 256) : t;
	}

	private static double round1(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	static String uid(String s) {
		return s == null ? "" : s.replace("-", "").toLowerCase(Locale.ROOT);
	}

	/** Service uuid (undashed) -> java UUID, or null. */
	public static UUID toUuid(String undashed) {
		try {
			if (undashed == null || undashed.length() != 32) {
				return null;
			}
			return UUID.fromString(undashed.substring(0, 8) + "-" + undashed.substring(8, 12) + "-"
					+ undashed.substring(12, 16) + "-" + undashed.substring(16, 20) + "-" + undashed.substring(20));
		} catch (Exception e) {
			return null;
		}
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static JsonObject obj(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray arr(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		try {
			return e == null || e.isJsonNull() ? null : e.getAsString();
		} catch (Exception x) {
			return null;
		}
	}

	private static long lng(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		try {
			return e == null || e.isJsonNull() ? 0 : e.getAsLong();
		} catch (Exception x) {
			return 0;
		}
	}

	private static double dbl(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		try {
			return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
		} catch (Exception x) {
			return 0;
		}
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		try {
			return e != null && !e.isJsonNull() && e.getAsBoolean();
		} catch (Exception x) {
			return false;
		}
	}
}
