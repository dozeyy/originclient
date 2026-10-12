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
import java.util.concurrent.TimeUnit;

/**
 * The game's half of Origin friends / chat / parties (docs/SOCIAL.md is the
 * contract; the service is services/cosmetics). Rules:
 *  - never on the game thread: ONE background thread does every request, in
 *    order, so a sync and an action can never race each other;
 *  - identity is Mojang's own signatures (docs/SOCIAL.md "Identity v2",
 *    social/MojangProof): the player certificate + signed profile, which the
 *    service checks offline (Mojang refuses Cloudflare Workers, so it can't
 *    ask Mojang). The access token only ever goes to Mojang; Origin's bearer
 *    token lives in memory only, and cosmetics' /v1/equip shares it
 *    ({@link #tokenFor});
 *  - the client thread only READS snapshots ({@link #state()}, chats) and
 *    writes plain presence values ({@link #setPresence}); toasts and chat lines
 *    reach it through {@link #EVENTS}, drained in Social.tick;
 *  - adaptive polling inside the contract's request budget (4 s while the
 *    Friends window is open, the party shares your LAN world or a DM/invite
 *    just arrived; 20 s in a party; 30 s idle; 60 s once the game window has
 *    been unfocused 5 minutes);
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
		/** offline / cracked / test account: Mojang won't vouch for it */
		SIGNED_OUT,
		/** Mojang or Origin turned this account down; {@link #refusal()} says why */
		REFUSED,
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
	/** Origin's bearer token; read and written only under AUTH_LOCK (cosmetics' worker shares it). */
	private static final Object AUTH_LOCK = new Object();
	private static String token = null;
	private static long tokenUntil = 0;
	/** What the Friends window says while status is REFUSED. */
	private static volatile String refusal = null;
	private static volatile boolean onlinePlayOff = false;

	/** The certificate refusal sentence (docs/SOCIAL.md: shown as is). */
	static final String NO_ONLINE_PLAY = "Your Microsoft account doesn't allow online play, so friends can't be used.";
	private static JsonElement cursor = new JsonPrimitive(0);
	/** The friends list's version; when the service says it's unchanged it leaves the list out. */
	private static JsonElement friendsEtag = null;
	/** The last sync asked for our position (another online member is on our server). */
	private static boolean wantState = true;
	private static boolean firstSync = true;
	private static long nextSyncAt = 0, lastAttemptAt = 0;
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

	public static boolean stale() {
		return stale;
	}

	/** Why the account was turned down (status REFUSED): a sentence to show as is. */
	public static String refusal() {
		String r = refusal;
		return r != null ? r : NO_ONLINE_PLAY;
	}

	/** REFUSED because Mojang won't issue a player certificate: online play is off on the Microsoft account. */
	public static boolean onlinePlayOff() {
		return onlinePlayOff;
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
			boolean down = status == Status.SIGNED_OUT || status == Status.REFUSED || status == Status.COMING_SOON || status == Status.UNREACHABLE;
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
			try {
				syncNow(api);
				failures = 0;
				stale = false;
				status = Status.READY;
				nextSyncAt = System.currentTimeMillis() + interval(System.currentTimeMillis());
			} catch (Missing e) {
				status = Status.COMING_SOON;
				nextSyncAt = now + MISSING_RETRY_MS;
			} catch (SignedOut e) {
				status = Status.SIGNED_OUT;
				nextSyncAt = now + SIGNED_OUT_RETRY_MS;
			} catch (Refused e) {
				refuse(e);
				nextSyncAt = now + SIGNED_OUT_RETRY_MS;
			} catch (Exception e) {
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
		} catch (Throwable t) {
			// the worker must survive anything a single round throws
			nextSyncAt = System.currentTimeMillis() + 60_000;
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

	private static void syncNow(String api) throws Exception {
		Presence p = presence;
		JsonObject body = new JsonObject();
		body.add("since", cursor);
		if (friendsEtag != null) {
			body.add("friendsEtag", friendsEtag);
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
		Reply r = authed(api, "/v1/sync", body);
		if (r.status() == 404) {
			throw new Missing();
		}
		if (r.status() != 200) {
			throw new IllegalStateException("HTTP " + r.status());
		}
		apply(r.body());
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
	}

	/** Mojang (no certificate) or the service (/v1/auth 400/401/403) turned the account down. */
	private static final class Refused extends Exception {
		final String sentence;
		final boolean onlinePlay;

		Refused(String sentence, boolean onlinePlay) {
			this.sentence = sentence;
			this.onlinePlay = onlinePlay;
		}
	}

	/** Why {@link #tokenFor} has no token for cosmetics. */
	public static final class NoToken extends Exception {
		public enum Why {
			/** offline / test account */
			SIGNED_OUT,
			/** Mojang won't issue a certificate: online play is off on the account */
			NO_ONLINE_PLAY,
			/** the service turned the sign-in down; {@link #sentence} says why */
			REFUSED,
			/** the service has no sign-in yet / isn't switched on */
			UNAVAILABLE,
			/** Mojang or Origin couldn't be reached */
			NETWORK
		}

		public final Why why;
		public final String sentence;

		NoToken(Why why, String sentence) {
			this.why = why;
			this.sentence = sentence;
		}
	}

	private static void refuse(Refused e) {
		refusal = e.sentence;
		onlinePlayOff = e.onlinePlay;
		status = Status.REFUSED;
	}

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
			status = Status.SIGNED_OUT;
			return "Sign in with Microsoft to use friends.";
		} catch (Refused e) {
			refuse(e);
			return e.sentence;
		} catch (Missing e) {
			status = Status.COMING_SOON;
			return "Friends aren't available yet.";
		} catch (Exception e) {
			return "Couldn't reach Origin. Check your connection.";
		}
	}

	/** POST with the bearer token; a 401 signs in again once and retries. */
	private static Reply authed(String api, String path, JsonObject body) throws Exception {
		String t = token(api, null);
		Reply r = post(api + path, body, t);
		if (r.status() == 401) {
			r = post(api + path, body, token(api, t));
		}
		return r;
	}

	/**
	 * A current Origin token, signing in first when there is none, it ran out,
	 * or it is `refused` (the service just answered 401 to it). Locked because
	 * cosmetics' worker shares the sign-in: a second caller holding the same
	 * refused token picks up the new one instead of signing in twice.
	 */
	private static String token(String api, String refused) throws Exception {
		synchronized (AUTH_LOCK) {
			if (token == null || System.currentTimeMillis() > tokenUntil || token.equals(refused)) {
				token = null;
				signIn(api);
			}
			return token;
		}
	}

	/**
	 * Cosmetics' /v1/equip takes the same Bearer token as the social calls
	 * (docs/SOCIAL.md). Call on CosmeticsService's worker, never the game
	 * thread; pass the token /v1/equip just answered 401 to as `refused`
	 * (else null) to sign in afresh.
	 */
	public static String tokenFor(String api, String refused) throws NoToken {
		if (DEMO) {
			throw new NoToken(NoToken.Why.SIGNED_OUT, null);
		}
		try {
			return token(api, refused);
		} catch (SignedOut e) {
			throw new NoToken(NoToken.Why.SIGNED_OUT, null);
		} catch (Refused e) {
			throw new NoToken(e.onlinePlay ? NoToken.Why.NO_ONLINE_PLAY : NoToken.Why.REFUSED, e.sentence);
		} catch (Missing e) {
			throw new NoToken(NoToken.Why.UNAVAILABLE, null);
		} catch (Exception e) {
			throw new NoToken(NoToken.Why.NETWORK, null);
		}
	}

	/**
	 * Identity v2 (MojangProof: certificate + signed profile + our signature)
	 * -> /v1/auth. Caller holds AUTH_LOCK. Throws SignedOut / Refused / Missing,
	 * or IOException / IllegalStateException for "try again later".
	 */
	private static void signIn(String api) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		if (user == null || user.getGameProfile() == null || user.getGameProfile().getId() == null) {
			throw new SignedOut();
		}
		String access = user.getAccessToken();
		// Dev / offline launches carry a placeholder token Mojang can never accept;
		// skip the round trips (and Mojang's rate limits) for those.
		if (access == null || access.length() < 20 || "FabricMC".equals(access)) {
			throw new SignedOut();
		}
		JsonObject b;
		try {
			b = MojangProof.authBody(access, user.getGameProfile().getId().toString(), user.getName(), "game");
		} catch (MojangProof.NoProfile e) {
			throw new SignedOut();
		} catch (MojangProof.NoOnlinePlay e) {
			throw new Refused(NO_ONLINE_PLAY, true);
		}
		Reply r = post(api + "/v1/auth", b, null);
		if (r.status() == 404) {
			throw new Missing();
		}
		if (r.status() == 400 || r.status() == 401 || r.status() == 403) {
			// The service's own sentence ("update Origin", "your clock looks wrong", ...).
			// Fetch a fresh certificate next time in case ours was the problem.
			MojangProof.forget();
			String msg = str(r.body(), "error");
			throw new Refused(msg != null && !msg.trim().isEmpty() ? msg
					: "Couldn't confirm your Minecraft account. Please restart Origin and try again.", false);
		}
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

	private static Reply post(String url, JsonObject body, String bearer) throws Exception {
		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "OriginClient-social")
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (bearer != null) {
			rb.header("Authorization", "Bearer " + bearer);
		}
		HttpResponse<String> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
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
