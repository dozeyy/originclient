package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * Friends: the game's half of Origin friends / chat / parties (docs/SOCIAL.md),
 * on the shared OriginWindow frame so it reads as one with the mod menu.
 *
 *   header   Friends + status line ............ notice | APPEAR OFFLINE [o] [x]
 *   left     add-by-username field, then one scrolling list: PARTY (your party,
 *            or "Start a party"), party INVITES, friend REQUESTS, ONLINE,
 *            OFFLINE -- faces, status lines ("Playing on hypixel.net • 1.21.1"),
 *            unread counts
 *   right    the picked friend: face, status, JOIN / INVITE TO PARTY / REMOVE /
 *            BLOCK, then your chat with them; or the party: WARP PARTY HERE
 *            (leader) / LEAVE, members (leader mark, LEAD / KICK on hover,
 *            friends to invite) beside the party chat
 *
 * Everything is read from SocialClient's snapshots and every action goes
 * through it (background thread); a result sentence shows in the header for a
 * few seconds. While the service isn't usable the body is one calm message
 * ("Sign in with Microsoft to use friends", "Friends are coming soon", ...).
 * Click targets are recorded while drawing (d.hit), like every OriginWindow.
 *
 * Pre-1.20 port: faces come from SocialSkins as skin textures drawn through
 * the Gfx shim; one scroll amount; SharedConstants' chat-character check.
 */
public class FriendsScreen extends OriginWindow {
	/** {@link #select} values besides a friend's uuid. */
	public static final String PARTY = "@party", REQUESTS = "@requests";

	// ---- layout (design px, inside OriginWindow's body rect) ----
	private static final float LIST_W = 272;
	private static final float SPLIT = BODY_X + LIST_W + 12;
	private static final float RX = SPLIT + 12, RW = BODY_X + BODY_W - RX;
	private static final float TOP = BODY_Y, BOTTOM = BODY_Y + BODY_H;
	private static final float LIST_TOP = TOP + 32 + 16;
	private static final float HEAD_ROW = TOP + 60;                 // the action buttons
	private static final float DIVIDER = TOP + 108;
	private static final float CHAT_TOP = DIVIDER + 13, CHAT_BOTTOM = BOTTOM - 36 - 12;
	private static final float MEM_W = 216;
	private static final float ROW_H = 48, LABEL_H = 28, ROW_GAP = 4;
	private static final float LINE_H = 18, BUBBLE_PAD = 6;

	private enum Focus { NONE, ADD, CHAT }

	private String sel;                       // friend uuid, PARTY, or null (pick a default)
	private Focus focus = Focus.NONE;
	private String addText = "", chatText = "";
	private String notice = null;
	private boolean noticeRed = false;
	private long noticeAt = 0;
	private String armed = null;              // "remove:<uuid>" / "block:<uuid>" waiting for a second click
	private long armedAt = 0;

	private double listScroll, listTarget, chatScroll, chatTarget, memScroll, memTarget;
	private float listMax, chatMax, memMax;

	public FriendsScreen(Screen returnTo) {
		super(new TextComponent("Friends"), returnTo);
	}

	/** Opens on a friend (uuid), the party ({@link #PARTY}) or the requests ({@link #REQUESTS}). */
	public FriendsScreen select(String what) {
		if (REQUESTS.equals(what)) {
			sel = null;
			listTarget = 0;
		} else if (what != null) {
			sel = what;
			chatTarget = chatScroll = 0;
		}
		return this;
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
		float x = right;
		if (SocialClient.status() == SocialClient.Status.READY) {
			boolean on = SocialClient.appearOffline();
			x -= 36;
			d.toggle("friends-offline", x, 22, on);
			String lab = "APPEAR OFFLINE";
			float lw = d.textW(lab, 11, true, CAPS_TRACK);
			boolean hov = d.over(x - 10 - lw, 16, lw + 10 + 36, 32);
			d.textRight(lab, x - 10, 32, 11, true, CAPS_TRACK, on || hov ? DIM : MUTE);
			d.hit(x - 10 - lw, 16, lw + 10 + 36, 32, () -> run(SocialClient.setAppearOffline(!on),
					!on ? "You appear offline to your friends." : "Your friends can see you again."));
			x -= 10 + lw + 20;
		}
		if (notice != null && System.currentTimeMillis() - noticeAt < 5000) {
			String n = d.fit(notice, Math.max(60, x - 400), 12.5f, false, 0);
			d.textRight(n, x, 32, 12.5f, false, 0, noticeRed ? RED : ACC);
		}
	}

