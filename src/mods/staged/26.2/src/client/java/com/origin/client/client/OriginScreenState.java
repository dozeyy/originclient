package com.origin.client.client;

import net.minecraft.client.gui.screens.Screen;

// 26.2 removed the public `Minecraft.screen` field (screen management was
// restructured in the render-state overhaul — there is no field/getter named
// `screen` anymore). Origin's tick-gating + HUD checks still need "what screen is
// open", so MinecraftScreenTrackerMixin captures it from setScreenAndShow(...) and
// stashes it here. `current` is null when no screen is open (in-world).
public final class OriginScreenState {
	public static volatile Screen current = null;

	private OriginScreenState() {
	}
}
