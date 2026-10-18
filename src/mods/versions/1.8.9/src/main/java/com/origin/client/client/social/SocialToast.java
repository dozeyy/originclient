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
 * friend (or the party); a warp toast carries a JOIN button.
 *
 * 1.8.9 has no toast system (only the achievement pop-up, GuiAchievement), so
 * this class IS the toast stack, rebuilt on 1.16.5's ToastComponent rules:
 * up to {@link #MAX_VISIBLE} at a time from the top right, each sliding in over
 * 600 ms (squared ease, vanilla's), shown {@link #SHOW_MS}, sliding out over
 * 600 ms; a second DM from the same friend updates the visible toast instead of
 * stacking. SocialHooks draws it at the END of every render tick -- after the
 * HUD, any screen and its widgets, like vanilla's toasts are -- and asks
 * {@link #click} first on every screen mouse click, so a toast takes clicks
 * wherever the mouse is free (any menu, or the chat box in game). The draw
 * position is computed here, so click hit-testing needs no GL read-back.
 */
public final class SocialToast {
	private static final int W = 220, H = 32;
	private static final long SHOW_MS = 7_000;
	private static final long SLIDE_MS = 600;
	private static final int JOIN_W = 42;
	private static final int MAX_VISIBLE = 5;

	private enum Kind { MESSAGE, REQUEST, INVITE, WARP }

	/** Every toast, oldest first; the first MAX_VISIBLE show, the rest wait. Client thread only. */
	private static final List<SocialToast> TOASTS = new ArrayList<>();

	private final Kind kind;
	private final String token;
	private final String uuid;
	private String title, line;
	private final SocialState.Warp warp;
	private boolean dismissed = false;
	/** When it first showed / was last refreshed / started sliding out (ms; -1 = not yet). */
	private long visibleAt = -1, shownFrom = -1, hideAt = -1;
	private float lastX = Float.NaN, lastY;
	private long lastDrawn = 0;

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
		for (SocialToast old : TOASTS) {
			if (old.token.equals(t.token) && !old.dismissed && old.hideAt < 0) {
				old.title = t.title;
				old.line = t.line;
				if (old.visibleAt >= 0) {
					old.shownFrom = System.currentTimeMillis();
				}
				return;
			}
		}
		TOASTS.add(t);
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
			if (t.hideAt < 0 && (t.dismissed || now - t.shownFrom >= SHOW_MS)) {
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

	private void draw(Gfx g, FontRenderer font) {
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
			OriginUi.panel(g, bx, 8, JOIN_W, 16, 5, OriginTheme.ACCENT, 0);
			int tw = OriginText.widthBold(font, "JOIN");
			OriginText.drawBold(g, font, "JOIN", bx + (JOIN_W - tw) / 2, 12, com.origin.client.client.gui.OriginDeck.ON_ACC, false);
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
			t.dismissed = true;
			Minecraft mc = Minecraft.getMinecraft();
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
		} else if (mc.thePlayer != null) {
			mc.thePlayer.addChatMessage(new ChatComponentText(why));
		} else {
			Social.openFriends(FriendsScreen.PARTY);
		}
	}
}
