package com.origin.client.client.gui;

import com.origin.client.client.mods.FpsCoach;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

// FPS Coach's one-line nudge, as a vanilla toast (top right, stacks with other
// toasts, slides in and out on its own) in the same Origin panel look as the
// Screenshot pop-up. It only says how many tips there are and which key opens
// them -- it never changes anything itself. One at a time: a new nudge while
// one is showing just updates its count.
//
// PER-VERSION DELTA (1.21.11 Toast contract, as ScreenshotToast on this
// module): the visibility decision lives in update(ToastManager, long) +
// getWantedVisibility(), render(GuiGraphics, Font, long) only draws, and the
// queue is Minecraft.getToastManager().
public class FpsCoachToast implements Toast {
	private static final Object TOKEN = new Object();
	private static final int W = 190, H = 32;
	private static final long SHOW_MS = 8_000;

	private int count;
	private String key;
	private boolean restart = false;

	private FpsCoachToast(int count, String key) {
		this.count = count;
		this.key = key;
	}

	public static void show(Minecraft mc, int count, String key) {
		FpsCoachToast t = mc.getToastManager().getToast(FpsCoachToast.class, TOKEN);
		if (t != null) {
			t.count = count;
			t.key = key;
			t.restart = true;
		} else {
			mc.getToastManager().addToast(new FpsCoachToast(count, key));
		}
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

	private long shownFrom = 0;
	private boolean hidden = false;

	// The time-out (1.21.1 did this at the end of render). Runs every frame,
	// before render, from ToastManager.update.
	@Override
	public void update(ToastManager toasts, long timeSinceVisible) {
		if (restart) {
			restart = false;
			shownFrom = timeSinceVisible;
		}
		long shown = timeSinceVisible - shownFrom;
		// Gone once the player opens the coach (the nudge did its job).
		boolean show = Mods.on(FpsCoach.ID)
				&& shown < SHOW_MS * toasts.getNotificationDisplayTimeMultiplier()
				&& !(Minecraft.getInstance().screen instanceof FpsCoachScreen);
		hidden = !show;
	}

	@Override
	public Visibility getWantedVisibility() {
		return hidden ? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public void render(GuiGraphics g, Font font, long timeSinceVisible) {
		try {
			OriginUi.panel(g, 0, 0, W, H, 8, 0xF0121614, OriginTheme.STROKE_STRONG);
			OriginUi.logo(g, 16, 16, 16, 1f);
			String title = "FPS Coach has " + count + (count == 1 ? " tip" : " tips");
			String hint = key != null ? "Press " + key + " to see " + (count == 1 ? "it" : "them")
					: "Open it from the Origin mod menu";
			OriginText.drawBold(g, font, OriginText.ellipsize(font, title, W - 38), 30, 7, OriginTheme.TEXT, false);
			OriginText.draw(g, font, OriginText.ellipsize(font, hint, W - 38), 30, 19, OriginTheme.TEXT_DIM, false);
		} catch (Throwable t) {
			// a broken draw just leaves the toast plain; it still times out
		}
	}
}
