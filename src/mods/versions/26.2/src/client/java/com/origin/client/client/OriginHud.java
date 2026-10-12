package com.origin.client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

// Thin dispatcher kept for compatibility: the real HUD system (anchored,
// movable, per-mod elements) lives in hud/HudElements.
public final class OriginHud {
	private OriginHud() {
	}

	public static void extractRenderState(GuiGraphicsExtractor guiGraphics) {
		com.origin.client.client.hud.HudElements.renderAll(guiGraphics);
	}
}
