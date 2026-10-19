package com.origin.client.client.social;

import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginText;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatComponentText;

import java.util.ArrayList;
import java.util.List;

/**
 * Friends pop-ups: a DM, a friend request, a party invite, the leader's warp.
 * Top right, stacking, sliding in and out on their own, in the Origin panel
 * look the FPS Coach nudge uses. Clicking one opens the Friends window on that
 * friend (or the party). The three that ask something carry a button so
 * answering takes one click (2026-10-18; it used to take two, toast then the
 * check in the list): ACCEPT on a friend request, JOIN on a party invite and on
 * the leader's warp. The toast then shows what happened.
 *
 * 1.8.9 has no toast system (only the achievement pop-up, GuiAchievement), so
 * this class IS the toast stack, rebuilt on 1.16.5's ToastComponent rules:
 * up to {@link #MAX_VISIBLE} at a time from the top right, each sliding in over
 * 600 ms (squared ease, vanilla's), shown {@link #SHOW_MS} (an answered one
 * {@link #RESULT_MS}), sliding out over 600 ms; a second DM from the same
 * friend updates the visible toast instead of stacking. SocialHooks draws it at
 * the END of every render tick -- after the HUD, any screen and its widgets,
 * like vanilla's toasts are -- and asks {@link #click} first on every screen
 * mouse click, so a toast takes clicks wherever the mouse is free (any menu, or
 * the chat box in game). The draw position is computed here, so click
 * hit-testing needs no GL read-back. Unlike vanilla's, this stack is not
 * cleared when a world is left (it never was on 1.8.9).
 */
public final class SocialToast {
	private static final int W = 232, H = 32;
	private static final long SHOW_MS = 7_000, RESULT_MS = 3_500;
	private static final long SLIDE_MS = 600;
	private static final int BTN_H = 16;
	private static final int MAX_VISIBLE = 5;

	private enum Kind { MESSAGE, REQUEST, INVITE, WARP }

