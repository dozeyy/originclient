package com.origin.client.client;

import com.origin.client.client.gui.Gfx;

// Thin dispatcher kept for compatibility: the real HUD system (anchored,
// movable, per-mod elements) lives in hud/HudElements.
public final class OriginHud {
	private OriginHud() {
	}

	public static void render(Gfx guiGraphics) {
		com.origin.client.client.hud.HudElements.renderAll(guiGraphics);
	}
}
