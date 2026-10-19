package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.social.FairPlay;
import com.origin.client.client.social.Social;
import com.origin.client.client.social.SocialClient;
import com.origin.client.client.social.SocialSkins;
import com.origin.client.client.social.SocialState;
import com.origin.client.client.social.SocialState.Friend;
import com.origin.client.client.social.SocialState.Member;
import com.origin.client.client.social.SocialState.Message;
import com.origin.client.client.social.SocialState.PartyInvite;
import com.origin.client.client.social.SocialState.PartyMessage;
import com.origin.client.client.social.SocialState.Request;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * Friends: the game's half of Origin friends / chat / parties (docs/SOCIAL.md),
 * on the shared OriginWindow frame so it reads as one with the mod menu.
 * Redesigned 2026-10-18 ("simple to get through and read"):
 *
 *   header   Friends + "N online - M friends" ............ APPEAR OFFLINE [o] [x]
 *   left     ONE box that searches AND adds (type a name: the list filters; no match
 *            and a valid name -> "Add <name>" row), then one scrolling list ordered by
 *            what needs you: REQUESTS (friend requests + party invites, labelled
 *            ACCEPT / JOIN / DECLINE), PARTY (one row), ONLINE, OFFLINE, SENT, BLOCKED.
 *            Every row's main job is on the row: JOIN when they're on a server (always
 *            visible), INVITE on hover; a click opens the chat.
 *   right    the picked friend: face, status ("Playing on x" / "Last seen 3 h ago"),
 *            JOIN, INVITE TO PARTY, a "..." menu (Remove / Block, both confirm), then a
 *            chat that reads like one (sender, time, grouped lines, day breaks, input
 *            at the bottom, Enter sends); or the party: member tiles (click = message /
 *            make leader / remove), WARP PARTY HERE, INVITE FRIEND, LEAVE PARTY, party chat.
 *
 * Everything is read from SocialClient's snapshots and every action goes
 * through it (background thread); a result sentence shows as a pill at the foot of
 * the list for a few seconds. While the service isn't usable the body is one calm
 * message. Click targets are recorded while drawing (d.hit), like every OriginWindow.
 * Keys: Ctrl+F focuses the search box, Up/Down walk the list, Esc clears the search
 * then closes. Nothing here changes what the social layer sends or how often.
 *
 * Pre-1.20 port (2026-10-18 redesign, 1.21.1's layout and behaviour as is):
 * faces come from SocialSkins as skin textures drawn through the Gfx shim;
 * render takes a PoseStack; one scroll amount; SharedConstants' chat-character check.
 */
public class FriendsScreen extends OriginWindow {
	/** {@link #select} values besides a friend's uuid. */
	public static final String PARTY = "@party", REQUESTS = "@requests";

	// ---- layout (design px, inside OriginWindow's body rect) ----
	private static final float LIST_W = 332;
	private static final float SPLIT = BODY_X + LIST_W + 12;
	private static final float RX = SPLIT + 14, RW = BODY_X + BODY_W - RX;
	private static final float TOP = BODY_Y, BOTTOM = BODY_Y + BODY_H;
	private static final float SEARCH_H = 36;
	private static final float LIST_TOP = TOP + SEARCH_H + 10;
	private static final float HEAD_ROW = TOP + 54;                 // the action buttons
	private static final float DIVIDER = TOP + 98;
	private static final float INPUT_H = 36;
	private static final float CHAT_BOTTOM = BOTTOM - INPUT_H - 10;
	private static final float ROW_H = 46, LABEL_H = 30, GAP = 8;
	private static final float LINE_H = 19, HEAD_EXTRA = 28, MSG_GAP = 4;
	private static final int MAX_CHAT = 256;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private enum Focus { NONE, SEARCH, CHAT }

	private enum K { LABEL, ADD, REQ_IN, REQ_OUT, INVITE, START_PARTY, PARTY, FRIEND, BLOCKED, BLOCKED_TOGGLE, NOTE }

	/** One row of the left list; `data` is a Friend / Request / PartyInvite / String depending on `kind`. */
	private record Item(K kind, float h, Object data, String text, int count) {
	}

	private String sel;                       // friend uuid, PARTY, or null
	private Focus focus = Focus.SEARCH;       // opens ready to search / add
	private Focus lastField = Focus.SEARCH;   // the field you last had the cursor in (typing with no focus goes there)
	private String search = "";
	/** One unsent message per conversation (friend uuid or PARTY): switching never sends the wrong text. */
	private final java.util.Map<String, String> drafts = new java.util.HashMap<>();
	private String notice = null;
	private boolean noticeRed = false;
	private long noticeAt = 0;
	private String armed = null;              // "<menu>:<label>" waiting for a second click
	private long armedAt = 0;
	private boolean showBlocked = false;

	private double listScroll, listTarget, chatScroll, chatTarget, memScroll, memTarget;
	private float memMax, gridTop = 0, gridBot = 0;
	private float listMax, chatMax;
	private String scrollToKey = null;        // keyboard moves: bring this row into view

	// what the last frame's list contained (keyboard navigation + Enter in the search box)
	private final List<String> navKeys = new ArrayList<>();
	private final List<String> matchKeys = new ArrayList<>();
	private boolean canAddNow = false;

	// the one pop-up menu (friend "...", party member, invite picker)
	private record MenuItem(String label, String confirmLabel, boolean danger, boolean confirm, boolean enabled, Runnable action) {
	}

	private List<MenuItem> menu = null;
	private String menuId = null;
	private float menuX, menuY, menuRight = Float.NaN;   // menuRight set = right-aligned to that edge

	// tooltip for the few icon-only controls
	private String tipText = null, tipKey = null;
	private long tipSince = 0;
	private float tipX, tipY;

	// chat layout cache (a layout pass wraps every message; only redo it when something changed)
	private record ChatRow(boolean day, String uuid, String name, String text, String time, boolean mine,
						   boolean pending, boolean head, float h) {
	}

	private record Msg(String from, String name, String text, long at, boolean pending) {
	}

	private List<ChatRow> chatRows = List.of();
	private float chatTotal = 0;

	public FriendsScreen(Screen returnTo) {
		super(new TextComponent("Friends"), returnTo);
	}

	/** Opens on a friend (uuid), the party ({@link #PARTY}) or the requests ({@link #REQUESTS}). */
	public FriendsScreen select(String what) {
		if (REQUESTS.equals(what)) {
			sel = null;
			listTarget = 0;
			setFocus(Focus.SEARCH);
		} else if (what != null) {
			sel = what;
			chatTarget = chatScroll = 0;
			setFocus(Focus.CHAT);   // you came to answer: the cursor is in the message box
			menu = null;
			scrollToKey = what;
		}
		return this;
	}

	/** DEV SHOTS ONLY: type into the search box before the first frame. */
	public FriendsScreen devSearch(String text) {
		search = text;
		setFocus(Focus.SEARCH);
		return this;
	}

	/** DEV SHOTS ONLY: open the pane's menu on the first frame (friend: Remove/Block; party: the invite picker). */
	public FriendsScreen devMenu() {
		devMenu = true;
		return this;
	}

	/** DEV SHOTS ONLY: show a result pill on the first frame. */
	public FriendsScreen devNotice(String text, boolean red) {
		say(text, red);
		return this;
	}

	private boolean devMenu = false;
	private float devX = Float.NaN, devY;

	/** DEV SHOTS ONLY: pretend the pointer is at this design-px spot (hover states), from the next frame on. */
	public void devHover(float x, float y) {
		devX = x;
		devY = y;
	}

	/** DEV SHOTS ONLY: click a design-px spot through the last frame's recorded targets (no OS input). */
	public void devClick(float x, float y) {
		devHover(x, y);
		focus = Focus.NONE;
		OriginDeck.click(hits, x, y);
	}

	/** DEV SHOTS ONLY: type text as if from the keyboard. */
	public void devChars(String text) {
		for (char c : text.toCharArray()) {
			charTyped(c, 0);
		}
	}

	/** DEV SHOTS ONLY: press a key (a GLFW key code). */
	public void devKey(int keyCode) {
		keyPressed(keyCode, 0, 0);
	}

	@Override
	public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		if (!Float.isNaN(devX)) {
			double gs = Minecraft.getInstance().getWindow().getGuiScale();
			mouseX = (int) ((devX * lastS + lastOx) / gs);
			mouseY = (int) ((devY * lastS + lastOy) / gs);
		}
		super.render(poseStack, mouseX, mouseY, partialTick);
	}

	/** Is the chat with `uuid` on screen right now? (Then a DM needs no toast.) */
	public static boolean isShowingChat(Minecraft mc, String uuid) {
		return mc.screen instanceof FriendsScreen fs && uuid != null && uuid.equals(fs.sel);
	}

	@Override
	protected void init() {
		SocialClient.setWindow(true, friendSelected() ? sel : null);
	}

	@Override
	public void removed() {
		SocialClient.setWindow(false, null);
	}

	// ---- header ----

	@Override
	protected String heading() {
		return "Friends";
	}

	@Override
	protected String subheading() {
		if (SocialClient.status() != SocialClient.Status.READY) {
			return "Chat, party up and join each other on any server.";
		}
		if (SocialClient.stale()) {
			return "Reconnecting...";
		}
		SocialState s = SocialClient.state();
		long online = s.friends().stream().filter(Friend::online).count();
		int n = s.friends().size();
		return n == 0 ? "Chat, party up and join each other on any server."
				: online + " online • " + n + (n == 1 ? " friend" : " friends");
	}

	@Override
	protected String headingIcon() {
		return "@user";
	}

	@Override
	protected void drawHeaderActions(OriginDeck d, float right) {
		if (SocialClient.status() != SocialClient.Status.READY) {
			return;
		}
		boolean on = SocialClient.appearOffline();
		float x = right - 36;
		d.toggle("friends-offline", x, 22, on);
		String lab = "APPEAR OFFLINE";
		float lw = d.textW(lab, 11, true, CAPS_TRACK);
		boolean hov = d.over(x - 10 - lw, 16, lw + 10 + 36, 32);
		d.textRight(lab, x - 10, 32, 11, true, CAPS_TRACK, on || hov ? TEXT : DIM);
		d.hit(x - 10 - lw, 16, lw + 10 + 36, 32, () -> run(SocialClient.setAppearOffline(!on),
				!on ? "You appear offline to your friends." : "Your friends can see you again."));
	}

	private void say(String text, boolean red) {
		notice = text;
		noticeRed = red;
		noticeAt = System.currentTimeMillis();
	}

	/** Runs an action; its error (or `ok`) shows as the pill at the foot of the list. */
	private void run(CompletableFuture<String> f, String ok) {
		f.thenAccept(err -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				say(err, true);
			} else if (ok != null) {
				say(ok, false);
			}
		}));
	}

	// ---- body ----

	@Override
	protected void drawBody(OriginDeck d) {
		maxScroll = 0;
		tipText = null;
		try {
			if (SocialClient.status() != SocialClient.Status.READY) {
				drawBlocked(d);
				return;
			}
			SocialState s = SocialClient.state();
			resolveSelection(s);
			SocialClient.setWindow(true, friendSelected() ? sel : null);
			drawList(d, s);
			d.fill(SPLIT, BODY_Y, 1, BODY_H, LINE);
			if (PARTY.equals(sel)) {
				drawParty(d, s);
			} else if (sel != null && s.friend(sel) != null) {
				drawFriend(d, s, s.friend(sel));
			} else {
				drawWelcome(d, s);
			}
			drawMenu(d);
			drawNotice(d);
			drawTooltip(d);
		} catch (Throwable t) {
			// a draw failure leaves the bare frame; Esc / close still work
			if (!drawWarned) {
				drawWarned = true;
				com.origin.client.OriginClient.LOGGER.warn("Origin: Friends window draw failed", t);
			}
		}
	}

	private static boolean drawWarned = false;

	private void setFocus(Focus f) {
		focus = f;
		if (f != Focus.NONE) {
			lastField = f;
		}
	}

	private String chatText() {
		return sel == null ? "" : drafts.getOrDefault(sel, "");
	}

	private void setChatText(String t) {
		if (sel != null) {
			if (t.isEmpty()) {
				drafts.remove(sel);
			} else {
				drafts.put(sel, t);
			}
		}
	}

	private boolean friendSelected() {
		return sel != null && !PARTY.equals(sel);
	}

	private void resolveSelection(SocialState s) {
		if (sel != null && !PARTY.equals(sel) && s.friend(sel) == null) {
			sel = null;
		}
	}

	// ---- the service isn't usable: one calm message ----

	private void drawBlocked(OriginDeck d) {
		String title, sub;
		boolean retry = false;
		switch (SocialClient.status()) {
			case SIGNED_OUT -> {
				title = "Sign in with Microsoft to use friends";
				sub = "Friends, chat and parties need a Microsoft account. Switch accounts in the Origin launcher.";
			}
			case REFUSED -> {
				// Mojang won't give this account a player certificate, or Origin's
				// sign-in check turned it down (its own sentence, shown as is)
				boolean off = SocialClient.onlinePlayOff();
				title = off ? "Online play is off for this account" : "Origin couldn't confirm your account";
				sub = off ? SocialClient.refusal() + " Allow multiplayer in the account's Xbox privacy or family settings, then restart Minecraft." : SocialClient.refusal();
			}
			case COMING_SOON -> {
				title = "Friends are coming soon";
				sub = "Add friends, chat and party up on any server. Hang tight.";
			}
			case UNREACHABLE -> {
				title = "Couldn't reach Origin";
				sub = "Check your connection. Origin keeps trying on its own.";
				retry = true;
			}
			default -> {
				title = "Connecting to Origin...";
				sub = "";
			}
		}
		emptyState(d, BODY_X, BODY_W, title, sub, "@user");
		if (retry) {
			float bw = buttonW(d, "Try again", null);
			button(d, "Try again", null, BODY_X + (BODY_W - bw) / 2, TOP + BODY_H / 2 + 62, bw, 32, Style.SECONDARY, true,
					SocialClient::syncSoon);
		}
	}

	private void emptyState(OriginDeck d, float x, float w, String title, String sub, String icon) {
		float cx = x + w / 2, cy = TOP + BODY_H / 2 - 30;
		d.circle(cx, cy - 34, 28, 0x14FFFFFF);
		d.icon(icon, cx - 14, cy - 48, 28, DIM);
		d.textCenter(title, cx, cy + 14, 18, true, 0, TEXT);
		float y = cy + 42;
		for (String line : d.wrap(sub, Math.min(w - 48, 460), 13.5f, false)) {
			d.textCenter(line, cx, y, 13.5f, false, 0, DIM);
			y += 21;
		}
	}

	// ---- colours (derived from the themed TEXT so an equipped theme still repaints them) ----

	private static int soft() {
		return withAlpha(TEXT, 0x8C);       // ~5:1 on the panel: readable, still a step under DIM
	}

	private static int labelCol() {
		return withAlpha(TEXT, 0x99);
	}

	// ---- left: search / add box + the list ----

	private void drawList(OriginDeck d, SocialState s) {
		searchField(d);

		List<Item> items = items(d, s);
		float contentH = 12;
		for (Item it : items) {
			contentH += it.h();
		}
		// while the result pill shows, the list's viewport ends above it (rows are clipped there, never hidden under it)
		List<String> nl = noticeLines(d);
		float view = BOTTOM - LIST_TOP - (nl != null ? 16 + nl.size() * 18 + 8 + 4 : 0);
		listMax = Math.max(0, contentH - view);
		if (scrollToKey != null) {
			float yy = 0;
			for (Item it : items) {
				if (keyOf(it) != null && keyOf(it).equals(scrollToKey)) {
					if (yy < listTarget) {
						listTarget = yy - 6;
					} else if (yy + it.h() > listTarget + view) {
						listTarget = yy + it.h() - view + 6;
					}
					break;
				}
				yy += it.h();
			}
			scrollToKey = null;
		}
		listTarget = Math.max(0, Math.min(listMax, listTarget));
		listScroll += (listTarget - listScroll) * 0.3;
		listScroll = Math.max(0, Math.min(listMax, listScroll));

		d.clip(BODY_X, LIST_TOP, LIST_W, view);
		float y = LIST_TOP - (float) listScroll;
		for (Item it : items) {
			if (y + it.h() >= LIST_TOP && y <= BOTTOM) {
				drawItem(d, s, it, y);
			}
			y += it.h();
		}
		d.unclip();
		if (listScroll < listMax - 0.5 && !d.seeThrough) {
			// more below: a short fade instead of a thin slice of the next row at the viewport edge
			d.gradient(BODY_X, LIST_TOP + view - 16, LIST_W, 16, withAlpha(PANEL, 0), PANEL);
		}
		if (listMax > 0) {
			float thumb = Math.max(28, view * view / (view + listMax));
			d.rect(SPLIT - 6, LIST_TOP + (view - thumb) * (float) (listScroll / listMax), 3, thumb, 1.5f, 0x33FFFFFF);
		}
	}

	private static String keyOf(Item it) {
		return switch (it.kind()) {
			case PARTY -> PARTY;
			case FRIEND -> ((Friend) it.data()).uuid();
			default -> null;
		};
	}

	// the row list only changes with the snapshot, the search text, the blocked fold or the scale
	private List<Item> cachedItems = null;
	private SocialState cachedState = null;
	private String cachedSearch = null;
	private boolean cachedBlocked = false;
	private float cachedScale = 0;

	private List<Item> items(OriginDeck d, SocialState s) {
		if (cachedItems != null && cachedState == s && search.equals(cachedSearch) && showBlocked == cachedBlocked
				&& d.s == cachedScale) {
			return cachedItems;
		}
		cachedItems = build(d, s);
		cachedState = s;
		cachedSearch = search;
		cachedBlocked = showBlocked;
		cachedScale = d.s;
		return cachedItems;
	}

	/** Builds the rows; also records the keyboard order and what Enter would do. */
	private List<Item> build(OriginDeck d, SocialState s) {
		List<Item> out = new ArrayList<>();
		navKeys.clear();
		matchKeys.clear();
		String raw = search.trim();
		String q = raw.toLowerCase(Locale.ROOT);
		boolean filtering = !q.isEmpty();
		boolean anyMatch = false;
		if (filtering) {
			for (Friend f : s.friends()) {
				anyMatch |= nz(f.name()).toLowerCase(Locale.ROOT).contains(q);
			}
			for (Request r : s.incoming()) {
				anyMatch |= nz(r.name()).toLowerCase(Locale.ROOT).contains(q);
			}
			for (Request r : s.outgoing()) {
				anyMatch |= nz(r.name()).toLowerCase(Locale.ROOT).contains(q);
			}
			for (PartyInvite i : s.invites()) {
				anyMatch |= nz(i.name()).toLowerCase(Locale.ROOT).contains(q);
			}
		}
		// 1-2 character names can be real, but offering "Add" while a friend matches is just noise
		canAddNow = filtering && NAME.matcher(raw).matches() && !knownName(s, raw) && (raw.length() >= 3 || !anyMatch);
		if (canAddNow) {
			out.add(new Item(K.ADD, ROW_H, raw, null, 0));
		}

		List<Request> incoming = new ArrayList<>();
		for (Request r : s.incoming()) {
			if (!filtering || nz(r.name()).toLowerCase(Locale.ROOT).contains(q)) {
				incoming.add(r);
			}
		}
		List<PartyInvite> invites = new ArrayList<>();
		for (PartyInvite i : s.invites()) {
			if (!filtering || nz(i.name()).toLowerCase(Locale.ROOT).contains(q)) {
				invites.add(i);
			}
		}
		if (!incoming.isEmpty() || !invites.isEmpty()) {
			out.add(new Item(K.LABEL, LABEL_H, null, "Requests", incoming.size() + invites.size()));
			for (PartyInvite i : invites) {
				out.add(new Item(K.INVITE, ROW_H, i, null, 0));
			}
			for (Request r : incoming) {
				out.add(new Item(K.REQ_IN, ROW_H, r, null, 0));
			}
		}

		if (!filtering && (s.inParty() || !s.friends().isEmpty())) {
			out.add(new Item(K.LABEL, LABEL_H, null, "Party", -1));
			if (s.inParty()) {
				out.add(new Item(K.PARTY, ROW_H, null, null, 0));
				navKeys.add(PARTY);
			} else {
				out.add(new Item(K.START_PARTY, ROW_H, null, null, 0));
			}
		}

		List<Friend> online = new ArrayList<>(), offline = new ArrayList<>();
		for (Friend f : s.friends()) {
			if (!filtering || nz(f.name()).toLowerCase(Locale.ROOT).contains(q)) {
				(f.online() ? online : offline).add(f);
			}
		}
		if (!online.isEmpty()) {
			out.add(new Item(K.LABEL, LABEL_H, null, "Online", online.size()));
			for (Friend f : online) {
				out.add(new Item(K.FRIEND, ROW_H, f, null, 0));
				navKeys.add(f.uuid());
				matchKeys.add(f.uuid());
			}
		}
		if (!offline.isEmpty()) {
			out.add(new Item(K.LABEL, LABEL_H, null, "Offline", offline.size()));
			for (Friend f : offline) {
				out.add(new Item(K.FRIEND, ROW_H, f, null, 0));
				navKeys.add(f.uuid());
				matchKeys.add(f.uuid());
			}
		}
		if (s.friends().isEmpty() && !filtering) {
			addNote(d, out, "No friends yet. Type a Minecraft name above and press Enter to send a request.");
		} else if (filtering && online.isEmpty() && offline.isEmpty() && incoming.isEmpty() && invites.isEmpty() && !canAddNow) {
			addNote(d, out, "No friends match \"" + raw + "\".");
		}

		if (!filtering) {
			if (!s.outgoing().isEmpty()) {
				out.add(new Item(K.LABEL, LABEL_H, null, "Sent", s.outgoing().size()));
				for (Request r : s.outgoing()) {
					out.add(new Item(K.REQ_OUT, ROW_H, r, null, 0));
				}
			}
			if (!s.blocked().isEmpty()) {
				out.add(new Item(K.BLOCKED_TOGGLE, 38, null, null, s.blocked().size()));
				if (showBlocked) {
					for (Request b : s.blocked()) {
						out.add(new Item(K.BLOCKED, ROW_H, b, null, 0));
					}
				}
			}
		}
		return out;
	}

	private void addNote(OriginDeck d, List<Item> out, String text) {
		List<String> lines = d.wrap(text, LIST_W - 28, 13, false);
		out.add(new Item(K.NOTE, lines.size() * 19 + 12, lines, text, 0));
	}

	private static boolean knownName(SocialState s, String name) {
		if (s.me() != null && name.equalsIgnoreCase(nz(s.me().name()))) {
			return true;
		}
		for (Friend f : s.friends()) {
			if (name.equalsIgnoreCase(nz(f.name()))) {
				return true;
			}
		}
		for (Request r : s.outgoing()) {
			if (name.equalsIgnoreCase(nz(r.name()))) {
				return true;
			}
		}
		for (Request r : s.incoming()) {
			if (name.equalsIgnoreCase(nz(r.name()))) {
				return true;
			}
		}
		return false;
	}

	private void searchField(OriginDeck d) {
		float w = LIST_W;
		boolean focused = focus == Focus.SEARCH;
		String hint = "Search friends or add by name";
		float clearW = search.isEmpty() ? 0 : 30;
		inputField(d, BODY_X, TOP, w - clearW, SEARCH_H, search, hint, focused, "@find", null, false);
		d.hit(BODY_X, TOP, w - clearW, SEARCH_H, () -> {
			setFocus(Focus.SEARCH);
			menu = null;
		});
		if (clearW > 0) {
			float cx = BODY_X + w - 28, cy = TOP + 4;
			boolean hov = !menuOpen() && d.over(cx, cy, 28, 28);
			d.rect(cx, cy, 28, 28, 6, hov ? 0x1AFFFFFF : 0x0FFFFFFF);
			d.icon("@close", cx + 7, cy + 7, 14, hov ? TEXT : DIM);
			tip(d, "friends-clear", "Clear (Esc)", cx, cy, 28, 28);
			d.hit(cx, cy, 28, 28, () -> {
				search = "";
				setFocus(Focus.SEARCH);
			});
		}
	}

	private void drawItem(OriginDeck d, SocialState s, Item it, float y) {
		switch (it.kind()) {
			case LABEL -> {
				String t = it.text().toUpperCase(Locale.ROOT) + (it.count() >= 0 ? " • " + it.count() : "");
				d.text(t, BODY_X + 4, y + LABEL_H - 9, 11.5f, true, 1.8f, labelCol());
			}
			case ADD -> addRow(d, (String) it.data(), y);
			case REQ_IN -> requestRow(d, (Request) it.data(), y);
			case REQ_OUT -> sentRow(d, (Request) it.data(), y);
			case INVITE -> inviteRow(d, (PartyInvite) it.data(), y);
			case START_PARTY -> startPartyRow(d, y);
			case PARTY -> partyRow(d, s, y);
			case FRIEND -> friendRow(d, s, (Friend) it.data(), y);
			case BLOCKED -> blockedRow(d, (Request) it.data(), y);
			case BLOCKED_TOGGLE -> blockedToggle(d, it.count(), y);
			case NOTE -> {
				float ny = y + 6;
				@SuppressWarnings("unchecked")
				List<String> noteLines = (List<String>) it.data();
				for (String line : noteLines) {
					d.text(line, BODY_X + 14, ny + 14, 13, false, 0, soft());
					ny += 19;
				}
			}
		}
	}

	private boolean menuOpen() {
		return menu != null;
	}

	/** Row background + selected indicator; returns the eased hover amount. */
	private float rowBack(OriginDeck d, String key, float y, boolean selected, float h) {
		boolean hov = !menuOpen() && d.over(BODY_X, y, LIST_W, h);
		float hp = OriginUi.hover("friends-row:" + key, hov);
		int fill = selected ? ACC_SOFT : lerp(0x00000000, CARD, hp);
		if ((fill >>> 24) != 0) {
			d.rect(BODY_X, y, LIST_W, h, 6, fill);
		}
		if (selected) {
			d.rect(BODY_X, y + 9, 3, h - 18, 1.5f, ACC);
		}
		return hp;
	}

	private void addRow(OriginDeck d, String name, float y) {
		float hp = rowBack(d, "add", y, false, ROW_H);
		d.rect(BODY_X, y, LIST_W, ROW_H, 6, 0x00000000, lerp(withAlpha(ACC, 0x55), withAlpha(ACC, 0xAA), hp));
		d.circle(BODY_X + 25, y + ROW_H / 2, 15, ACC_SOFT);
		d.icon("@plus", BODY_X + 18, y + ROW_H / 2 - 7, 14, ACC);
		boolean enterAdds = matchKeys.isEmpty();
		float chip = enterAdds ? d.textW("ENTER", 10, true, 1.4f) + 16 : 0;
		d.text(d.fit("Add " + name, LIST_W - 56 - chip - 8, 14, true, 0), BODY_X + 50, y + 20, 14, true, 0, TEXT);
		d.text(d.fit("Send a friend request", LIST_W - 56 - chip - 8, 12.5f, false, 0), BODY_X + 50, y + 37, 12.5f, false, 0, DIM);
		if (enterAdds) {
			float cx = BODY_X + LIST_W - 10 - chip;
			d.rect(cx, y + ROW_H / 2 - 10, chip, 20, 5, 0x14FFFFFF);
			d.textCenter("ENTER", cx + chip / 2, y + ROW_H / 2, 10, true, 1.4f, DIM);
		}
		d.hit(BODY_X, y, LIST_W, ROW_H, this::submitAdd);
	}

	private void requestRow(OriginDeck d, Request r, float y) {
		rowBack(d, "req:" + r.uuid(), y, false, ROW_H);
		face(d, r.uuid(), BODY_X + 10, y + 8, 30, false);
		float right = BODY_X + LIST_W - 10;
		float dw = miniW(d, "Decline"), aw = miniW(d, "Accept");
		miniButton(d, "Decline", right - dw, y + 11, dw, Mini.GHOST,
				() -> run(SocialClient.respond(r.uuid(), false), null));
		miniButton(d, "Accept", right - dw - 6 - aw, y + 11, aw, Mini.PRIMARY,
				() -> run(SocialClient.respond(r.uuid(), true), "You and " + r.name() + " are friends."));
		float max = LIST_W - 50 - 12 - dw - aw - 12;
		d.text(d.fit(nz(r.name()), max, 14, true, 0), BODY_X + 50, y + 20, 14, true, 0, TEXT);
		d.text(d.fit("Wants to be friends", max, 12.5f, false, 0), BODY_X + 50, y + 37, 12.5f, false, 0, DIM);
	}

	private void inviteRow(OriginDeck d, PartyInvite inv, float y) {
		rowBack(d, "inv:" + inv.partyId(), y, false, ROW_H);
		face(d, inv.from(), BODY_X + 10, y + 8, 30, false);
		float right = BODY_X + LIST_W - 10;
		float dw = miniW(d, "Decline"), jw = miniW(d, "Join");
		miniButton(d, "Decline", right - dw, y + 11, dw, Mini.GHOST,
				() -> run(SocialClient.declineInvite(inv.partyId()), null));
		miniButton(d, "Join", right - dw - 6 - jw, y + 11, jw, Mini.PRIMARY,
				() -> run(SocialClient.acceptInvite(inv.partyId()), "You joined " + inv.name() + "'s party."));
		float max = LIST_W - 50 - 12 - dw - jw - 12;
		d.text(d.fit(nz(inv.name()) + "'s party", max, 14, true, 0), BODY_X + 50, y + 20, 14, true, 0, TEXT);
		d.text(d.fit("Invited you to a party", max, 12.5f, false, 0), BODY_X + 50, y + 37, 12.5f, false, 0, DIM);
	}

	private void sentRow(OriginDeck d, Request r, float y) {
		rowBack(d, "sent:" + r.uuid(), y, false, ROW_H);
		face(d, r.uuid(), BODY_X + 10, y + 8, 30, false);
		float right = BODY_X + LIST_W - 10;
		float cw = miniW(d, "Cancel");
		miniButton(d, "Cancel", right - cw, y + 11, cw, Mini.GHOST,
				() -> run(SocialClient.removeFriend(r.uuid()), "Request cancelled."));
		float max = LIST_W - 50 - 12 - cw;
		d.text(d.fit(nz(r.name()), max, 14, true, 0), BODY_X + 50, y + 20, 14, true, 0, DIM);
		d.text(d.fit("Request sent", max, 12.5f, false, 0), BODY_X + 50, y + 37, 12.5f, false, 0, soft());
	}

	private void blockedRow(OriginDeck d, Request b, float y) {
		rowBack(d, "blk:" + b.uuid(), y, false, ROW_H);
		face(d, b.uuid(), BODY_X + 10, y + 8, 30, false);
		float right = BODY_X + LIST_W - 10;
		float uw = miniW(d, "Unblock");
		miniButton(d, "Unblock", right - uw, y + 11, uw, Mini.SECONDARY,
				() -> run(SocialClient.unblock(b.uuid()), (b.name() == null ? "They're" : b.name() + " is") + " unblocked."));
		float max = LIST_W - 50 - 12 - uw;
		d.text(d.fit(nz(b.name()), max, 14, true, 0), BODY_X + 50, y + 20, 14, true, 0, DIM);
		d.text("Blocked", BODY_X + 50, y + 37, 12.5f, false, 0, soft());
	}

	private void blockedToggle(OriginDeck d, int n, float y) {
		boolean hov = !menuOpen() && d.over(BODY_X, y, LIST_W, 38);
		float hp = OriginUi.hover("friends-blocked", hov);
		d.icon(showBlocked ? "@chev-down" : "@chev-right", BODY_X + 6, y + 12, 14, lerp(soft(), TEXT, hp));
		d.text("BLOCKED • " + n, BODY_X + 28, y + 24, 11.5f, true, 1.8f, lerp(labelCol(), TEXT, hp));
		d.hit(BODY_X, y, LIST_W, 38, () -> showBlocked = !showBlocked);
	}

	private void partyRow(OriginDeck d, SocialState s, float y) {
		boolean selected = PARTY.equals(sel);
		float hp = rowBack(d, "party", y, selected, ROW_H);
		d.circle(BODY_X + 25, y + ROW_H / 2, 15, selected ? withAlpha(ACC, 0x29) : 0x14FFFFFF);
		d.icon("@title-multiplayer", BODY_X + 17, y + ROW_H / 2 - 8, 16, selected ? ACC : DIM);
		int n = s.party().members().size();
		long on = s.party().members().stream().filter(Member::online).count();
		d.text("Your party", BODY_X + 50, y + 20, 14, true, 0, selected ? ACC : TEXT);
		String sub = n + (n == 1 ? " member" : " members") + " • " + on + " online" + (s.isLeader() ? " • you lead" : "");
		d.text(d.fit(sub, LIST_W - 62, 12.5f, false, 0), BODY_X + 50, y + 37, 12.5f, false, 0, DIM);
		d.hit(BODY_X, y, LIST_W, ROW_H, () -> pick(PARTY));
	}

	private void startPartyRow(OriginDeck d, float y) {
		float hp = rowBack(d, "start", y, false, ROW_H);
		d.rect(BODY_X, y, LIST_W, ROW_H, 6, 0x00000000, lerp(LINE2, 0x29F1E9D2, hp));
		d.circle(BODY_X + 25, y + ROW_H / 2, 15, 0x14FFFFFF);
		d.icon("@plus", BODY_X + 18, y + ROW_H / 2 - 7, 14, lerp(DIM, ACC, hp));
		d.text("Start a party", BODY_X + 50, y + 20, 14, true, 0, TEXT);
		d.text("Chat and warp together on any server", BODY_X + 50, y + 37, 12.5f, false, 0, DIM);
		d.hit(BODY_X, y, LIST_W, ROW_H, () -> {
			sel = PARTY;
			run(SocialClient.createParty(), "Party started. Hover a friend and press Invite.");
		});
	}

	/** The Origin+ badge (the Origin mark) centred on (cx, cy), `size` design px. Never throws. */
	private static void plusMark(OriginDeck d, float cx, float cy, float size) {
		try {
			d.logo(cx, cy, size, 0.10f);
		} catch (Throwable ignored) {
			// no badge
		}
	}

	private void friendRow(OriginDeck d, SocialState s, Friend f, float y) {
		boolean selected = f.uuid().equals(sel);
		boolean hov = !menuOpen() && d.over(BODY_X, y, LIST_W, ROW_H);
		float hp = rowBack(d, f.uuid(), y, selected, ROW_H);
		face(d, f.uuid(), BODY_X + 10, y + 8, 30, f.online());

		int unread = SocialClient.unread(f.uuid());
		boolean plus = CosmeticsService.isPlus(f.uuid());
		boolean inParty = s.party() != null && s.party().member(f.uuid()) != null;
		boolean joinable = isJoinable(f) && !selected;   // the selected row's header already has JOIN / INVITE

		// the right-hand gutter is fixed per row (never changes under the pointer)
		float right = BODY_X + LIST_W - 10;
		float bx = right;
		if (unread > 0) {
			String c = unread > 9 ? "9+" : Integer.toString(unread);
			d.circle(bx - 10, y + ROW_H / 2, 10, ACC);
			d.textCenter(c, bx - 10, y + ROW_H / 2, 10.5f, true, 0, ON_ACC);
			bx -= 26;
		}
		if (joinable) {
			float jw = miniW(d, "Join");
			miniButton(d, "Join", bx - jw, y + 11, jw, Mini.SOFT, () -> joinFriend(f));
			bx -= jw + 6;
		}
		float textX = BODY_X + 50;
		float textMax = Math.max(60, bx - textX - 4);
		float inviteW = miniW(d, "Invite");
		if (hov && !selected && !inParty) {
			textMax = Math.max(60, bx - inviteW - 8 - 12 - textX);   // stop at the hover toolbar
		}

		String chip = inParty ? "PARTY" : null;
		float chipW = chip == null ? 0 : d.textW(chip, 9, true, 1.2f) + 12;
		float nameMax = textMax - (plus ? 18 : 0) - (chip != null ? chipW + 6 : 0);
		int nameCol = selected ? ACC : f.online() ? TEXT : lerp(DIM, TEXT, hp);
		String shownName = d.fit(nz(f.name()), nameMax, 14, true, 0);
		float nameW = d.text(shownName, textX, y + 20, 14, true, 0, nameCol);
		float cx = textX + nameW + 6;
		if (plus) {
			plusMark(d, cx + 6, y + 15, 12);
			cx += 18;
		}
		if (chip != null) {
			d.rect(cx, y + 9, chipW, 15, 4, ACC_SOFT);
			d.textMid(chip, cx + 6, y + 16.5f, 9, true, 1.2f, ACC);
		}
		d.text(d.fit(rowStatus(f), textMax, 12.5f, false, 0), textX, y + 37, 12.5f, false, 0, f.online() ? DIM : soft());

		d.hit(BODY_X, y, LIST_W, ROW_H, () -> pick(f.uuid()));
		if (hov && !selected && !inParty) {
			// hover toolbar: the second-most-common job, one click (it covers the text under it)
			float iw = miniW(d, "Invite");
			float px = bx - iw - 8;
			d.rect(px - 8, y + 6, bx - px + 6, ROW_H - 12, 5, selected ? lerp(CARD, ACC_SOFT, 0.5f) : CARD_H);
			miniButton(d, "Invite", px, y + 11, iw, Mini.SECONDARY,
					() -> run(SocialClient.inviteToParty(f.uuid()), "Party invite sent to " + f.name() + "."));
		}
	}

	private static boolean isJoinable(Friend f) {
		return f.online() && "server".equals(f.activity()) && f.server() != null;
	}

	private void joinFriend(Friend f) {
		String why = Social.whyCantJoin(f.server(), f.version());
		if (why != null) {
			say(why, true);
		} else {
			beginClose();
			Social.join(f.server());
		}
	}

	/** Click a row: open that conversation with the cursor in the message box. */
	private void pick(String key) {
		sel = key;
		chatTarget = chatScroll = 0;
		menu = null;
		setFocus(PARTY.equals(key) && !SocialClient.state().inParty() ? Focus.NONE : Focus.CHAT);
	}

	// ---- right: one friend ----

	private void drawFriend(OriginDeck d, SocialState s, Friend f) {
		face(d, f.uuid(), RX, TOP + 2, 44, f.online());
		float tx = RX + 44 + 14;
		boolean plus = CosmeticsService.isPlus(f.uuid());
		String shownName = d.fit(nz(f.name()), RW - 64 - (plus ? 24 : 0), 19, true, 0);
		d.text(shownName, tx, TOP + 21, 19, true, 0.2f, TEXT);
		if (plus) {
			plusMark(d, tx + d.textW(shownName, 19, true, 0.2f) + 14, TOP + 14.5f, 16);
		}
		d.text(d.fit(status(f, " • "), RW - 64, 13, false, 0), tx, TOP + 41, 13, false, 0, f.online() ? DIM : soft());

		float x = RX;
		if (isJoinable(f)) {
			boolean sameVersion = f.version() == null || f.version().equals(Social.version());
			String lab = sameVersion ? "Join" : "Join (" + f.version() + ")";
			float w = buttonW(d, lab, null);
			button(d, lab, null, x, HEAD_ROW, w, 32, Style.PRIMARY, true, () -> joinFriend(f));
			x += w + 8;
		}
		boolean inParty = s.party() != null && s.party().member(f.uuid()) != null;
		String inv = inParty ? "In your party" : "Invite to party";
		float iw = buttonW(d, inv, null);
		button(d, inv, null, x, HEAD_ROW, iw, 32, Style.SECONDARY, !inParty,
				() -> run(SocialClient.inviteToParty(f.uuid()), "Party invite sent to " + f.name() + "."));

		// "..." holds the two destructive jobs, each behind a second click
		float mx = RX + RW - 32;
		Runnable openFriendMenu = () -> openMenuRight("friend:" + f.uuid(), mx + 32, HEAD_ROW + 38,
				List.of(new MenuItem("Remove friend", "Click again to remove " + nz(f.name()), false, true, true,
								() -> {
									run(SocialClient.removeFriend(f.uuid()), f.name() + " was removed.");
									sel = null;
								}),
						new MenuItem("Block " + nz(f.name()), "Click again to block " + nz(f.name()), true, true, true,
								() -> {
									run(SocialClient.block(f.uuid()), f.name() + " is blocked.");
									sel = null;
								})));
		moreButton(d, "friends-more", mx, HEAD_ROW, "More", openFriendMenu);
		if (devMenu) {
			devMenu = false;
			openFriendMenu.run();
		}

		d.fill(RX, DIVIDER, RW, 1, LINE);
		String self = s.me() != null ? s.me().uuid() : "";
		String myName = s.me() != null ? nz(s.me().name()) : "You";
		List<Message> raw = SocialClient.chat(f.uuid());
		drawChat(d, RX, RW, DIVIDER + 8, sigDm(f.uuid(), f.name(), myName, raw), () -> {
			List<Msg> msgs = new ArrayList<>(raw.size());
			for (Message m : raw) {
				boolean mine = m.from().equals(self);
				msgs.add(new Msg(m.from(), mine ? myName : nz(f.name()), m.text(), m.at(), m.pending()));
			}
			return msgs;
		}, self, "No messages yet. Say hi to " + nz(f.name()) + ".", "@user");
		chatInput(d, RX, RW, "Message " + f.name());
	}

	private void moreButton(OriginDeck d, String id, float x, float y, String tip, Runnable open) {
		boolean hov = !menuOpen() && d.over(x, y, 32, 32);
		float hp = OriginUi.hover(id, hov);
		d.rect(x, y, 32, 32, 6, lerp(0x0FFFFFFF, 0x24FFFFFF, hp));
		int c = lerp(DIM, TEXT, hp);
		for (int i = -1; i <= 1; i++) {
			d.circle(x + 16 + i * 6, y + 16, 1.8f, c);
		}
		tip(d, id, tip, x, y, 32, 32);
		d.hit(x, y, 32, 32, open);
	}

	// ---- right: the party ----

	private void drawParty(OriginDeck d, SocialState s) {
		if (!s.inParty()) {
			emptyState(d, RX, RW, "You're not in a party", "Start one, then hover a friend and press Invite. Party chat and warps work on any server.", "@title-multiplayer");
			float bw = buttonW(d, "Start a party", "@plus");
			button(d, "Start a party", "@plus", RX + (RW - bw) / 2, TOP + BODY_H / 2 + 62, bw, 32, Style.PRIMARY, true,
					() -> run(SocialClient.createParty(), "Party started. Hover a friend and press Invite."));
			return;
		}
		var party = s.party();
		String self = s.me() != null ? s.me().uuid() : "";
		boolean leader = s.isLeader();

		d.circle(RX + 22, TOP + 24, 22, ACC_SOFT);
		d.icon("@title-multiplayer", RX + 11, TOP + 13, 22, ACC);
		float tx = RX + 44 + 14;
		d.text("Party", tx, TOP + 21, 19, true, 0.2f, TEXT);
		String leaderName = leader ? "you" : nz(s.nameOf(party.leader()));
		d.text(party.members().size() + " of 8 • led by " + leaderName, tx, TOP + 41, 13, false, 0, DIM);

		float x = RX;
		if (leader) {
			String lab = "Warp party here";
			float w = buttonW(d, lab, null);
			button(d, lab, null, x, HEAD_ROW, w, 32, Style.PRIMARY, true, () -> {
				String server = Social.currentServer(Minecraft.getInstance());
				if (server == null) {
					say("Join a server first, then warp your party to it.", true);
				} else {
					run(SocialClient.warp(server, Social.version()), "Your party is invited to " + server + ".");
				}
			});
			x += w + 8;
		}
		String il = "Invite friend";
		float iw = buttonW(d, il, null);
		float ix = x;
		if (devMenu) {
			devMenu = false;
			openInviteMenu(s, ix, HEAD_ROW + 38);
		}
		button(d, il, null, ix, HEAD_ROW, iw, 32, Style.SECONDARY, true, () -> openInviteMenu(s, ix, HEAD_ROW + 38));
		String ll = "Leave party";
		float lw = buttonW(d, ll, null);
		button(d, ll, null, RX + RW - lw, HEAD_ROW, lw, 32, Style.DANGER, true, () -> {
			run(SocialClient.leaveParty(), "You left the party.");
			sel = null;
		});
		d.fill(RX, DIVIDER, RW, 1, LINE);

		// member tiles: two across, three once there are more than four; at most two rows show, the rest scroll
		int n = party.members().size();
		int cols = n > 4 ? 3 : 2;
		float tileW = (RW - (cols - 1) * 8) / (float) cols, tileH = 40, pitch = 46;
		int rows = (n + cols - 1) / cols, shown = Math.min(rows, 2);
		float gy = DIVIDER + 12, gridH = shown * pitch - 6;
		memMax = Math.max(0, (rows - shown) * pitch);
		memTarget = Math.max(0, Math.min(memMax, memTarget));
		memScroll += (memTarget - memScroll) * 0.3;
		memScroll = Math.max(0, Math.min(memMax, memScroll));
		gridTop = gy;
		gridBot = gy + gridH;
		Minecraft mc = Minecraft.getInstance();
		String here = FairPlay.positionKey(mc);
		d.clip(RX, gy, RW, gridH);
		int i = 0;
		for (Member m : party.members()) {
			float tileX = RX + (i % cols) * (tileW + 8), tileY = gy + (i / cols) * pitch - (float) memScroll;
			if (tileY + tileH >= gy && tileY <= gy + gridH) {
				memberTile(d, s, m, tileX, tileY, tileW, tileH, leader, self, here);
			}
			i++;
		}
		d.unclip();
		if (memMax > 0) {
			float thumb = Math.max(18, gridH * gridH / (gridH + memMax));
			d.rect(RX + RW + 6, gy + (gridH - thumb) * (float) (memScroll / memMax), 3, thumb, 1.5f, 0x33FFFFFF);
		}
		float gridBottom = gy + gridH;
		float chatTop = gridBottom + 10;
		if (mc.level != null && !mc.hasSingleplayerServer() && here == null) {   // = FairPlay.positionsOffHere, without a second lookup
			// Will's baseline: markers, health and pings only in LAN worlds
			d.text("Markers, health and pings show in LAN worlds only.", RX + 2, chatTop + 11, 12, false, 0, GOLD);
			chatTop += 22;
		}
		d.fill(RX, chatTop - 2, RW, 1, LINE);

		List<PartyMessage> rawParty = SocialClient.partyChat();
		drawChat(d, RX, RW, chatTop + 6, sigParty(PARTY, rawParty), () -> {
			List<Msg> msgs = new ArrayList<>(rawParty.size());
			for (PartyMessage m : rawParty) {
				msgs.add(new Msg(m.from(), nz(m.name()), m.text(), m.at(), m.pending()));
			}
			return msgs;
		}, self, "Party chat. In game, type /" + Social.COMMAND + " <message>.", "@title-multiplayer");
		chatInput(d, RX, RW, "Message your party");
	}

	private void memberTile(OriginDeck d, SocialState s, Member m, float x, float y, float w, float h, boolean leader,
							String self, String here) {
		boolean isLeader = m.uuid().equals(s.party().leader());
		boolean me = m.uuid().equals(self);
		Friend fr = s.friend(m.uuid());
		boolean clickable = !me && (fr != null || leader);   // the menu is built only when it is clicked
		boolean hov = clickable && !menuOpen() && d.over(x, y, w, h);
		float hp = OriginUi.hover("friends-mem:" + m.uuid(), hov);
		d.rect(x, y, w, h, 6, lerp(CARD, CARD_H, hp), LINE);
		face(d, m.uuid(), x + 8, y + 9, 22, m.online());
		float nx = x + 38, max = w - 38 - 10;
		String sub;
		if (!m.online()) {
			sub = "Offline";
		} else if (me) {
			sub = "You";
		} else if (m.state() != null && here != null && here.equals(Social.normalize(nz(m.state().server())))) {
			sub = "In your world";
		} else if (fr != null) {
			sub = shortStatus(fr);
		} else {
			sub = "Online";
		}
		float tagW = isLeader ? d.textW("LEADER", 8.5f, true, 1.0f) + 10 : 0;
		d.text(d.fit(nz(m.name()), max - (isLeader ? tagW + 6 : 0), 13, true, 0), nx, y + 17, 13, true, 0, m.online() ? TEXT : DIM);
		if (isLeader) {
			// inside the tile, top-right: it never costs the status line any width
			d.rect(x + w - tagW - 8, y + 6, tagW, 14, 3, GOLD_BG);
			d.textMid("LEADER", x + w - tagW - 3, y + 13.5f, 8.5f, true, 1.0f, GOLD);
		}
		d.text(d.fit(sub, max - (clickable ? 16 : 0), 12, false, 0), nx, y + 32, 12, false, 0, m.online() ? DIM : soft());
		if (clickable) {
			d.icon("@chev-down", x + w - 22, y + h - 20, 12, lerp(soft(), TEXT, hp));
			d.hit(x, y, w, h, () -> openMenu("member:" + m.uuid(), x, y + h + 2, memberMenu(s, m, leader, me), w));
		}
		if (!menuOpen() && d.over(x, y, w, h)) {
			tip(d, "mem:" + m.uuid(), nz(m.name()) + " \u2022 " + sub, x, y, w, h);   // text built only while hovered
		}
	}

	private List<MenuItem> memberMenu(SocialState s, Member m, boolean leader, boolean me) {
		List<MenuItem> items = new ArrayList<>();
		if (me) {
			return items;
		}
		if (s.friend(m.uuid()) != null) {
			items.add(new MenuItem("Message", null, false, false, true, () -> pick(m.uuid())));
		}
		if (leader) {
			items.add(new MenuItem("Make leader", null, false, false, true,
					() -> run(SocialClient.promote(m.uuid()), m.name() + " leads the party now.")));
			items.add(new MenuItem("Remove from party", "Click again to remove " + nz(m.name()), true, true, true,
					() -> run(SocialClient.kick(m.uuid()), m.name() + " was removed from the party.")));
		}
		return items;
	}

	private void openInviteMenu(SocialState s, float x, float y) {
		List<MenuItem> items = new ArrayList<>();
		int extra = 0;
		for (Friend f : s.friends()) {
			if (!f.online() || (s.party() != null && s.party().member(f.uuid()) != null)) {
				continue;
			}
			if (items.size() >= 8) {
				extra++;
				continue;
			}
			items.add(new MenuItem(nz(f.name()), null, false, false, true,
					() -> run(SocialClient.inviteToParty(f.uuid()), "Party invite sent to " + f.name() + ".")));
		}
		if (items.isEmpty()) {
			items.add(new MenuItem("No friends online to invite", null, false, false, false, null));
		} else if (extra > 0) {
			items.add(new MenuItem("+" + extra + " more: hover them in the list", null, false, false, false, null));
		}
		openMenu("invite", x, y, items);
	}

	// ---- right: nothing picked ----

	private void drawWelcome(OriginDeck d, SocialState s) {
		if (s.friends().isEmpty()) {
			float cx = RX + RW / 2, y = TOP + 70;
			d.circle(cx, y, 28, 0x14FFFFFF);
			d.icon("@user", cx - 14, y - 14, 28, DIM);
			d.textCenter("Add your first friend", cx, y + 50, 18, true, 0, TEXT);
			String[] steps = {"Type their Minecraft name in the box on the left.",
					"They accept your request. They need Origin too.",
					"Chat, party up and join each other's servers."};
			float sy = y + 92;
			for (int i = 0; i < steps.length; i++) {
				d.circle(cx - 190, sy, 11, ACC_SOFT);
				d.textCenter(Integer.toString(i + 1), cx - 190, sy, 12, true, 0, ACC);
				d.text(steps[i], cx - 170, sy + 5, 13.5f, false, 0, DIM);
				sy += 40;
			}
		} else {
			emptyState(d, RX, RW, "Pick a friend to chat.", "", "@user");
		}
	}

	// ---- chat (DMs and the party) ----

	/** Width-safe wrap: breaks at spaces, and inside a single word that is wider than the line. */
	private static List<String> wrapHard(OriginDeck d, String text, float maxW, float size) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : text.split(" ", -1)) {
			if (word.isEmpty()) {
				continue;
			}
			String test = cur.length() == 0 ? word : cur + " " + word;
			if (d.textW(test, size, false, 0) <= maxW) {
				cur = new StringBuilder(test);
				continue;
			}
			if (cur.length() > 0) {
				out.add(cur.toString());
				cur = new StringBuilder();
			}
			// a word too wide for a line: cut it at the last character that fits
			String rest = word;
			while (d.textW(rest, size, false, 0) > maxW && rest.length() > 1) {
				int lo = 1, hi = rest.length() - 1;
				while (lo < hi) {
					int mid = (lo + hi + 1) >>> 1;
					if (d.textW(rest.substring(0, mid), size, false, 0) <= maxW) {
						lo = mid;
					} else {
						hi = mid - 1;
					}
				}
				out.add(rest.substring(0, lo));
				rest = rest.substring(lo);
			}
			cur = new StringBuilder(rest);
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		if (out.isEmpty()) {
			out.add("");
		}
		return out;
	}

	private static final ZoneId ZONE = ZoneId.systemDefault();
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private static String clock(long ms) {
		// the localized pattern can use a narrow no-break space the font has no glyph for
		return CLOCK.format(Instant.ofEpochMilli(ms).atZone(ZONE)).replace(' ', ' ').replace(' ', ' ');
	}

	private static String dayLabel(LocalDate day) {
		LocalDate today = LocalDate.now(ZONE);
		if (day.equals(today)) {
			return "Today";
		}
		return day.equals(today.minusDays(1)) ? "Yesterday" : DAY.format(day);
	}

	private void layoutChat(OriginDeck d, List<Msg> msgs, String self, float width) {
		List<ChatRow> rows = new ArrayList<>();
		float total = 0;
		float textW = width - 40;
		LocalDate prevDay = null;
		String prevFrom = null;
		long prevAt = 0;
		for (Msg m : msgs) {
			LocalDate day = Instant.ofEpochMilli(m.at()).atZone(ZONE).toLocalDate();
			boolean newDay = !day.equals(prevDay);
			if (newDay) {
				rows.add(new ChatRow(true, null, dayLabel(day), null, null, false, false, false, 30));
				total += 30;
			}
			boolean head = newDay || !m.from().equals(prevFrom) || m.at() - prevAt > 5 * 60_000L;
			boolean mine = m.from().equals(self);
			List<String> lines = wrapHard(d, m.text(), textW, 14);
			for (int i = 0; i < lines.size(); i++) {
				boolean h = head && i == 0;
				float rh = LINE_H + (h ? HEAD_EXTRA : (i == 0 ? MSG_GAP : 0));
				rows.add(new ChatRow(false, m.from(), m.name(), lines.get(i), h ? clock(m.at()) : null, mine, m.pending(), h, rh));
				total += rh;
			}
			prevDay = day;
			prevFrom = m.from();
			prevAt = m.at();
		}
		chatRows = rows;
		chatTotal = total;
	}

	/** Messages bottom-anchored between `top` and CHAT_BOTTOM; scroll 0 = newest. */
	/**
	 * What the chat layout depends on, held as plain fields so comparing frames builds no strings: the
	 * conversation, the list's ends, the trailing run of pending lines (a confirmed one changes it), the width,
	 * the scale and today's date (the "Today" / "Yesterday" labels roll over at midnight).
	 */
	private static final class ChatSig {
		String scope, nameA, nameB, firstId, lastId, firstPending;
		int count, pending, lastLen, width;
		float scale;
		long today;

		boolean same(ChatSig o) {
			return count == o.count && pending == o.pending && lastLen == o.lastLen && width == o.width
					&& scale == o.scale && today == o.today && java.util.Objects.equals(scope, o.scope)
					&& java.util.Objects.equals(nameA, o.nameA) && java.util.Objects.equals(nameB, o.nameB)
					&& java.util.Objects.equals(firstId, o.firstId) && java.util.Objects.equals(lastId, o.lastId)
					&& java.util.Objects.equals(firstPending, o.firstPending);
		}

		void copy(ChatSig o) {
			scope = o.scope;
			nameA = o.nameA;
			nameB = o.nameB;
			firstId = o.firstId;
			lastId = o.lastId;
			firstPending = o.firstPending;
			count = o.count;
			pending = o.pending;
			lastLen = o.lastLen;
			width = o.width;
			scale = o.scale;
			today = o.today;
		}
	}

	private final ChatSig curSig = new ChatSig(), laidSig = new ChatSig();
	private long todayDay = 0, todayCheckedAt = 0;

	/** Today's epoch day, re-read twice a minute at most. */
	private long today() {
		long now = System.currentTimeMillis();
		if (now - todayCheckedAt > 30_000) {
			todayCheckedAt = now;
			todayDay = LocalDate.now(ZONE).toEpochDay();
		}
		return todayDay;
	}

	private ChatSig sigDm(String scope, String nameA, String nameB, List<Message> l) {
		ChatSig c = curSig;
		c.scope = scope;
		c.nameA = nameA;
		c.nameB = nameB;
		int n = l.size();
		c.count = n;
		c.pending = 0;
		c.firstPending = null;
		if (n == 0) {
			c.firstId = c.lastId = null;
			c.lastLen = 0;
			return c;
		}
		Message a = l.get(0), z = l.get(n - 1);
		c.firstId = a.id();
		c.lastId = z.id();
		c.lastLen = z.text().length();
		for (int i = n - 1; i >= 0 && l.get(i).pending(); i--) {   // pending lines sit at the tail
			c.pending++;
			c.firstPending = l.get(i).id();
		}
		return c;
	}

	private ChatSig sigParty(String scope, List<PartyMessage> l) {
		ChatSig c = curSig;
		c.scope = scope;
		c.nameA = c.nameB = null;
		int n = l.size();
		c.count = n;
		c.pending = 0;
		c.firstPending = null;
		if (n == 0) {
			c.firstId = c.lastId = null;
			c.lastLen = 0;
			return c;
		}
		PartyMessage a = l.get(0), z = l.get(n - 1);
		c.firstId = a.id();
		c.lastId = z.id();
		c.lastLen = z.text().length();
		for (int i = n - 1; i >= 0 && l.get(i).pending(); i--) {
			c.pending++;
			c.firstPending = l.get(i).id();
		}
		return c;
	}

	private void drawChat(OriginDeck d, float x, float w, float top, ChatSig sig,
						  java.util.function.Supplier<List<Msg>> msgs, String self, String empty, String emptyIcon) {
		int count = sig.count;
		float viewH = CHAT_BOTTOM - top;
		d.hit(x, top, w, viewH, () -> {
			if (focus != Focus.CHAT) {
				setFocus(Focus.CHAT);
			}
		});
		if (count == 0) {
			d.icon(emptyIcon, x + w / 2 - 12, (top + CHAT_BOTTOM) / 2 - 30, 24, soft());
			for (String line : d.wrap(empty, w - 64, 13.5f, false)) {
				d.textCenter(line, x + w / 2, (top + CHAT_BOTTOM) / 2 + 6, 13.5f, false, 0, DIM);
				break;
			}
			chatMax = 0;
			return;
		}
		sig.width = Math.round(w);
		sig.scale = d.s;
		sig.today = today();
		if (!sig.same(laidSig)) {
			laidSig.copy(sig);
			layoutChat(d, msgs.get(), self, w);
		}
		chatMax = Math.max(0, chatTotal - viewH);
		chatTarget = Math.max(0, Math.min(chatMax, chatTarget));
		chatScroll += (chatTarget - chatScroll) * 0.3;
		chatScroll = Math.max(0, Math.min(chatMax, chatScroll));

		d.clip(x, top, w, viewH);
		float y = CHAT_BOTTOM + (float) chatScroll;
		float textX = x + 40;
		for (int i = chatRows.size() - 1; i >= 0; i--) {
			ChatRow r = chatRows.get(i);
			y -= r.h();
			if (y + r.h() < top || y > CHAT_BOTTOM) {
				continue;
			}
			if (r.day()) {
				float lw = d.textW(r.name(), 11.5f, true, 1.0f) + 20;
				float cx = x + w / 2;
				d.fill(x, y + 15, w / 2 - lw / 2, 1, LINE);
				d.fill(cx + lw / 2, y + 15, w / 2 - lw / 2, 1, LINE);
				d.textCenter(r.name(), cx, y + 15, 11.5f, true, 1.0f, labelCol());
				continue;
			}
			float ty = y + r.h() - LINE_H;
			if (r.head()) {
				face(d, r.uuid(), x, y + 8, 28, false);
				float nw = d.text(r.mine() ? "You" : d.fit(nz(r.name()), w - 120, 14, true, 0), textX, y + 22, 14, true, 0,
						r.mine() ? ACC : TEXT);
				d.text(r.time(), textX + nw + 8, y + 22, 12, false, 0, soft());
			}
			d.text(r.text(), textX, ty + 14, 14, false, 0, r.pending() ? DIM : TEXT);
		}
		if (chatScroll < chatMax - 0.5 && !d.seeThrough) {
			d.gradient(x, top, w, 18, PANEL, withAlpha(PANEL, 0));   // more above: fade the cut edge instead of slicing a line
		}
		d.unclip();
		if (chatMax > 0 && chatScroll > 8) {
			// scrolled up: a quiet way back down
			String t = "Jump to latest";
			float bw = d.textW(t, 12, true, 0) + 28, bx = x + w / 2 - bw / 2, by = CHAT_BOTTOM - 30;
			boolean hov = !menuOpen() && d.over(bx, by, bw, 24);
			d.rect(bx, by, bw, 24, 6, hov ? CARD_H : CARD, LINE2);
			d.textCenter(t, bx + bw / 2, by + 12, 12, true, 0, hov ? TEXT : DIM);
			d.hit(bx, by, bw, 24, () -> chatTarget = 0);
		}
	}

	private void chatInput(OriginDeck d, float x, float w, String placeholder) {
		float sw = 84;
		float fy = BOTTOM - INPUT_H;
		String ct = chatText();
		inputField(d, x, fy, w - sw - 8, INPUT_H, ct, placeholder, focus == Focus.CHAT, null,
				ct.length() > MAX_CHAT - 56 ? ct.length() + "/" + MAX_CHAT : null, ct.length() >= MAX_CHAT);
		d.hit(x, fy, w - sw - 8, INPUT_H, () -> setFocus(Focus.CHAT));
		button(d, "Send", null, x + w - sw, fy, sw, INPUT_H, Style.PRIMARY, !ct.isBlank(), this::submitChat);
	}

	// ---- small widgets ----

	/** A text field: the focused one carries an emerald edge (never colour-only: the caret blinks too). */
	private void inputField(OriginDeck d, float x, float y, float w, float h, String text, String placeholder,
							boolean focused, String icon, String counter, boolean counterRed) {
		if (focused) {
			// a SOLID 2 px emerald frame: a filled emerald plate with the (opaque) field inset on top.
			// Stacked 1 px SDF borders never reach full strength, so the frame is the plate, not a ring.
			d.rect(x - 1, y - 1, w + 2, h + 2, 7, ACC);
			d.rect(x + 1, y + 1, w - 2, h - 2, 5, over(FIELD, 0xFF000000 | (PANEL & 0xFFFFFF)));
		} else {
			d.rect(x, y, w, h, 6, FIELD, LINE2);
		}
		float tx = x + 12;
		if (icon != null) {
			d.icon(icon, tx, y + h / 2 - 7, 14, soft());
			tx += 22;
		}
		float right = x + w - 12;
		if (counter != null) {
			float cw = d.textW(counter, 11.5f, false, 0);
			d.textMid(counter, right - cw, y + h / 2, 11.5f, false, 0, counterRed ? RED : soft());
			right -= cw + 10;
		}
		boolean empty = text == null || text.isEmpty();
		float end;
		if (empty) {
			end = tx;
			d.textMid(placeholder, tx + 3, y + h / 2, 13.5f, false, 0, soft());
		} else {
			// long text shows its END (where you are typing), not its start
			String shown = tail(d, text, right - tx, 13.5f);
			end = tx + d.textMid(shown, tx, y + h / 2, 13.5f, false, 0, TEXT);
		}
		if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
			d.fill(end + (empty ? 0 : 1), y + h / 2 - 8, 1.5f, 16, TEXT);
		}
	}

	private static String tail(OriginDeck d, String s, float maxW, float size) {
		if (d.textW(s, size, false, 0) <= maxW) {
			return s;
		}
		int lo = 1, hi = s.length() - 1;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (d.textW("…" + s.substring(mid), size, false, 0) <= maxW) {
				hi = mid;
			} else {
				lo = mid + 1;
			}
		}
		return "…" + s.substring(lo);
	}

	/** A friend's face (with an online dot) at design px; fades with the frame. */
	private void face(OriginDeck d, String uuid, float x, float y, float size, boolean online) {
		int px = Math.round(d.ox + x * d.s), py = Math.round(d.oy + y * d.s), ps = Math.round(size * d.s);
		d.rect(x - 1, y - 1, size + 2, size + 2, 4, 0x1AFFFFFF);
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, d.alpha);
		SocialSkins.face(d.g, uuid, px, py, ps);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		if (online) {
			float r = Math.max(4, size * 0.16f);
			d.circle(x + size, y + size, r + 2, withAlpha(PANEL, 0xFF));
			d.circle(x + size, y + size, r, ACC);
		}
	}

	/** A 24 px button with a text label (the row's own actions). SOFT = emerald text on a faint wash. */
	private void miniButton(OriginDeck d, String label, float x, float y, float w, Mini style, Runnable action) {
		float h = 24;
		boolean hov = !menuOpen() && d.over(x, y, w, h);
		int fill, fg;
		switch (style) {
			case PRIMARY -> {
				fill = hov ? ACC_H : ACC;
				fg = ON_ACC;
			}
			case SOFT -> {
				fill = hov ? withAlpha(ACC, 0x40) : ACC_SOFT;
				fg = hov ? ACC_H : ACC;
			}
			case DANGER -> {
				fill = hov ? 0x3DD9675A : RED_SOFT;
				fg = RED_TEXT;
			}
			case GHOST -> {
				fill = hov ? 0x14FFFFFF : 0x00000000;
				fg = hov ? TEXT : DIM;
			}
			default -> {
				fill = hov ? 0x24FFFFFF : 0x14FFFFFF;
				fg = TEXT;
			}
		}
		if ((fill >>> 24) != 0) {
			d.rect(x, y, w, h, 5, fill);
		}
		d.textCenter(label.toUpperCase(Locale.ROOT), x + w / 2, y + h / 2, 10.5f, true, 1.2f, fg);
		d.hit(x, y, w, h, action);
	}

	private static float miniW(OriginDeck d, String label) {
		return d.textW(label.toUpperCase(Locale.ROOT), 10.5f, true, 1.2f) + 18;
	}

	/** The row-button styles. SOFT = emerald text on a faint wash (a quiet call to action). */
	private enum Mini {
		PRIMARY, SOFT, SECONDARY, GHOST, DANGER
	}

	private void arm(String what) {
		armed = what;
		armedAt = System.currentTimeMillis();
	}

	private boolean armedFor(String what) {
		return what.equals(armed) && System.currentTimeMillis() - armedAt < 3000;
	}

	// ---- the pop-up menu ----

	private float menuMinW = 208;

	private void openMenu(String id, float x, float y, List<MenuItem> items) {
		openMenu(id, x, y, items, 208);
	}

	private void openMenu(String id, float x, float y, List<MenuItem> items, float minW) {
		menuMinW = minW;
		menu = items;
		menuId = id;
		menuX = x;
		menuY = y;
		menuRight = Float.NaN;
		armed = null;
	}

	private void openMenuRight(String id, float rightEdge, float y, List<MenuItem> items) {
		openMenu(id, rightEdge, y, items);
		menuRight = rightEdge;
	}

	private void closeMenu() {
		menu = null;
		armed = null;
	}

	private void drawMenu(OriginDeck d) {
		if (menu == null) {
			return;
		}
		// a click anywhere else closes it: this hit is recorded before the items, so they win
		d.hit(0, 0, W, H, this::closeMenu);
		float itemH = 32;
		float h = menu.size() * itemH + 8;
		float menuW = menuMinW;   // at least the tile it came from; wider for the longest label, including the named confirm line
		for (MenuItem mi : menu) {
			menuW = Math.max(menuW, d.textW(mi.label(), 13, false, 0) + 28);
			if (mi.confirmLabel() != null) {
				menuW = Math.max(menuW, d.textW(mi.confirmLabel(), 13, false, 0) + 28);
			}
		}
		menuW = Math.min(menuW, 340);
		float left = Float.isNaN(menuRight) ? menuX : menuRight - menuW;
		float x = Math.max(8, Math.min(W - menuW - 8, left));
		float y = Math.min(menuY, H - h - 8);
		d.shadow(x, y, menuW, h, 6);
		d.rect(x, y, menuW, h, 6, CARD_H, LINE2);
		float iy = y + 4;
		for (MenuItem mi : menu) {
			String key = menuId + ":" + mi.label();
			boolean isArmed = mi.confirm() && armedFor(key);
			boolean hov = mi.enabled() && d.over(x + 4, iy, menuW - 8, itemH);
			if (hov) {
				d.rect(x + 4, iy, menuW - 8, itemH, 4, mi.danger() ? RED_SOFT : 0x1AFFFFFF);
			}
			String label = isArmed ? (mi.confirmLabel() != null ? mi.confirmLabel() : "Click again to confirm") : mi.label();
			int col = !mi.enabled() ? soft() : mi.danger() || isArmed ? RED_TEXT : hov ? TEXT : DIM;
			d.textMid(d.fit(label, menuW - 28, 13, false, 0), x + 14, iy + itemH / 2, 13, false, 0, col);
			if (mi.enabled()) {
				d.hit(x + 4, iy, menuW - 8, itemH, () -> {
					if (mi.confirm()) {
						if (!armedFor(key)) {
							arm(key);
							return;
						}
						if (System.currentTimeMillis() - armedAt < 350) {
							return;   // a fast double-click must not skip the confirm
						}
					}
					closeMenu();
					if (mi.action() != null) {
						mi.action().run();
					}
				});
			}
			iy += itemH;
		}
	}

	// ---- the result pill (what an action just did, next to where you clicked) ----

	private String nlText = null;
	private List<String> nlLines = List.of();

	/** The pill's wrapped lines while a notice is showing (cached per text), else null. */
	private List<String> noticeLines(OriginDeck d) {
		if (notice == null || System.currentTimeMillis() - noticeAt > 5000) {
			return null;
		}
		if (!notice.equals(nlText)) {
			nlText = notice;
			List<String> l = d.wrap(notice, LIST_W - 40, 13, false);
			nlLines = l.size() > 3 ? l.subList(0, 3) : l;
		}
		return nlLines;
	}

	private void drawNotice(OriginDeck d) {
		if (notice == null) {
			return;
		}
		long age = System.currentTimeMillis() - noticeAt;
		List<String> lines = noticeLines(d);
		if (lines == null) {
			return;
		}
		float h = 16 + lines.size() * 18;
		float y = BOTTOM - h - 8;
		float fade = age > 4500 ? (5000 - age) / 500f : 1f;
		int edge = noticeRed ? RED : ACC;
		d.rect(BODY_X, y, LIST_W, h, 6, mul(CARD_H, fade), mul(withAlpha(edge, 0x88), fade));
		d.rect(BODY_X, y + 7, 3, h - 14, 1.5f, mul(edge, fade));
		float ly = y + 8;
		for (String l : lines) {
			d.text(l, BODY_X + 14, ly + 14, 13, false, 0, mul(TEXT, fade));
			ly += 18;
		}
		d.hit(BODY_X, y, LIST_W, h, () -> noticeAt = 0);
	}

	// ---- tooltips (icon-only controls) ----

	private void tip(OriginDeck d, String key, String text, float x, float y, float w, float h) {
		if (!menuOpen() && d.over(x, y, w, h)) {
			if (!key.equals(tipKey)) {
				tipKey = key;
				tipSince = System.currentTimeMillis();
			}
			tipText = text;
			tipX = x + w / 2;
			tipY = y + h + 6;
		}
	}

	private void drawTooltip(OriginDeck d) {
		if (tipText == null) {
			tipKey = null;
			return;
		}
		if (System.currentTimeMillis() - tipSince < 300) {
			return;
		}
		float w = d.textW(tipText, 12, false, 0) + 20;
		float x = Math.max(6, Math.min(W - w - 6, tipX - w / 2));
		d.rect(x, tipY, w, 24, 5, 0xF0101311, LINE2);
		d.textCenter(tipText, x + w / 2, tipY + 12, 12, false, 0, TEXT);
	}

	// ---- status text ----

	/** "Playing on hypixel.net - 1.21.1", "In the launcher", "Last seen 3 h ago". */
	public static String status(Friend f, String sep) {
		return statusLine(f, sep, true);
	}

	/** List rows leave the version out when it is the one you are running (it only matters when it differs). */
	private static String rowStatus(Friend f) {
		boolean same = f.version() == null || f.version().isEmpty() || f.version().equals(Social.version());
		return statusLine(f, " • ", !same);
	}

	private static String statusLine(Friend f, String sep, boolean withVersion) {
		if (!f.online()) {
			return f.lastSeen() > 0 ? "Last seen " + ago(f.lastSeen()) : "Offline";
		}
		if ("launcher".equals(f.client())) {
			return "In the launcher";
		}
		String v = !withVersion || f.version() == null || f.version().isEmpty() ? "" : sep + f.version();
		if ("server".equals(f.activity())) {
			return (f.server() != null ? "Playing on " + f.server() : "Playing online") + v;
		}
		if ("singleplayer".equals(f.activity())) {
			return "Playing singleplayer" + v;
		}
		return "In the menus" + v;
	}

	/** The short form the party tiles use. */
	private static String shortStatus(Friend f) {
		if (!f.online()) {
			return "Offline";
		}
		if ("launcher".equals(f.client())) {
			return "In the launcher";
		}
		if ("server".equals(f.activity())) {
			return f.server() != null ? f.server() : "Online";
		}
		return "singleplayer".equals(f.activity()) ? "Singleplayer" : "In the menus";
	}

	private static String ago(long ms) {
		long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
		if (s < 60) {
			return "just now";
		}
		long m = s / 60;
		if (m < 60) {
			return m + " min ago";
		}
		long h = m / 60;
		if (h < 24) {
			return h + (h == 1 ? " hour ago" : " hours ago");
		}
		long days = h / 24;
		return days < 60 ? days + (days == 1 ? " day ago" : " days ago") : "a while ago";
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	// ---- input ----

	private void submitAdd() {
		String name = search.trim();
		if (name.isEmpty()) {
			return;
		}
		search = "";
		run(SocialClient.addFriend(name), "Friend request sent to " + name + ".");
	}

	private void submitChat() {
		String t = chatText().strip();
		if (t.isEmpty()) {
			return;
		}
		String key = sel;
		java.util.concurrent.CompletableFuture<String> sent = null;
		if (PARTY.equals(key)) {
			sent = SocialClient.partyChat(t, false);
		} else if (friendSelected()) {
			sent = SocialClient.sendMessage(key, t);
		}
		if (sent == null) {
			return;
		}
		setChatText("");
		chatTarget = 0;
		setFocus(Focus.CHAT);
		// the text leaves the box at once (the pending line shows); if the send fails it comes back,
		// unless that conversation already has a newer draft
		sent.thenAccept(err -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				if (drafts.getOrDefault(key, "").isEmpty()) {
					drafts.put(key, t);
				}
				say(err, true);
			}
		}));
	}

	/** Enter in the search box: open the first match; with no match, add the typed name. */
	private void searchEnter() {
		if (!matchKeys.isEmpty()) {
			pick(matchKeys.get(0));
			scrollToKey = matchKeys.get(0);
		} else if (canAddNow) {
			submitAdd();
		}
	}

	private void moveSelection(int dir) {
		if (navKeys.isEmpty()) {
			return;
		}
		int i = sel == null ? -1 : navKeys.indexOf(sel);
		int n = i < 0 ? (dir > 0 ? 0 : navKeys.size() - 1) : Math.max(0, Math.min(navKeys.size() - 1, i + dir));
		String key = navKeys.get(n);
		sel = key;
		chatTarget = chatScroll = 0;
		scrollToKey = key;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		focus = Focus.NONE;   // a click on a field's hit focuses it again
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sy) {   // pre-1.20: one scroll amount
		if (menuOpen()) {
			return true;
		}
		float x = dX(mx), y = dY(my);
		if (x < SPLIT) {
			listTarget = Math.max(0, Math.min(listMax, listTarget - sy * (ROW_H + 4) * 2));
		} else if (PARTY.equals(sel) && memMax > 0 && y >= gridTop && y < gridBot) {
			memTarget = Math.max(0, Math.min(memMax, memTarget - sy * 46));
		} else {
			chatTarget = Math.max(0, Math.min(chatMax, chatTarget + sy * 48));
		}
		return true;
	}

	private boolean chatAvailable() {
		return sel != null && SocialClient.status() == SocialClient.Status.READY
				&& (!PARTY.equals(sel) || SocialClient.state().inParty());
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (!net.minecraft.SharedConstants.isAllowedChatCharacter(chr)) {
			return super.charTyped(chr, modifiers);
		}
		if (SocialClient.status() != SocialClient.Status.READY) {
			return super.charTyped(chr, modifiers);
		}
		if (menuOpen()) {
			return true;   // a menu is waiting for its click: typing must not land anywhere else
		}
		if (focus == Focus.NONE) {
			// just start typing: into the open chat, or the search box when no chat is open
			// no field has the cursor: text goes where it last was (the chat only if you were just typing in it)
			setFocus(lastField == Focus.CHAT && chatAvailable() ? Focus.CHAT : Focus.SEARCH);
		}
		if (focus == Focus.SEARCH) {
			if ((Character.isLetterOrDigit(chr) || chr == '_') && chr < 128 && search.length() < 16) {
				search += chr;
				listTarget = 0;
			}
			return true;
		}
		if (focus == Focus.CHAT) {
			String ct = chatText();
			if (ct.length() < MAX_CHAT) {
				setChatText(ct + chr);
			}
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (SocialClient.status() == SocialClient.Status.READY) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				// one Esc at a time: menu, then the search text, then the window
				if (menuOpen()) {
					closeMenu();
					return true;
				}
				if (!search.isEmpty()) {
					search = "";
					return true;
				}
				beginClose();
				return true;
			}
			if (menuOpen()) {
				return true;   // Esc (above) closes it; Up/Down/Enter/Backspace do nothing behind it
			}
			if (keyCode == GLFW.GLFW_KEY_F && hasControlDown()) {
				setFocus(Focus.SEARCH);
				return true;
			}
			if (focus != Focus.CHAT && (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP)) {
				moveSelection(keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1);
				return true;
			}
			if (focus != Focus.NONE) {
				if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
					if (focus == Focus.SEARCH) {
						searchEnter();
					} else {
						submitChat();
					}
					return true;
				}
				if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
					if (focus == Focus.SEARCH) {
						search = cut(search, hasControlDown());
					} else {
						setChatText(cut(chatText(), hasControlDown()));
					}
					return true;
				}
				if (Screen.isPaste(keyCode)) {
					String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replace('\n', ' ');
					for (char c : clip.toCharArray()) {
						charTyped(c, 0);
					}
					return true;
				}
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static String cut(String s, boolean word) {
		if (s.isEmpty()) {
			return s;
		}
		if (!word) {
			return s.substring(0, s.length() - 1);
		}
		String t = s.stripTrailing();
		int i = t.lastIndexOf(' ');
		return i < 0 ? "" : t.substring(0, i + 1);
	}
}
