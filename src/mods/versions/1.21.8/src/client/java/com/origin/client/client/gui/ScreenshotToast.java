package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.Window;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.ScreenshotPopup;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.ChatScreen;
import org.joml.Matrix3x2f;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// The Screenshot pop-up, as a vanilla toast: ToastManager slides it in at the
// top right, stacks it with other toasts and draws it above every screen. One
// toast at a time -- a new screenshot re-targets the visible one (fresh
// picture, fresh timer) instead of stacking.
//
// The toast never learns where it sits, so render() reads its on-screen origin
// from the pose ToastManager translated it by (1.21.8 ToastInstance.render
// translates to the slot, source-verified); the chat-screen click hook hit-tests
// against that same origin, so drawn and clickable buttons always line up.
//
// PER-VERSION (1.21.2+ Toast API, verified against the decompiled 1.21.8 Toast /
// ToastManager): drawing and the show/hide decision are split -- render(g, font,
// t) only draws; update(manager, t) runs once per toast-manager update and sets
// what getWantedVisibility() reports (1.21.1 returned it from render). The thumb
// blit takes RenderPipelines.GUI_TEXTURED first, and the pose is this era's 2D
// Matrix3x2f (its translation is m20/m21).
public class ScreenshotToast implements Toast {
	public static final Object TOKEN = new Object();
	private static final int W = 236, H = 64;
	private static final int THUMB_W = 64, THUMB_H = 36;
	private static final int BTN_Y = 46, BTN_H = 14, PAD = 6;
	private static final long STATUS_MS = 2500;

	private enum Action {
		COPY_IMAGE("Copy image", "Image", "copyImageButton"),
		COPY_FILE("Copy file", "File", "copyFileButton"),
		OPEN_FOLDER("Open folder", "Folder", "openFolderButton"),
		DELETE("Delete", "Delete", "deleteButton");

		final String label, shortLabel, optionKey;

		Action(String label, String shortLabel, String optionKey) {
			this.label = label;
			this.shortLabel = shortLabel;
			this.optionKey = optionKey;
		}
	}

	// The visible toast + where it was last drawn (GUI pixels), for click tests.
	private static volatile ScreenshotToast current = null;
	private static volatile String statusText = null;
	private static volatile long statusAt = 0;

	private File file;
	private int texW, texH;
	private long shownAt;
	private long heldAt = 0;
	private boolean hidden = false;
	private Visibility wanted = Visibility.SHOW;
	private float originX = -1, originY = -1;

	public ScreenshotToast(File file, int texW, int texH) {
		retarget(file, texW, texH);
	}

	/** Shows a newer screenshot in this toast and restarts its timer. */
	public void retarget(File file, int texW, int texH) {
		this.file = file;
		this.texW = texW;
		this.texH = texH;
		this.shownAt = Util.getMillis();
		this.hidden = false;
		this.wanted = Visibility.SHOW;
		statusText = null;
		current = this;
	}

	/** A short line in place of the hint ("Image copied"); safe from any thread. */
	public static void status(String text) {
		statusText = text;
		statusAt = Util.getMillis();
	}

	@Override
	public Object getToken() {
		return TOKEN;
	}

	@Override
	public int width() {
		return W;
	}

