package com.origin.client.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * The frame every Origin window shares (Will, 2026-10-12: "all the other random
 * windows ... should also be changed to this new style"): the mod menu's panel,
 * header and motion, so FPS Coach, Cosmetics, Waypoints, the item pickers,
 * Quickplay, the shader browser and the minimap all read as one product.
 *
 * Layout is in OriginDeck design px (920 x 580 at 1080p, the mod menu's size):
 *
 *   +--------------------------------------------------------------+
 *   | [<]  [icon]  TITLE                          [header actions] [x] |  HEAD_H
 *   |              subtitle                                         |
 *   +--------------------------------------------------------------+
 *   |  body: BODY_X, BODY_Y, BODY_W, BODY_H  (M inset on every side) |
 *   +--------------------------------------------------------------+
 *
 * A window implements {@link #drawBody} (and optionally {@link #drawHeaderActions})
 * using the OriginDeck kit, recording click targets with d.hit(...) while it
 * draws -- the base replays them on click, so render and input share one
 * geometry. Esc / the back chevron / the close button all go to {@code returnTo}.
 * The panel honours the menu's "Solid Menu Background" setting like the mod menu;
 * with it off, OriginDeck's see-through mode keeps every word readable over the world.
 *
 * 1.17.1: drawn through this module's Gfx (PoseStack) backend like the mod menu;
 * Screen.render(PoseStack, ...) never paints a backdrop itself before 1.20.2, so
 * render() draws its own (title backdrop / MenuBlur) exactly as the mod menu does,
 * and mouseScrolled is the 3-arg form.
 */
public abstract class OriginWindow extends Screen {
	public static final float W = 920, H = 580, M = 24;
	public static final float HEAD_H = 64;
	public static final float BODY_X = M, BODY_Y = HEAD_H + M, BODY_W = W - 2 * M, BODY_H = H - HEAD_H - 2 * M;
	private static final long OPEN_MS = 180;

	protected final Screen returnTo;
	protected final List<OriginDeck.Hit> hits = new ArrayList<>();
	private final long openedAt = System.currentTimeMillis();
	private long closingAt = -1;
	private long lastFrameNanos = 0;
	protected float lastS = 1, lastOx = 0, lastOy = 0;

	/** Body scroll (design px), eased toward {@link #scrollTarget}; max set by the window. */
	protected double scroll = 0, scrollTarget = 0, maxScroll = 0;

	protected OriginWindow(Component title, Screen returnTo) {
		super(title);
		this.returnTo = returnTo;
	}

	/** Big header line. */
	protected abstract String heading();

	/** Smaller line under the heading ("" for none). */
	protected String subheading() {
		return "";
	}

	/** Mod-icon atlas id drawn before the heading (null for none). */
	protected String headingIcon() {
		return null;
	}

	/** Draws the window's content inside the body rect. */
	protected abstract void drawBody(OriginDeck d);

	/**
	 * Optional buttons at the right of the header, ending at {@code right} (the
	 * close button's left edge minus a gap). Use {@link #button} with y=16, h=32.
	 */
	protected void drawHeaderActions(OriginDeck d, float right) {
	}

	// ---- render ----

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
		if (Minecraft.getInstance().level == null) {
			com.origin.client.client.render.OriginScreenRenderer.renderTitleBackground(g);
		} else {
			com.origin.client.client.mods.MenuBlur.renderBehind(g, this, partialTick);
		}
		long now = System.currentTimeMillis();
		long nanos = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 16.7 : Math.min(50.0, (nanos - lastFrameNanos) / 1_000_000.0);
		lastFrameNanos = nanos;
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt / 60.0);

		float p = (float) OriginTheme.easeOut(Math.min(1.0, (now - openedAt) / (double) OPEN_MS));
		if (closingAt > 0) {
			double cp = Math.min(1.0, (now - closingAt) / (double) OPEN_MS);
			p = (float) OriginTheme.easeOut(1.0 - cp);
			if (cp >= 1.0) {
				Minecraft.getInstance().setScreen(returnTo);
				return;
			}
		}

		var win = Minecraft.getInstance().getWindow();
		float s = OriginDeck.scale(win.getWidth(), win.getHeight(), W, H);
		float ox = (win.getWidth() - W * s) / 2f;
		float oy = (win.getHeight() - H * s) / 2f + (1 - p) * 12 * s;
		lastS = s;
		lastOx = ox;
		lastOy = oy;
		OriginDeck d = new OriginDeck(g, s, ox, oy, mouseX, mouseY, hits);
		d.alpha = p;
		// "Solid Menu Background" off: no panel, so the kit lifts the text over the world
		d.seeThrough = OriginDeck.menuSeeThrough();
		drawShell(d);
		drawBody(d);
		d.end();
		renderOverlays(g, mouseX, mouseY, partialTick);
	}

	/** Anything drawn in plain GUI px above the deck (vanilla widgets, previews). */
	protected void renderOverlays(Gfx g, int mouseX, int mouseY, float partialTick) {
	}

	private void drawShell(OriginDeck d) {
		double op = Mods.metaNum("menuBgOpacity", 1.0);
		if (op > 0.02) {
			d.shadow(0, 0, W, H, 8);
			d.rect(0, 0, W, H, 8, mul(PANEL, (float) op), 0x12FFFFFF);
		}
		d.fill(1, HEAD_H, W - 2, 1, LINE);

		float x = M;
		boolean bh = d.over(x, 16, 32, 32);
		d.rect(x, 16, 32, 32, 6, bh ? 0x1AFFFFFF : 0x0AFFFFFF);
		d.icon("@chev-left", x + 9, 25, 14, bh ? TEXT : DIM);
		d.hit(x, 16, 32, 32, this::back);
		x += 32 + 14;

		String icon = headingIcon();
		if (icon != null) {
			d.icon(icon, x, HEAD_H / 2 - 12, 24, TEXT);
			x += 24 + 12;
		}
		String sub = subheading();
		if (sub == null || sub.isEmpty()) {
			d.textMid(heading(), x, HEAD_H / 2, 18, true, 0.4f, TEXT);
		} else {
			d.text(heading(), x, 30, 17, true, 0.3f, TEXT);
			d.text(sub, x, 48, 12, false, 0, DIM);
		}

		float cx = W - M - 32;
		boolean ch = d.over(cx, 16, 32, 32);
		d.rect(cx, 16, 32, 32, 6, ch ? 0x1AFFFFFF : 0x0AFFFFFF);
		d.icon("@close", cx + 9, 25, 14, ch ? TEXT : DIM);
		d.hit(cx, 16, 32, 32, this::beginClose);
		drawHeaderActions(d, cx - 8);
	}

	// ---- shared widgets (the mod menu's shapes) ----

	public enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

	/** Caps label button, the mod menu's EDIT HUD / OPTIONS shape. Returns its width. */
	protected float button(OriginDeck d, String label, String icon, float x, float y, float w, float h,
			Style style, boolean enabled, Runnable action) {
		boolean hov = enabled && d.over(x, y, w, h);
		int fill, fg;
		switch (style) {
			case PRIMARY -> {
				fill = hov ? 0xFF83E2B5 : ACC;
				fg = ON_ACC;
			}
			case DANGER -> {
				fill = hov ? 0x3DD9675A : RED_SOFT;
				fg = RED;
			}
			case GHOST -> {
				fill = hov ? 0x0AFFFFFF : 0x00000000;
				fg = hov ? TEXT : DIM;
			}
			default -> {
				fill = hov ? 0x1AFFFFFF : 0x0FFFFFFF;
				fg = hov ? TEXT : DIM;
			}
		}
		if (!enabled) {
			fill = 0x0AFFFFFF;
			fg = MUTE;
		}
		if ((fill >>> 24) != 0) {
			d.rect(x, y, w, h, 6, fill);
		}
		String caps = label.toUpperCase(java.util.Locale.ROOT);
		float tw = d.textW(caps, 11, true, CAPS_TRACK);
		float iw = icon == null ? 0 : 14 + 8;
		float tx = x + (w - tw - iw) / 2f;
		if (icon != null) {
			d.icon(icon, tx, y + h / 2 - 7, 14, fg);
			tx += iw;
		}
		d.textMid(caps, tx, y + h / 2, 11, true, CAPS_TRACK, fg);
		if (enabled && action != null) {
			d.hit(x, y, w, h, action);
		}
		return w;
	}

	/** Width a {@link #button} needs for this label (+ icon) with 14 px padding each side. */
	protected float buttonW(OriginDeck d, String label, String icon) {
		return 14 + (icon == null ? 0 : 22) + d.textW(label.toUpperCase(java.util.Locale.ROOT), 11, true, CAPS_TRACK) + 14;
	}

	/** A content card (the mod grid's card). */
	protected void card(OriginDeck d, float x, float y, float w, float h, boolean hover) {
		d.rect(x, y, w, h, 6, hover ? CARD_H : CARD, LINE);
	}

	/** Small all-caps section label. */
	protected void label(OriginDeck d, String text, float x, float baseline) {
		d.text(text.toUpperCase(java.util.Locale.ROOT), x, baseline, 11, true, CAPS_TRACK, MUTE);
	}

	/** A text / search field: dark well, placeholder when empty, caret when focused. */
	protected void field(OriginDeck d, float x, float y, float w, float h, String text, String placeholder,
			boolean focused, String icon) {
		d.rect(x, y, w, h, 6, FIELD, focused ? 0x40FFFFFF : LINE2);
		float tx = x + 12;
		if (icon != null) {
			d.icon(icon, tx, y + h / 2 - 7, 14, MUTE);
			tx += 22;
		}
		boolean empty = text == null || text.isEmpty();
		String shown = empty ? placeholder : d.fit(text, w - (tx - x) - 16, 13, false, 0);
		float end = tx + d.textMid(shown, tx, y + h / 2, 13, false, 0, empty ? MUTE : TEXT);
		if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
			d.fill(empty ? tx : end + 1, y + h / 2 - 8, 1.5f, 16, TEXT);
		}
	}

	/** Thin scrollbar at the body's right edge (no-op when nothing scrolls). */
	protected void scrollbar(OriginDeck d, float top, float bottom) {
		if (maxScroll <= 0) {
			return;
		}
		float trackH = bottom - top;
		float thumbH = Math.max(28, trackH * trackH / (float) (trackH + maxScroll));
		float t = (float) (scroll / maxScroll);
		d.rect(W - 10, top + (trackH - thumbH) * t, 3, thumbH, 1.5f, 0x33FFFFFF);
	}

	// ---- input ----

	protected float dX(double gui) {
		return OriginDeck.toDesign(gui, Minecraft.getInstance().getWindow().getGuiScale(), lastOx, lastS);
	}

	protected float dY(double gui) {
		return OriginDeck.toDesign(gui, Minecraft.getInstance().getWindow().getGuiScale(), lastOy, lastS);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (closingAt > 0) {
			return true;
		}
		if (button == 0 && OriginDeck.click(hits, dX(mx), dY(my))) {
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sy) {
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - sy * 48));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			back();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	/** Esc / back chevron: one level up. Windows with inner pages override this. */
	protected void back() {
		beginClose();
	}

	public void beginClose() {
		if (closingAt < 0) {
			closingAt = System.currentTimeMillis();
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