	private void say(String text, boolean red) {
		notice = text;
		noticeRed = red;
		noticeAt = System.currentTimeMillis();
	}

	/** Runs an action; its error (or `ok`) shows in the header. */
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
		} catch (Throwable t) {
			// a draw failure leaves the bare frame; Esc / close still work
			if (!drawWarned) {
				drawWarned = true;
				com.origin.client.OriginClient.LOGGER.warn("Origin: Friends window draw failed", t);
			}
		}
	}

	private static boolean drawWarned = false;

	private boolean friendSelected() {
		return sel != null && !PARTY.equals(sel);
	}

	private void resolveSelection(SocialState s) {
		if (sel != null && !PARTY.equals(sel) && s.friend(sel) == null) {
			sel = null;
		}
		if (sel == null) {
			for (Friend f : s.friends()) {
				if (SocialClient.unread(f.uuid()) > 0) {
					sel = f.uuid();
					return;
				}
			}
			if (s.inParty()) {
				sel = PARTY;
			} else if (!s.friends().isEmpty() && s.friends().get(0).online()) {
				sel = s.friends().get(0).uuid();
			}
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
		emptyState(d, BODY_X, BODY_W, title, sub);
		if (retry) {
			float bw = buttonW(d, "Try again", null);
			button(d, "Try again", null, BODY_X + (BODY_W - bw) / 2, TOP + BODY_H / 2 + 56, bw, 32, Style.SECONDARY, true,
					SocialClient::syncSoon);
		}
	}

	private void emptyState(OriginDeck d, float x, float w, String title, String sub) {
		float cx = x + w / 2, cy = TOP + BODY_H / 2 - 30;
		d.circle(cx, cy - 34, 28, 0x0FFFFFFF);
		d.icon("@user", cx - 14, cy - 48, 28, MUTE);
		d.textCenter(title, cx, cy + 14, 16, true, 0, TEXT);
		float y = cy + 40;
		for (String line : d.wrap(sub, Math.min(w - 48, 460), 13, false)) {
			d.textCenter(line, cx, y, 13, false, 0, DIM);
			y += 20;
		}
	}

	// ---- left: add field + the list ----

	private void drawList(OriginDeck d, SocialState s) {
		field(d, BODY_X, TOP, LIST_W - 40, 32, addText, "Add friend by username", focus == Focus.ADD, "@user");
		d.hit(BODY_X, TOP, LIST_W - 40, 32, () -> focus = Focus.ADD);
		iconButton(d, "@plus", BODY_X + LIST_W - 32, TOP, 32, !addText.isBlank(), this::submitAdd);

		// measure first so the scroll range is right this frame
		float contentH = listHeight(s);
		listMax = Math.max(0, contentH - (BOTTOM - LIST_TOP));
		listTarget = Math.max(0, Math.min(listMax, listTarget));
		listScroll += (listTarget - listScroll) * 0.3;
		listScroll = Math.max(0, Math.min(listMax, listScroll));

		d.clip(BODY_X, LIST_TOP, LIST_W, BOTTOM - LIST_TOP);
		float y = LIST_TOP - (float) listScroll;

		label(d, "Party", BODY_X, y + 16);
		y += LABEL_H;
		if (s.inParty()) {
			partyRow(d, s, y);
		} else {
			startPartyRow(d, y);
		}
		y += ROW_H + ROW_GAP;

		if (!s.invites().isEmpty()) {
			y += 8;
			label(d, "Party invites • " + s.invites().size(), BODY_X, y + 16);
			y += LABEL_H;
			for (PartyInvite inv : s.invites()) {
				actionRow(d, y, inv.from(), inv.name() + "'s party", "Invited you",
						() -> run(SocialClient.acceptInvite(inv.partyId()), "You joined " + inv.name() + "'s party."),
						() -> run(SocialClient.declineInvite(inv.partyId()), null));
				y += ROW_H + ROW_GAP;
			}
		}

		if (!s.incoming().isEmpty() || !s.outgoing().isEmpty()) {
			y += 8;
			label(d, "Requests • " + (s.incoming().size() + s.outgoing().size()), BODY_X, y + 16);
			y += LABEL_H;
			for (Request r : s.incoming()) {
				actionRow(d, y, r.uuid(), r.name(), "Wants to be friends",
						() -> run(SocialClient.respond(r.uuid(), true), "You and " + r.name() + " are friends."),
						() -> run(SocialClient.respond(r.uuid(), false), null));
				y += ROW_H + ROW_GAP;
			}
			for (Request r : s.outgoing()) {
				actionRow(d, y, r.uuid(), r.name(), "Request sent", null,
						() -> run(SocialClient.removeFriend(r.uuid()), "Request cancelled."));
				y += ROW_H + ROW_GAP;
			}
		}

		List<Friend> online = new ArrayList<>(), offline = new ArrayList<>();
		for (Friend f : s.friends()) {
			(f.online() ? online : offline).add(f);
		}
		y += 8;
		label(d, "Online • " + online.size(), BODY_X, y + 16);
		y += LABEL_H;
		if (s.friends().isEmpty()) {
			for (String line : d.wrap("No friends yet. Add one by their Minecraft username above.", LIST_W - 28, 12.5f, false)) {
				d.text(line, BODY_X + 14, y + 16, 12.5f, false, 0, MUTE);
				y += 18;
			}
		}
		for (Friend f : online) {
			friendRow(d, f, y);
			y += ROW_H + ROW_GAP;
		}
		if (!offline.isEmpty()) {
			y += 8;
			label(d, "Offline • " + offline.size(), BODY_X, y + 16);
			y += LABEL_H;
			for (Friend f : offline) {
				friendRow(d, f, y);
				y += ROW_H + ROW_GAP;
			}
		}
		if (!s.blocked().isEmpty()) {
			y += 8;
			label(d, "Blocked • " + s.blocked().size(), BODY_X, y + 16);
			y += LABEL_H;
			for (Request b : s.blocked()) {
				rowBack(d, "blk:" + b.uuid(), y, false);
				face(d, b.uuid(), BODY_X + 10, y + 10, 28, false);
				d.text(d.fit(nz(b.name()), LIST_W - 56 - 84, 13.5f, true, 0), BODY_X + 48, y + 21, 13.5f, true, 0, DIM);
				d.text("Blocked", BODY_X + 48, y + 37, 11.5f, false, 0, MUTE);
				miniButton(d, "Unblock", BODY_X + LIST_W - 10 - 74, y + 13, 74, Style.SECONDARY,
						() -> run(SocialClient.unblock(b.uuid()), (b.name() == null ? "They're" : b.name() + " is") + " unblocked."));
				y += ROW_H + ROW_GAP;
			}
		}
		d.unclip();
		if (listMax > 0) {
			float track = BOTTOM - LIST_TOP;
			float thumb = Math.max(28, track * track / (track + listMax));
			d.rect(SPLIT - 6, LIST_TOP + (track - thumb) * (float) (listScroll / listMax), 3, thumb, 1.5f, 0x33FFFFFF);
		}
	}

	/** Same walk as drawList, heights only. */
	private float listHeight(SocialState s) {
		float h = LABEL_H + ROW_H + ROW_GAP;
		if (!s.invites().isEmpty()) {
			h += 8 + LABEL_H + s.invites().size() * (ROW_H + ROW_GAP);
		}
		int req = s.incoming().size() + s.outgoing().size();
		if (req > 0) {
			h += 8 + LABEL_H + req * (ROW_H + ROW_GAP);
		}
		long on = s.friends().stream().filter(Friend::online).count();
		h += 8 + LABEL_H + on * (ROW_H + ROW_GAP) + (s.friends().isEmpty() ? 40 : 0);
		long off = s.friends().size() - on;
		if (off > 0) {
			h += 8 + LABEL_H + off * (ROW_H + ROW_GAP);
		}
		if (!s.blocked().isEmpty()) {
			h += 8 + LABEL_H + s.blocked().size() * (ROW_H + ROW_GAP);
		}
		return h + 8;
	}

	private float rowBack(OriginDeck d, String key, float y, boolean selected) {
		boolean hov = d.over(BODY_X, y, LIST_W, ROW_H);
		float hp = OriginUi.hover("friends-row:" + key, hov);
		int fill = selected ? ACC_SOFT : lerp(0x00000000, CARD, hp);
		if ((fill >>> 24) != 0) {
			d.rect(BODY_X, y, LIST_W, ROW_H, 6, fill);
		}
		return hp;
	}

	private void partyRow(OriginDeck d, SocialState s, float y) {
		boolean selected = PARTY.equals(sel);
		float hp = rowBack(d, "party", y, selected);
		d.circle(BODY_X + 24, y + ROW_H / 2, 14, selected ? 0x295FD09B : 0x14FFFFFF);
		d.icon("@user", BODY_X + 17, y + ROW_H / 2 - 7, 14, selected ? ACC : DIM);
		int n = s.party().members().size();
		d.text("Your party", BODY_X + 48, y + 21, 13.5f, true, 0, selected ? ACC : lerp(DIM, TEXT, hp));
		String sub = n + (n == 1 ? " member" : " members") + (s.isLeader() ? " • you lead" : "");
		d.text(sub, BODY_X + 48, y + 37, 11.5f, false, 0, MUTE);
		d.hit(BODY_X, y, LIST_W, ROW_H, () -> select(PARTY));
	}

	private void startPartyRow(OriginDeck d, float y) {
		float hp = rowBack(d, "start", y, false);
		d.rect(BODY_X, y, LIST_W, ROW_H, 6, 0x00000000, lerp(LINE2, 0x29F1E9D2, hp));
		d.circle(BODY_X + 24, y + ROW_H / 2, 14, 0x0FFFFFFF);
		d.icon("@plus", BODY_X + 17, y + ROW_H / 2 - 7, 14, lerp(DIM, ACC, hp));
		d.text("Start a party", BODY_X + 48, y + 21, 13.5f, true, 0, lerp(DIM, TEXT, hp));
		d.text("Then invite friends to it", BODY_X + 48, y + 37, 11.5f, false, 0, MUTE);
		d.hit(BODY_X, y, LIST_W, ROW_H, () -> {
			sel = PARTY;
			run(SocialClient.createParty(), "Party started. Invite your friends.");
		});
	}

	private void friendRow(OriginDeck d, Friend f, float y) {
		boolean selected = f.uuid().equals(sel);
		float hp = rowBack(d, f.uuid(), y, selected);
		face(d, f.uuid(), BODY_X + 10, y + 10, 28, f.online());
		int unread = SocialClient.unread(f.uuid());
		float nameMax = LIST_W - 56 - (unread > 0 ? 34 : 12);
		int nameCol = selected ? ACC : f.online() ? lerp(DIM, TEXT, Math.max(hp, unread > 0 ? 1 : 0)) : lerp(MUTE, DIM, hp);
		d.text(d.fit(nz(f.name()), nameMax, 13.5f, true, 0), BODY_X + 48, y + 21, 13.5f, true, 0, nameCol);
		d.text(d.fit(status(f, " • "), LIST_W - 60, 11.5f, false, 0), BODY_X + 48, y + 37, 11.5f, false, 0, MUTE);
		if (unread > 0) {
			String c = unread > 9 ? "9+" : Integer.toString(unread);
			d.circle(BODY_X + LIST_W - 22, y + ROW_H / 2, 10, ACC);
			d.textCenter(c, BODY_X + LIST_W - 22, y + ROW_H / 2, 10.5f, true, 0, ON_ACC);
		}
		d.hit(BODY_X, y, LIST_W, ROW_H, () -> select(f.uuid()));
	}

	/** A request / invite row: face, two lines, then accept (check) and decline (x). */
	private void actionRow(OriginDeck d, float y, String uuid, String name, String sub, Runnable accept, Runnable decline) {
		rowBack(d, "act:" + uuid + sub, y, false);
		face(d, uuid, BODY_X + 10, y + 10, 28, false);
		float right = BODY_X + LIST_W - 10;
		float textMax = LIST_W - 56 - (accept != null ? 72 : 40);
		d.text(d.fit(nz(name), textMax, 13.5f, true, 0), BODY_X + 48, y + 21, 13.5f, true, 0, TEXT);
		d.text(d.fit(sub, textMax, 11.5f, false, 0), BODY_X + 48, y + 37, 11.5f, false, 0, MUTE);
		iconButton(d, "@close", right - 28, y + 10, 28, true, decline);
		if (accept != null) {
			iconButton(d, "@check", right - 28 - 6 - 28, y + 10, 28, true, accept, true);
		}
	}

	// ---- right: one friend ----

	private void drawFriend(OriginDeck d, SocialState s, Friend f) {
		face(d, f.uuid(), RX, TOP + 4, 44, f.online());
		float tx = RX + 44 + 16;
		d.text(d.fit(nz(f.name()), RW - 64, 18, true, 0), tx, TOP + 22, 18, true, 0.2f, TEXT);
		d.text(d.fit(status(f, " • "), RW - 64, 12.5f, false, 0), tx, TOP + 42, 12.5f, false, 0, f.online() ? DIM : MUTE);

		float x = RX;
		boolean onServer = f.online() && "server".equals(f.activity()) && f.server() != null;
		if (onServer) {
			boolean sameVersion = f.version() == null || f.version().equals(Social.version());
			String lab = sameVersion ? "Join" : "Join (" + f.version() + ")";
			float w = buttonW(d, lab, null);
			button(d, lab, null, x, HEAD_ROW, w, 32, Style.PRIMARY, true, () -> {
				String why = Social.whyCantJoin(f.server(), f.version());
				if (why != null) {
					say(why, true);
				} else {
					beginClose();
					Social.join(f.server());
				}
			});
			x += w + 8;
		}
		boolean inParty = s.party() != null && s.party().member(f.uuid()) != null;
		String inv = inParty ? "In your party" : "Invite to party";
		float iw = buttonW(d, inv, null);
		button(d, inv, null, x, HEAD_ROW, iw, 32, Style.SECONDARY, !inParty,
				() -> run(SocialClient.inviteToParty(f.uuid()), "Party invite sent to " + f.name() + "."));

		float r = RX + RW;
		boolean blockArmed = armedFor("block:" + f.uuid());
		String bl = blockArmed ? "Confirm block" : "Block";
		float bw = buttonW(d, bl, null);
		button(d, bl, null, r - bw, HEAD_ROW, bw, 32, Style.DANGER, true, () -> {
			if (armedFor("block:" + f.uuid())) {
				armed = null;
				run(SocialClient.block(f.uuid()), f.name() + " is blocked.");
				sel = null;
			} else {
				arm("block:" + f.uuid());
			}
		});
		r -= bw + 8;
		boolean removeArmed = armedFor("remove:" + f.uuid());
		String rl = removeArmed ? "Confirm remove" : "Remove";
		float rw = buttonW(d, rl, null);
		button(d, rl, null, r - rw, HEAD_ROW, rw, 32, removeArmed ? Style.DANGER : Style.GHOST, true, () -> {
			if (armedFor("remove:" + f.uuid())) {
				armed = null;
				run(SocialClient.removeFriend(f.uuid()), f.name() + " was removed.");
				sel = null;
			} else {
				arm("remove:" + f.uuid());
			}
		});

		d.fill(RX, DIVIDER, RW, 1, LINE);
		List<Bubble> bubbles = new ArrayList<>();
		String self = s.me() != null ? s.me().uuid() : "";
		for (Message m : SocialClient.chat(f.uuid())) {
			bubbles.add(new Bubble(m.from().equals(self), null, m.text(), m.pending()));
		}
		drawChat(d, RX, RW, bubbles, "No messages yet. Say hi to " + f.name() + ".");
		chatInput(d, RX, RW, "Message " + f.name());
	}

	// ---- right: the party ----

	private void drawParty(OriginDeck d, SocialState s) {
		if (!s.inParty()) {
			emptyState(d, RX, RW, "You're not in a party", "Start one, then invite friends. Party chat and warps work on any server.");
			float bw = buttonW(d, "Start a party", "@plus");
			button(d, "Start a party", "@plus", RX + (RW - bw) / 2, TOP + BODY_H / 2 + 56, bw, 32, Style.PRIMARY, true,
					() -> run(SocialClient.createParty(), "Party started. Invite your friends."));
			return;
		}
		var party = s.party();
		String self = s.me() != null ? s.me().uuid() : "";
		boolean leader = s.isLeader();

		d.circle(RX + 22, TOP + 26, 22, ACC_SOFT);
		d.icon("@user", RX + 11, TOP + 15, 22, ACC);
		float tx = RX + 44 + 16;
		d.text("Party", tx, TOP + 22, 18, true, 0.2f, TEXT);
		String leaderName = leader ? "you" : nz(s.nameOf(party.leader()));
		d.text(party.members().size() + " of 8 • led by " + leaderName, tx, TOP + 42, 12.5f, false, 0, DIM);
		Minecraft mc = Minecraft.getInstance();
		String here = FairPlay.positionKey(mc);
		if (FairPlay.positionsOffHere(mc)) {
			// Will's baseline: markers, health and pings only in LAN worlds
			String t = "MARKERS: LAN WORLDS ONLY";
			float w = 24 + d.textW(t, 11, true, CAPS_TRACK);
			d.rect(RX + RW - w, TOP + 10, w, 28, 6, GOLD_BG);
			d.textMid(t, RX + RW - w + 12, TOP + 24, 11, true, CAPS_TRACK, GOLD);
		}

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
		}
		String ll = "Leave party";
		float lw = buttonW(d, ll, null);
		button(d, ll, null, RX + RW - lw, HEAD_ROW, lw, 32, Style.DANGER, true, () -> {
			run(SocialClient.leaveParty(), "You left the party.");
			sel = null;
		});
		d.fill(RX, DIVIDER, RW, 1, LINE);

		// members column (its own scroll), then the party chat beside it
		float mx = RX, top = CHAT_TOP, bottom = BOTTOM;
		List<Friend> invitable = new ArrayList<>();
		for (Friend f : s.friends()) {
			if (f.online() && party.member(f.uuid()) == null) {
				invitable.add(f);
			}
		}
		float contentH = LABEL_H + party.members().size() * 48 + (invitable.isEmpty() ? 0 : 8 + LABEL_H + invitable.size() * 40);
		memMax = Math.max(0, contentH - (bottom - top));
		memTarget = Math.max(0, Math.min(memMax, memTarget));
		memScroll += (memTarget - memScroll) * 0.3;
		d.clip(mx, top, MEM_W, bottom - top);
		float y = top - (float) memScroll;
		label(d, "Members • " + party.members().size(), mx, y + 14);
		y += LABEL_H;
		for (Member m : party.members()) {
			memberRow(d, s, m, mx, y, leader, self, here);
			y += 48;
		}
		if (!invitable.isEmpty()) {
			y += 8;
			label(d, "Invite", mx, y + 14);
			y += LABEL_H;
			for (Friend f : invitable) {
				face(d, f.uuid(), mx + 4, y + 8, 24, true);
				d.text(d.fit(nz(f.name()), MEM_W - 40 - 76, 13, false, 0), mx + 38, y + 24, 13, false, 0, DIM);
				miniButton(d, "Invite", mx + MEM_W - 66, y + 9, 62, Style.SECONDARY,
						() -> run(SocialClient.inviteToParty(f.uuid()), "Party invite sent to " + f.name() + "."));
				y += 40;
			}
		}
		d.unclip();

		float cx = RX + MEM_W + 16, cw = RW - MEM_W - 16;
		d.fill(cx - 8, CHAT_TOP, 1, BOTTOM - CHAT_TOP, LINE);
		List<Bubble> bubbles = new ArrayList<>();
		for (PartyMessage m : SocialClient.partyChat()) {
			boolean mine = m.from().equals(self);
			bubbles.add(new Bubble(mine, mine ? null : m.name(), m.text(), m.pending()));
		}
		drawChat(d, cx, cw, bubbles, "Party chat. In game, type /" + Social.COMMAND + " <message>.");
		chatInput(d, cx, cw, "Message your party");
	}

	private void memberRow(OriginDeck d, SocialState s, Member m, float x, float y, boolean leader, String self, String here) {
		boolean isLeader = m.uuid().equals(s.party().leader());
		boolean me = m.uuid().equals(self);
		boolean hov = d.over(x, y, MEM_W, 44);
		float hp = OriginUi.hover("friends-mem:" + m.uuid(), hov);
		if (hp > 0.01f) {
			d.rect(x, y, MEM_W, 44, 6, mul(CARD, hp));
		}
		face(d, m.uuid(), x + 6, y + 10, 24, m.online());
		float nx = x + 40;
		boolean actions = leader && !me && hov;
		// the leader's mark: a small gold tag after the name (the ★ glyph renders badly at this size)
		float tagW = isLeader ? d.textW("LEADER", 9, true, 1.2f) + 10 : 0;
		float nameMax = MEM_W - 48 - (actions ? 100 : 0) - (isLeader ? tagW + 6 : 0);
		float adv = d.text(d.fit(nz(m.name()), nameMax, 13, true, 0), nx, y + 20, 13, true, 0,
				m.online() ? TEXT : DIM);
		if (isLeader) {
			d.rect(nx + adv + 6, y + 7, tagW, 16, 4, GOLD_BG);
			d.textMid("LEADER", nx + adv + 11, y + 15, 9, true, 1.2f, GOLD);
		}
		String sub;
		if (!m.online()) {
			sub = "Offline";
		} else if (me) {
			sub = "You";
		} else if (m.state() != null && here != null && here.equals(Social.normalize(nz(m.state().server())))) {
			sub = "In your world";
		} else if (SocialClient.state().friend(m.uuid()) != null) {
			sub = status(SocialClient.state().friend(m.uuid()), " • ");
		} else {
			sub = "Online";
		}
		d.text(d.fit(sub, MEM_W - 48 - (actions ? 100 : 0), 11.5f, false, 0), nx, y + 35, 11.5f, false, 0, MUTE);
		if (actions) {
			miniButton(d, "Kick", x + MEM_W - 46, y + 11, 42, Style.DANGER,
					() -> run(SocialClient.kick(m.uuid()), m.name() + " was removed from the party."));
			miniButton(d, "Lead", x + MEM_W - 46 - 4 - 46, y + 11, 46, Style.SECONDARY,
					() -> run(SocialClient.promote(m.uuid()), m.name() + " leads the party now."));
		}
	}

	// ---- right: nothing picked ----

	private void drawWelcome(OriginDeck d, SocialState s) {
		if (s.friends().isEmpty()) {
			emptyState(d, RX, RW, "Your friends live here", "Add friends by their Minecraft username. They need Origin too. Then chat, party up and join each other's servers.");
		} else {
			emptyState(d, RX, RW, "Pick a friend", "Chat with them, join their server or invite them to your party.");
		}
	}

	// ---- chat (DMs and the party) ----

	private record Bubble(boolean mine, String name, String text, boolean pending) {
	}

	/** Bubbles bottom-anchored between CHAT_TOP and CHAT_BOTTOM; scroll 0 = newest. */
	private void drawChat(OriginDeck d, float x, float w, List<Bubble> bubbles, String empty) {
		if (bubbles.isEmpty()) {
			for (String line : d.wrap(empty, w - 48, 13, false)) {
				d.textCenter(line, x + w / 2, (CHAT_TOP + CHAT_BOTTOM) / 2, 13, false, 0, MUTE);
				break;
			}
			chatMax = 0;
			return;
		}
		float maxB = Math.min(380, w * 0.72f);
		// lay out from the newest upward
		List<float[]> heights = new ArrayList<>();
		List<List<String>> lines = new ArrayList<>();
		float total = 0;
		for (int i = 0; i < bubbles.size(); i++) {
			Bubble b = bubbles.get(i);
			List<String> ls = d.wrap(b.text(), maxB - 2 * 12, 13, false);
			lines.add(ls);
			boolean head = b.name() != null && (i == 0 || !b.name().equals(bubbles.get(i - 1).name()));
			float h = ls.size() * LINE_H + 2 * BUBBLE_PAD + (head ? 18 : 0);
			heights.add(new float[]{h, head ? 1 : 0});
			total += h + 6;
		}
		chatMax = Math.max(0, total - (CHAT_BOTTOM - CHAT_TOP));
		chatTarget = Math.max(0, Math.min(chatMax, chatTarget));
		chatScroll += (chatTarget - chatScroll) * 0.3;
		chatScroll = Math.max(0, Math.min(chatMax, chatScroll));

		d.clip(x, CHAT_TOP, w, CHAT_BOTTOM - CHAT_TOP);
		float y = CHAT_BOTTOM + (float) chatScroll;
		for (int i = bubbles.size() - 1; i >= 0; i--) {
			Bubble b = bubbles.get(i);
			float h = heights.get(i)[0];
			boolean head = heights.get(i)[1] > 0;
			y -= h;
			if (y + h >= CHAT_TOP && y <= CHAT_BOTTOM) {
				List<String> ls = lines.get(i);
				float tw = 0;
				for (String l : ls) {
					tw = Math.max(tw, d.textW(l, 13, false, 0));
				}
				float bw = tw + 24, by = y + (head ? 18 : 0), bh = h - (head ? 18 : 0);
				float bx = b.mine() ? x + w - bw : x;
				if (head) {
					d.text(d.fit(b.name(), w, 11, true, 0), bx + 2, y + 12, 11, true, 0, MUTE);
				}
				if (b.mine()) {
					d.rect(bx, by, bw, bh, 8, b.pending() ? 0x145FD09B : ACC_SOFT);
				} else {
					d.rect(bx, by, bw, bh, 8, CARD, LINE);
				}
				float ly = by + BUBBLE_PAD + LINE_H / 2;
				for (String l : ls) {
					d.textMid(l, bx + 12, ly, 13, false, 0, b.pending() ? DIM : TEXT);
					ly += LINE_H;
				}
			}
			y -= 6;
		}
		d.unclip();
	}

	private void chatInput(OriginDeck d, float x, float w, String placeholder) {
		float sw = 88;
		float fy = BOTTOM - 36;
		field(d, x, fy, w - sw - 8, 36, chatText, placeholder, focus == Focus.CHAT, null);
		d.hit(x, fy, w - sw - 8, 36, () -> focus = Focus.CHAT);
		button(d, "Send", null, x + w - sw, fy, sw, 36, Style.PRIMARY, !chatText.isBlank(), this::submitChat);
	}

	// ---- small widgets ----

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
			d.circle(x + size, y + size, r + 2, 0xFF141816);
			d.circle(x + size, y + size, r, ACC);
		}
	}

	private void iconButton(OriginDeck d, String icon, float x, float y, float size, boolean enabled, Runnable action) {
		iconButton(d, icon, x, y, size, enabled, action, false);
	}

	private void iconButton(OriginDeck d, String icon, float x, float y, float size, boolean enabled, Runnable action,
							boolean accent) {
		boolean hov = enabled && d.over(x, y, size, size);
		int fill = accent ? (hov ? 0xFF83E2B5 : ACC) : hov ? 0x1AFFFFFF : 0x0FFFFFFF;
		int fg = accent ? ON_ACC : enabled ? (hov ? TEXT : DIM) : MUTE;
		d.rect(x, y, size, size, 6, fill);
		float is = 14;
		d.icon(icon, x + (size - is) / 2, y + (size - is) / 2, is, fg);
		if (enabled && action != null) {
			d.hit(x, y, size, size, action);
		}
	}

	private void miniButton(OriginDeck d, String label, float x, float y, float w, Style style, Runnable action) {
		boolean hov = d.over(x, y, w, 22);
		int fill = style == Style.DANGER ? (hov ? 0x3DD9675A : RED_SOFT) : hov ? 0x1AFFFFFF : 0x0FFFFFFF;
		int fg = style == Style.DANGER ? RED : hov ? TEXT : DIM;
		d.rect(x, y, w, 22, 5, fill);
		d.textCenter(label.toUpperCase(Locale.ROOT), x + w / 2, y + 11, 10, true, 1.6f, fg);
		d.hit(x, y, w, 22, action);
	}

	private void arm(String what) {
		armed = what;
		armedAt = System.currentTimeMillis();
	}

	private boolean armedFor(String what) {
		return what.equals(armed) && System.currentTimeMillis() - armedAt < 3000;
	}

	/** "Playing on hypixel.net • 1.21.1", "In the launcher", "Offline". */
	public static String status(Friend f, String sep) {
		if (!f.online()) {
			return "Offline";
		}
		if ("launcher".equals(f.client())) {
			return "In the launcher";
		}
		String v = f.version() == null || f.version().isEmpty() ? "" : sep + f.version();
		if ("server".equals(f.activity())) {
			return (f.server() != null ? "Playing on " + f.server() : "Playing online") + v;
		}
		if ("singleplayer".equals(f.activity())) {
			return "Playing singleplayer" + v;
		}
		return "In the menus" + v;
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	// ---- input ----

	private void submitAdd() {
		String name = addText.trim();
		if (name.isEmpty()) {
			return;
		}
		addText = "";
		run(SocialClient.addFriend(name), "Friend request sent to " + name + ".");
	}

	private void submitChat() {
		String t = chatText.strip();
		if (t.isEmpty()) {
			return;
		}
		chatText = "";
		chatTarget = 0;
		if (PARTY.equals(sel)) {
			run(SocialClient.partyChat(t, false), null);
		} else if (friendSelected()) {
			run(SocialClient.sendMessage(sel, t), null);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		focus = Focus.NONE;   // a click on a field's hit focuses it again
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sy) {
		float x = dX(mx);
		if (x < SPLIT) {
			listTarget = Math.max(0, Math.min(listMax, listTarget - sy * (ROW_H + ROW_GAP) * 2));
		} else if (PARTY.equals(sel) && x < RX + MEM_W + 8) {
			memTarget = Math.max(0, Math.min(memMax, memTarget - sy * 48));
		} else {
			chatTarget = Math.max(0, Math.min(chatMax, chatTarget + sy * 48));
		}
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (!net.minecraft.SharedConstants.isAllowedChatCharacter(chr)) {
			return super.charTyped(chr, modifiers);
		}
		if (focus == Focus.NONE && sel != null && SocialClient.status() == SocialClient.Status.READY
				&& (PARTY.equals(sel) ? SocialClient.state().inParty() : true)) {
			focus = Focus.CHAT;   // just start typing to chat
		}
		if (focus == Focus.ADD) {
			if ((Character.isLetterOrDigit(chr) || chr == '_') && addText.length() < 16) {
				addText += chr;
			}
			return true;
		}
		if (focus == Focus.CHAT) {
			if (chatText.length() < 256) {
				chatText += chr;
			}
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (focus != Focus.NONE) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				if (focus == Focus.ADD) {
					submitAdd();
				} else {
					submitChat();
				}
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
				if (focus == Focus.ADD) {
					addText = cut(addText, hasControlDown());
				} else {
					chatText = cut(chatText, hasControlDown());
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
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				focus = Focus.NONE;
				return true;
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