	@Override
	public int height() {
		return H;
	}

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager toasts, long timeSinceVisible) {
		long durationMs = (long) (Mods.num("screenshot", "duration") * 1000
				* toasts.getNotificationDisplayTimeMultiplier());
		boolean show = current == this && Mods.on("screenshot")
				&& Util.getMillis() - Math.max(shownAt, heldAt) < durationMs;
		hidden = !show;
		wanted = show ? Visibility.SHOW : Visibility.HIDE;
	}

	@Override
	public void render(GuiGraphics g, Font font, long timeSinceVisible) {
		Minecraft mc = Minecraft.getInstance();
		long now = Util.getMillis();
		Matrix3x2f pose = g.pose();
		originX = pose.m20();
		originY = pose.m21();
		boolean chat = mc.screen instanceof ChatScreen;
		double[] m = mouse(mc);
		boolean over = chat && m[0] >= originX && m[0] < originX + W && m[1] >= originY && m[1] < originY + H;
		if (over) {
			heldAt = now;   // hovering with chat open keeps it up
		}

		try {
			OriginUi.panel(g, 0, 0, W, H, 8, 0xF0121614, OriginTheme.STROKE_STRONG);
			// picture
			OriginUi.panel(g, PAD - 1, PAD - 1, THUMB_W + 2, THUMB_H + 2, 3, 0xFF000000, OriginTheme.STROKE);
			if (texW > 0 && texH > 0) {
				g.blit(RenderPipelines.GUI_TEXTURED, ScreenshotPopup.THUMB, PAD, PAD, 0f, 0f, THUMB_W, THUMB_H, texW, texH, texW, texH);
			}
			// text
			int tx = PAD + THUMB_W + 8, tw = W - tx - PAD;
			OriginText.drawBold(g, font, "Screenshot saved", tx, PAD + 1, OriginTheme.TEXT, false);
			OriginText.draw(g, font, OriginText.ellipsize(font, file.getName(), tw), tx, PAD + 13, OriginTheme.TEXT_DIM, false);
			String status = statusText != null && now - statusAt < STATUS_MS ? statusText : null;
			String hint = status != null ? status
					: chat ? "Click an action"
					: "Open chat (" + mc.options.keyChat.getTranslatedKeyMessage().getString() + ") to use";
			OriginText.draw(g, font, OriginText.ellipsize(font, hint, tw), tx, PAD + 25,
					status != null ? OriginTheme.ACCENT : OriginTheme.MUTED, false);
			// buttons
			List<Action> actions = actions();
			for (int i = 0; i < actions.size(); i++) {
				int[] r = buttonRect(i, actions.size());
				boolean hover = chat && m[0] >= originX + r[0] && m[0] < originX + r[2]
						&& m[1] >= originY + r[1] && m[1] < originY + r[3];
				OriginUi.panel(g, r[0], r[1], r[2] - r[0], r[3] - r[1], 4,
						hover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
						hover ? OriginTheme.BOX_BORDER_HOVER : OriginTheme.BOX_BORDER);
				Action a = actions.get(i);
				String label = OriginText.width(font, a.label) <= r[2] - r[0] - 6 ? a.label : a.shortLabel;
				OriginText.draw(g, font, label, r[0] + (r[2] - r[0] - OriginText.width(font, label)) / 2,
						r[1] + 3, chat ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, false);
			}
		} catch (Throwable t) {
			// a broken draw just leaves the toast plain; it still times out
		}
	}

	private static List<Action> actions() {
		List<Action> out = new ArrayList<>();
		for (Action a : Action.values()) {
			if (Mods.bool("screenshot", a.optionKey)) {
				out.add(a);
			}
		}
		return out;
	}

	// Buttons share the bottom row evenly; rect is relative to the toast origin.
	private static int[] buttonRect(int i, int n) {
		int gap = 4;
		int bw = (W - 2 * PAD - gap * (n - 1)) / Math.max(1, n);
		int x = PAD + i * (bw + gap);
		return new int[]{x, BTN_Y, x + bw, BTN_Y + BTN_H};
	}

	private static double[] mouse(Minecraft mc) {
		Window w = mc.getWindow();
		return new double[]{
				mc.mouseHandler.xpos() * w.getGuiScaledWidth() / (double) Math.max(1, w.getScreenWidth()),
				mc.mouseHandler.ypos() * w.getGuiScaledHeight() / (double) Math.max(1, w.getScreenHeight())};
	}

	/** Chat-screen click hook: true = the click hit the toast and was handled. */
	public static boolean mouseClicked(double mx, double my, int button) {
		ScreenshotToast t = current;
		if (t == null || t.hidden || t.originX < 0 || button != 0) {
			return false;
		}
		double lx = mx - t.originX, ly = my - t.originY;
		if (lx < 0 || lx >= W || ly < 0 || ly >= H) {
			return false;
		}
		List<Action> actions = actions();
		for (int i = 0; i < actions.size(); i++) {
			int[] r = buttonRect(i, actions.size());
			if (lx >= r[0] && lx < r[2] && ly >= r[1] && ly < r[3]) {
				t.heldAt = Util.getMillis();
				switch (actions.get(i)) {
					case COPY_IMAGE -> ScreenshotPopup.copyImage(t.file);
					case COPY_FILE -> ScreenshotPopup.copyFile(t.file);
					case OPEN_FOLDER -> ScreenshotPopup.openFolder(t.file);
					case DELETE -> ScreenshotPopup.delete(t.file);
				}
				return true;
			}
		}
		return true;   // a click on the toast body never falls through to chat
	}
}
