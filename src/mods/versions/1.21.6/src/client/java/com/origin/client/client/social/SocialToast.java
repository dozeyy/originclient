package com.origin.client.client.social;

import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.gui.OriginText;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Friends pop-ups: a DM, a friend request, a party invite, the leader's warp.
 * A vanilla toast (top right, stacks, slides in and out on its own) in the
 * Origin panel look the FPS Coach nudge uses. Clicking one opens the Friends
 * window on that friend (or the party); a warp toast carries a JOIN button.
 *
 * Vanilla toasts take no clicks, so each one remembers where it was last drawn
 * (the translation ToastManager applied to the pose) and Social's screen
 * mouse hook asks {@link #click} first. That works wherever the mouse is free
 * -- any menu, or the chat box in game.
 *
 * PER-VERSION (1.21.2+ Toast API, as FpsCoachToast): drawing and the show/hide
 * decision are split -- render(g, font, t) only draws; update(manager, t) sets
 * what getWantedVisibility() reports. The stack is Minecraft.getToastManager().
 * g.pose() is a Matrix3x2fStack here, so the toast's place is its m20/m21.
 */
public final class SocialToast implements Toast {
	private static final int W = 220, H = 32;
	private static final long SHOW_MS = 7_000;
	private static final int JOIN_W = 42;

	private enum Kind { MESSAGE, REQUEST, INVITE, WARP }

	/** Toasts drawn in the last moment, newest last (for click hit-testing). */
	private static final List<SocialToast> LIVE = new CopyOnWriteArrayList<>();

	private final Kind kind;
	private final String token;
	private final String uuid;
	private String title, line;
	private final SocialState.Warp warp;
	private boolean restart = false, dismissed = false;
	private long shownFrom = 0;
	private volatile float lastX = Float.NaN, lastY;
	private volatile long lastDrawn = 0;

	private SocialToast(Kind kind, String token, String uuid, String title, String line, SocialState.Warp warp) {
		this.kind = kind;
		this.token = token;
		this.uuid = uuid;
		this.title = title;
		this.line = line;
		this.warp = warp;
	}

	// ---- the four pop-ups ----

	public static void message(Minecraft mc, String uuid, String name, String text) {
		show(mc, new SocialToast(Kind.MESSAGE, "dm:" + uuid, uuid, name, text, null));
	}

	public static void request(Minecraft mc, String uuid, String name) {
		show(mc, new SocialToast(Kind.REQUEST, "req:" + uuid, uuid, "Friend request", name + " wants to be friends", null));
	}

	public static void invite(Minecraft mc, String uuid, String name) {
		show(mc, new SocialToast(Kind.INVITE, "inv:" + uuid, uuid, "Party invite", name + " invited you to their party", null));
	}

	public static void warp(Minecraft mc, String uuid, String name, SocialState.Warp w) {
		String where = w.server() == null ? "their server" : w.server();
		show(mc, new SocialToast(Kind.WARP, "warp", uuid, name + " is warping the party", "to " + where, w));
	}

	/** A second DM from the same friend updates the visible toast instead of stacking. */
	private static void show(Minecraft mc, SocialToast t) {
		SocialToast old = mc.getToastManager().getToast(SocialToast.class, t.token);
		if (old != null && !old.dismissed) {
			old.title = t.title;
			old.line = t.line;
			old.restart = true;
			return;
		}
		mc.getToastManager().addToast(t);
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

	private Visibility wanted = Visibility.SHOW;

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager toasts, long timeSinceVisible) {
		if (restart) {
			restart = false;
			shownFrom = timeSinceVisible;
		}
		long shown = timeSinceVisible - shownFrom;
		boolean show = !dismissed && shown < SHOW_MS * toasts.getNotificationDisplayTimeMultiplier();
		if (!show) {
			LIVE.remove(this);
		}
		wanted = show ? Visibility.SHOW : Visibility.HIDE;
	}

	@Override
	public void render(GuiGraphics g, Font font, long timeSinceVisible) {
		try {
			// remember where ToastManager put us (GUI px) for click()
			var m = g.pose();
			lastX = m.m20();
			lastY = m.m21();
			lastDrawn = System.currentTimeMillis();
			if (!LIVE.contains(this)) {
				LIVE.add(this);
			}
			draw(g, font);
		} catch (Throwable t) {
			// a broken draw just leaves the toast plain; it still times out
		}
	}

	private void draw(GuiGraphics g, Font font) {
		OriginUi.panel(g, 0, 0, W, H, 8, 0xF0121614, OriginTheme.STROKE_STRONG);
		if (uuid != null && !uuid.isEmpty()) {
			SocialSkins.face(g, uuid, 8, 8, 16);
		} else {
			OriginUi.logo(g, 16, 16, 16, 1f);
		}
		int textW = W - 38 - (kind == Kind.WARP ? JOIN_W + 6 : 0);
		OriginText.drawBold(g, font, OriginText.ellipsize(font, title, textW), 30, 7, OriginTheme.TEXT, false);
		OriginText.draw(g, font, OriginText.ellipsize(font, line, textW), 30, 19, OriginTheme.TEXT_DIM, false);
		if (kind == Kind.WARP) {
			int bx = W - JOIN_W - 8;
			OriginUi.panel(g, bx, 8, JOIN_W, 16, 5, 0xFF5FD09B, 0);
			int tw = OriginText.widthBold(font, "JOIN");
			OriginText.drawBold(g, font, "JOIN", bx + (JOIN_W - tw) / 2, 12, 0xFF0A1710, false);
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
			t.dismissed = true;
			LIVE.remove(t);
			Minecraft mc = Minecraft.getInstance();
			if (t.kind == Kind.WARP && lx >= W - JOIN_W - 8) {
				joinWarp(mc, t.warp);
			} else {
				Social.openFriends(t.kind == Kind.INVITE || t.kind == Kind.WARP ? FriendsScreen.PARTY
						: t.kind == Kind.REQUEST ? FriendsScreen.REQUESTS : t.uuid);
			}
			return true;
		}
		return false;
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
			mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(why), false);
		} else {
			Social.openFriends(FriendsScreen.PARTY);
		}
	}
}
