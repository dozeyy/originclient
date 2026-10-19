package com.origin.client.client.social;

import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.gui.OriginText;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Friends pop-ups: a DM, a friend request, a party invite, the leader's warp.
 * A vanilla toast (top right, stacks, slides in and out on its own) in the
 * Origin panel look the FPS Coach nudge uses. Clicking one opens the Friends
 * window on that friend (or the party). The three that ask something carry a
 * button so answering takes one click (2026-10-18; it used to take two, toast
 * then the check in the list): ACCEPT on a friend request, JOIN on a party
 * invite and on the leader's warp. The toast then shows what happened.
 *
 * Vanilla toasts take no clicks, so each one remembers where it was last drawn
 * (the translation ToastManager applied to the pose) and Social's screen
 * mouse hook asks {@link #click} first. That works wherever the mouse is free
 * -- any menu, or the chat box in game.
 *
 * PER-VERSION DELTA (26.3 Toast contract, as FpsCoachToast on this module):
 * the time-out lives in update(ToastManager, long) + getWantedVisibility(),
 * extractRenderState(GuiGraphicsExtractor, Font, long) only draws, the queue is
 * Minecraft.gui.toastManager(), and the GUI pose is a Matrix3x2f (translation in
 * m20/m21). Buttons, copy and the stale checks are 1.21.1's.
 */
public final class SocialToast implements Toast {
	private static final int W = 232, H = 32;
	private static final long SHOW_MS = 7_000, RESULT_MS = 3_500;
	private static final int BTN_H = 16;

	private enum Kind { MESSAGE, REQUEST, INVITE, WARP }

	/** Toasts drawn in the last moment, newest last (for click hit-testing). */
	private static final List<SocialToast> LIVE = new CopyOnWriteArrayList<>();

	private final Kind kind;
	private final String token;
	private final String uuid;
	private String title, line;
	/** The name the toast is about (kept when `line` is replaced by an outcome). */
	private final String who;
	private final SocialState.Warp warp;
	private boolean restart = false, dismissed = false;
	/** The button was pressed: it is gone and `line` shows the outcome (red when `failed`). */
	private boolean acted = false, failed = false;
	private long shownFrom = 0, showMs = SHOW_MS;
	private volatile float lastX = Float.NaN, lastY;
	private volatile long lastDrawn = 0;

	private SocialToast(Kind kind, String token, String uuid, String title, String line, SocialState.Warp warp) {
		this.kind = kind;
		this.token = token;
		this.uuid = uuid;
		this.title = title;
		this.line = line;
		this.who = line;
		this.warp = warp;
	}

	// ---- the four pop-ups ----

	public static void message(Minecraft mc, String uuid, String name, String text) {
		show(mc, new SocialToast(Kind.MESSAGE, "dm:" + uuid, uuid, name, text, null));
	}

	public static void request(Minecraft mc, String uuid, String name) {
		show(mc, new SocialToast(Kind.REQUEST, "req:" + uuid, uuid, "Friend request", name, null));
	}

	public static void invite(Minecraft mc, String uuid, String name) {
		show(mc, new SocialToast(Kind.INVITE, "inv:" + uuid, uuid, "Party invite", name, null));
	}

	public static void warp(Minecraft mc, String uuid, String name, SocialState.Warp w) {
		String where = w.server() == null ? "their server" : w.server();
		show(mc, new SocialToast(Kind.WARP, "warp", uuid, "Party warp", name + " on " + where, w));
	}

	/** A second DM from the same friend updates the visible toast instead of stacking. */
	private static void show(Minecraft mc, SocialToast t) {
		SocialToast old = mc.gui.toastManager().getToast(SocialToast.class, t.token);
		if (old != null && !old.dismissed) {
			old.title = t.title;
			old.line = t.line;
			old.acted = false;
			old.failed = false;
			old.showMs = SHOW_MS;
			old.restart = true;
			return;
		}
		mc.gui.toastManager().addToast(t);
	}

	@Override
	public Object getToken() {
		return token;
	}

	@Override
	public int width() {
		return W;
	}

	@Override
	public int height() {
		return H;
	}

	private boolean hidden = false;

	// The time-out (1.21.1 does this at the end of render). Runs every frame,
	// before render, from ToastManager.update.
	@Override
	public void update(ToastManager toasts, long timeSinceVisible) {
		if (restart) {
			restart = false;
			shownFrom = timeSinceVisible;
		}
		long shown = timeSinceVisible - shownFrom;
		hidden = dismissed || shown >= showMs * toasts.getNotificationDisplayTimeMultiplier();
		if (hidden) {
			LIVE.remove(this);
		}
	}

	@Override
	public Visibility getWantedVisibility() {
		return hidden ? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, Font font, long timeSinceVisible) {
		try {
			// remember where ToastManager put us (GUI px) for click()
			lastX = g.pose().m20();
			lastY = g.pose().m21();
			lastDrawn = System.currentTimeMillis();
			if (!hidden && !LIVE.contains(this)) {
				// ToastManager.clear() (leaving a world) drops toasts without a last render: forget the ones not drawn for a while
				long cutoff = lastDrawn - 2_000;
				LIVE.removeIf(t -> t != this && t.lastDrawn < cutoff);
				LIVE.add(this);
			}
			draw(g, font);
		} catch (Throwable t) {
			// a broken draw just leaves the toast plain; it still times out
		}
	}

	/** The button this toast carries, or null (a plain message toast, or one already answered). */
	private String buttonLabel() {
		if (acted) {
			return null;
		}
		return switch (kind) {
			case REQUEST -> "ACCEPT";
			case INVITE, WARP -> "JOIN";
			default -> null;
		};
	}

	private int buttonW(Font font) {
		String b = buttonLabel();
		return b == null ? 0 : OriginText.widthBold(font, b) + 14;
	}

	private void draw(GuiGraphicsExtractor g, Font font) {
		OriginUi.panel(g, 0, 0, W, H, 8, 0xF0121614, failed ? OriginTheme.DANGER : OriginTheme.STROKE_STRONG);
		if (uuid != null && !uuid.isEmpty()) {
			SocialSkins.face(g, uuid, 8, 8, 16);
		} else {
			OriginUi.logo(g, 16, 16, 16, 1f);
		}
		int bw = buttonW(font);
		int textW = W - 38 - (bw > 0 ? bw + 6 : 0);
		OriginText.drawBold(g, font, OriginText.ellipsize(font, title, textW), 30, 7, OriginTheme.TEXT, false);
		OriginText.draw(g, font, OriginText.ellipsize(font, line, textW), 30, 19,
				failed ? OriginTheme.DANGER : OriginTheme.TEXT_DIM, false);
		if (bw > 0) {
			int bx = W - bw - 8, by = (H - BTN_H) / 2;
			OriginUi.panel(g, bx, by, bw, BTN_H, 5, OriginTheme.ACCENT, 0);
			String b = buttonLabel();
			int tw = OriginText.widthBold(font, b);
			OriginText.drawBold(g, font, b, bx + (bw - tw) / 2, by + 4, com.origin.client.client.gui.OriginDeck.ON_ACC, false);
		}
	}

	/** Social's mouse hook: true if the click landed on a pop-up (and was handled). */
	static boolean click(double mx, double my) {
		long now = System.currentTimeMillis();
		for (int i = LIVE.size() - 1; i >= 0; i--) {
			SocialToast t = LIVE.get(i);
			if (now - t.lastDrawn > 250 || Float.isNaN(t.lastX)) {
				continue;
			}
			double lx = mx - t.lastX, ly = my - t.lastY;
			if (lx < 0 || ly < 0 || lx >= W || ly >= H) {
				continue;
			}
			Minecraft mc = Minecraft.getInstance();
			int bw = t.buttonW(mc.font);
			if (bw > 0 && lx >= W - bw - 10 && lx < W - 6) {
				t.answer(mc);          // ACCEPT / JOIN: one click does the job
				return true;
			}
			t.dismissed = true;
			LIVE.remove(t);
			if (!t.acted) {
				Social.openFriends(t.kind == Kind.INVITE || t.kind == Kind.WARP ? FriendsScreen.PARTY
						: t.kind == Kind.REQUEST ? FriendsScreen.REQUESTS : t.uuid);
			}
			return true;
		}
		return false;
	}

	/** DEV SHOTS ONLY: click the button of the live toast with this token (e.g. "req:<uuid>"); false if none. */
	public static boolean devClickButton(String token) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		for (int i = LIVE.size() - 1; i >= 0; i--) {   // newest first, and only toasts that are on screen right now
			SocialToast t = LIVE.get(i);
			if (t.token.equals(token) && !Float.isNaN(t.lastX) && now - t.lastDrawn <= 250) {
				int bw = t.buttonW(mc.font);
				return bw > 0 && click(t.lastX + W - bw / 2.0 - 8, t.lastY + H / 2.0);
			}
		}
		return false;
	}

	/** The toast's button: accept the request / party invite, or follow the warp. */
	private void answer(Minecraft mc) {
		switch (kind) {
			case WARP -> {
				dismissed = true;
				LIVE.remove(this);
				joinWarp(mc, warp);
			}
			case REQUEST -> {
				// "already answered" is only knowable from a fresh snapshot: on a dropped connection just send it
				boolean live = SocialClient.status() == SocialClient.Status.READY && !SocialClient.stale();
				boolean pending = false;
				for (SocialState.Request r : SocialClient.state().incoming()) {
					pending |= uuid != null && uuid.equals(r.uuid());
				}
				if (live && !pending) {
					// answered somewhere else (the list, the launcher) since the toast came up: don't POST again
					settle("Friend request", "Already answered", false);
					return;
				}
				finish(SocialClient.respond(uuid, true), "Friend request", "Friend added", "You and " + who + " are friends.");
			}
			case INVITE -> {
				boolean live = SocialClient.status() == SocialClient.Status.READY && !SocialClient.stale();
				String partyId = null;
				for (SocialState.PartyInvite i : SocialClient.state().invites()) {
					if (uuid != null && uuid.equals(i.from())) {
						partyId = i.partyId();
						break;
					}
				}
				if (partyId == null) {
					if (live) {
						// expired or answered elsewhere since the toast came up
						settle("Party invite", "That invite is gone", false);
					} else {
						settle("Couldn't do that", "Can't reach Origin. Try again.", true);
					}
					return;
				}
				finish(SocialClient.acceptInvite(partyId), "Party invite", "Joined party", "You joined " + who + "'s party.");
			}
			default -> {
			}
		}
	}

	/** Swap the buttons for the outcome (a few seconds), whichever way it went. */
	private void settle(String heading, String text, boolean bad) {
		acted = true;
		failed = bad;
		title = heading;
		line = text;
		showMs = RESULT_MS;
		restart = true;
	}

	private void finish(java.util.concurrent.CompletableFuture<String> result, String heading, String okTitle, String okLine) {
		acted = true;
		failed = false;
		title = heading;
		line = "Working...";
		showMs = SHOW_MS;
		restart = true;
		result.thenAccept(err -> Minecraft.getInstance().execute(() -> {
			failed = err != null;
			title = failed ? "Couldn't do that" : okTitle;
			line = failed ? err : okLine;
			showMs = RESULT_MS;
			restart = true;
		}));
	}

	/** The warp's JOIN: connect now if this game can, else say why (other version -> the launcher). */
	static void joinWarp(Minecraft mc, SocialState.Warp w) {
		if (w == null) {
			return;
		}
		String why = Social.whyCantJoin(w.server(), w.version());
		if (why == null) {
			Social.join(w.server());
		} else if (mc.player != null) {
			mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(why));
		} else {
			Social.openFriends(FriendsScreen.PARTY);
		}
	}
}
