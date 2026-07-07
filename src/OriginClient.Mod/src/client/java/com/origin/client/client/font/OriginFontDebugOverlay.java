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
		OriginFont.drawString(guiGraphics, "ORIGIN FONT TEST", x, y, 700, 22f);
		OriginFont.drawString(guiGraphics, "COORDS  142, 74, -308", x, y + 28, 400, 14f);
		OriginFont.drawString(guiGraphics, "abcdefghijklmnopqrstuvwxyz", x, y + 50, 400, 14f);
		OriginFont.drawString(guiGraphics, "0123456789 !@#$%^&*()", x, y + 68, 500, 14f);
	}
}
