package com.origin.client.client.gui;

import com.origin.client.client.mods.FpsCoach;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

// FPS Coach's one-line nudge: a small card in the top-right corner that slides
// in and out on its own, in the same Origin panel look as every other version's
// toast. It only says how many tips there are and which key opens them -- it
// never changes anything itself. One at a time: a new nudge while one is
// showing just updates its count and restarts its time.
//
// 1.8.9 has no toast system (ToastComponent is 1.12+; GuiAchievement only draws
// achievements), so the card draws itself: ServerHooks calls render() at the end
// of every frame (Forge RenderTickEvent END), after the HUD and any open screen,
// on top of everything like a modern toast. setupOverlayRendering() puts the GUI
// projection back (and clears depth) first, as vanilla does before its own GUI pass.
public final class FpsCoachToast {
	private static final int W = 190, H = 32, MARGIN = 4;
	private static final long SHOW_MS = 8_000;
	private static final long SLIDE_MS = 250;

	private static int count;
	private static String key;
	private static long shownAt = -1;   // -1 = no toast

	private FpsCoachToast() {
	}

	public static void show(Minecraft mc, int tips, String keyName) {
		count = tips;
		key = keyName;
		long now = Minecraft.getSystemTime();
		// Already on screen: keep it in place (no second slide-in), just restart its time.
		shownAt = shownAt >= 0 && now - shownAt < SHOW_MS ? now - SLIDE_MS : now;
	}

	/** End of every frame: draws the card while it's showing. */
	public static void render() {
		if (shownAt < 0) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		long shown = Minecraft.getSystemTime() - shownAt;
		// Gone once its time is up, the coach is opened (the nudge did its job),
		// or the mod is switched off.
		if (shown >= SHOW_MS || !Mods.on(FpsCoach.ID) || mc.currentScreen instanceof FpsCoachScreen) {
			shownAt = -1;
			return;
		}
		if (mc.gameSettings.hideGUI && mc.currentScreen == null) {
			return;   // F1: nothing over the view, like the rest of the HUD
		}
		// Ease-out slide in from the right edge, ease-in slide back out at the end.
		float in = Math.min(1f, shown / (float) SLIDE_MS);
		float out = Math.min(1f, Math.max(0f, (SHOW_MS - shown) / (float) SLIDE_MS));
		float t = Math.min(in, out);
		float eased = 1f - (1f - t) * (1f - t) * (1f - t);
		try {
			mc.entityRenderer.setupOverlayRendering();
			ScaledResolution sr = Gfx.scaled();
			int x = Math.round(sr.getScaledWidth() - (W + MARGIN) * eased);
			GlStateManager.disableLighting();
			GlStateManager.enableBlend();
			Gfx g = new Gfx();
			g.pose().pushPose();
			g.pose().translate(x, MARGIN, 0);
			try {
				draw(g, mc.fontRendererObj);
			} finally {
				g.pose().popPose();
			}
		} catch (Throwable th) {
			// a broken draw just leaves the frame as it was; the card still times out
		}
	}

	private static void draw(Gfx g, FontRenderer font) {
		OriginUi.panel(g, 0, 0, W, H, 8, 0xF0121614, OriginTheme.STROKE_STRONG);
		OriginUi.logo(g, 16, 16, 16, 1f);
		String title = "FPS Coach has " + count + (count == 1 ? " tip" : " tips");
		String hint = key != null ? "Press " + key + " to see " + (count == 1 ? "it" : "them")
				: "Open it from the Origin mod menu";
		OriginText.drawBold(g, font, OriginText.ellipsize(font, title, W - 38), 30, 7, OriginTheme.TEXT, false);
		OriginText.draw(g, font, OriginText.ellipsize(font, hint, W - 38), 30, 19, OriginTheme.TEXT_DIM, false);
	}
}