	/** Every toast, oldest first; the first MAX_VISIBLE show, the rest wait. Client thread only. */
	private static final List<SocialToast> TOASTS = new ArrayList<>();

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
	/** When it first showed / was last refreshed / started sliding out (ms; -1 = not yet). */
	private long visibleAt = -1, shownFrom = -1, hideAt = -1;
	private long showMs = SHOW_MS;
	private float lastX = Float.NaN, lastY;
	private long lastDrawn = 0;

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
		for (SocialToast old : TOASTS) {
			if (old.token.equals(t.token) && !old.dismissed && old.hideAt < 0) {
				old.title = t.title;
				old.line = t.line;
				old.acted = false;
				old.failed = false;
				old.showMs = SHOW_MS;
				old.restart = true;
				return;
			}
		}
		TOASTS.add(t);
	}

	/** DEV SHOTS ONLY: drop every toast (1.21.1's harness calls ToastComponent.clear() between shots). */
	public static void clearAll() {
		TOASTS.clear();
	}

	// ---- drawing (SocialHooks, end of every render tick) ----

	/** Draws the stack in GUI px over everything. Client thread; fail-soft. */
	public static void renderAll() {
		if (TOASTS.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		long now = System.currentTimeMillis();
		int sw = new ScaledResolution(mc).getScaledWidth();
		boolean setUp = false;
		int slot = 0;
		for (int i = 0; i < TOASTS.size() && slot < MAX_VISIBLE; i++, slot++) {
			SocialToast t = TOASTS.get(i);
			if (t.visibleAt < 0) {
				t.visibleAt = now;
				t.shownFrom = now;
			}
			if (t.restart) {
				// refreshed (a second DM) or answered: the show time starts again
				t.restart = false;
				t.shownFrom = now;
			}
			if (t.hideAt < 0 && (t.dismissed || now - t.shownFrom >= t.showMs)) {
				t.hideAt = now;
			}
			float vis;
			if (t.hideAt < 0) {
				float f = Math.min(1f, (now - t.visibleAt) / (float) SLIDE_MS);
				vis = f * f;
			} else {
				float f = Math.min(1f, (now - t.hideAt) / (float) SLIDE_MS);
				if (f >= 1f || t.dismissed) {
					TOASTS.remove(i);
					i--;
					slot--;
					continue;
				}
				vis = 1f - f * f;
			}
			if (!setUp) {
				setUp = true;
				// the GUI projection for this frame (a screen or the HUD set it already;
				// set it again so a toast never draws under a stale world matrix)
				mc.entityRenderer.setupOverlayRendering();
				GlStateManager.disableDepth();
				GlStateManager.disableLighting();
				Gfx.defaultBlend();
			}
			float x = sw - W * vis;
			float y = slot * H;
			t.lastX = x;
			t.lastY = y;
			t.lastDrawn = now;
			Gfx g = new Gfx();
			g.pose().pushPose();
			try {
				g.pose().translate(x, y, 0);   // depth is off: drawn over everything
				t.draw(g, mc.fontRendererObj);
			} catch (Throwable ex) {
				// a broken draw just leaves the toast out; it still times out
			} finally {
				g.pose().popAll();
			}
		}
		if (setUp) {
			GlStateManager.enableDepth();
			Gfx.resetColor();
		}
	}

	/** The button this toast carries, or null (a plain message toast, or one already answered). */
	private String buttonLabel() {
		if (acted) {
			return null;
		}
		switch (kind) {
			case REQUEST:
				return "ACCEPT";
			case INVITE:
			case WARP:
				return "JOIN";
			default:
				return null;
		}
	}

	private int buttonW(FontRenderer font) {
		String b = buttonLabel();
		return b == null ? 0 : OriginText.widthBold(font, b) + 14;
	}

	private void draw(Gfx g, FontRenderer font) {
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

	/** SocialHooks' screen mouse hook: true if the click landed on a pop-up (and was handled). */
	public static boolean click(double mx, double my) {
		long now = System.currentTimeMillis();
		for (int i = Math.min(TOASTS.size(), MAX_VISIBLE) - 1; i >= 0; i--) {
			SocialToast t = TOASTS.get(i);
			if (t.dismissed || t.hideAt >= 0 || now - t.lastDrawn > 250 || Float.isNaN(t.lastX)) {
				continue;
			}
			double lx = mx - t.lastX, ly = my - t.lastY;
			if (lx < 0 || ly < 0 || lx >= W || ly >= H) {
				continue;
			}
			Minecraft mc = Minecraft.getMinecraft();
			int bw = t.buttonW(mc.fontRendererObj);
			if (bw > 0 && lx >= W - bw - 10 && lx < W - 6) {
				t.answer(mc);          // ACCEPT / JOIN: one click does the job
				return true;
			}
			t.dismissed = true;
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
		Minecraft mc = Minecraft.getMinecraft();
		long now = System.currentTimeMillis();
		for (int i = Math.min(TOASTS.size(), MAX_VISIBLE) - 1; i >= 0; i--) {   // newest first, and only toasts on screen right now
			SocialToast t = TOASTS.get(i);
			if (t.token.equals(token) && !t.dismissed && t.hideAt < 0 && !Float.isNaN(t.lastX) && now - t.lastDrawn <= 250) {
				int bw = t.buttonW(mc.fontRendererObj);
				return bw > 0 && click(t.lastX + W - bw / 2.0 - 8, t.lastY + H / 2.0);
			}
		}
		return false;
	}

	/** The toast's button: accept the request / party invite, or follow the warp. */
	private void answer(Minecraft mc) {
		switch (kind) {
			case WARP:
				dismissed = true;
				joinWarp(mc, warp);
				break;
			case REQUEST: {
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
				break;
			}
			case INVITE: {
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
				break;
			}
			default:
				break;
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
		result.thenAccept(err -> Minecraft.getMinecraft().addScheduledTask(() -> {
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
		} else if (mc.thePlayer != null) {
			mc.thePlayer.addChatMessage(new ChatComponentText(why));
		} else {
			Social.openFriends(FriendsScreen.PARTY);
		}
	}
}
