package com.origin.client.client.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

// Temporary debug corner for the M3 checkpoint: confirms whether a real
// anti-aliased atlas + forced GL_LINEAR filtering (no custom shader) reads
// as smooth live, or whether DESIGN_SYSTEM.md's structural-ceiling
// hypothesis holds and a shader-based renderer (M4) is required instead.
// Delete this class and its HudRenderCallback registration in
// OriginClientMod once M5 replaces OriginHud with the confirmed renderer.
public final class OriginFontDebugOverlay {
	private OriginFontDebugOverlay() {
	}

	public static void render(GuiGraphics guiGraphics) {
		if (Minecraft.getInstance().options.hideGui) {
			return;
		}
		int x = 6;
		int y = 40;
		// Actual planned HUD-row size/weight (DESIGN_SYSTEM.md §2) -- the one
		// that matters most for the M3 checkpoint.
		OriginFont.drawString(guiGraphics, "COORDS  142, 74, -308", x, y, 400, 11f);
		OriginFont.drawString(guiGraphics, "PING  12ms", x, y + 14, 400, 11f);
		// A modest heading size/weight, not an exaggerated bold test.
		OriginFont.drawString(guiGraphics, "Origin Font Test", x, y + 32, 600, 16f);
		OriginFont.drawString(guiGraphics, "abcdefghijklmnopqrstuvwxyz", x, y + 54, 400, 14f);
		OriginFont.drawString(guiGraphics, "0123456789 !@#$%^&*()", x, y + 72, 500, 14f);
	}
}
