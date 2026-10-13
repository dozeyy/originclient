package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

// Applies the SETTINGS > General options that need to run against the live
// window every tick. Edge-driven and fully fail-soft -- a window hiccup is
// swallowed so a bad window state can never crash the game.
//
// 1.8.9 (LWJGL 2, no GLFW): Borderless Fullscreen is the LWJGL 2 recipe the
// 1.8.9 clients use -- the "org.lwjgl.opengl.Window.undecorated" system
// property is read when Display (re)creates its window, and
// Display.setDisplayMode re-creates it, so: property on, a desktop-sized mode,
// window at (0,0), then Minecraft.resize so the framebuffers follow. Off puts
// the decorations and the saved windowed size back the same way.
// Raw Mouse Input has no LWJGL 2 switch (its Windows mouse already reads
// DirectInput deltas), so that row is not offered on 1.8.9 (see Mods).
public final class GeneralSettings {
	private static final String UNDECORATED = "org.lwjgl.opengl.Window.undecorated";

	// Edge-triggered so we never fight the user's own window dragging: we only
	// act when the toggle's value actually changes.
	private static Boolean lastBorderless = null;
	private static boolean borderlessApplied = false;
	private static int savedX, savedY, savedW = 854, savedH = 480;

	private GeneralSettings() {
	}

	static void tick(Minecraft mc) {
		applyBorderless(mc);
	}

	private static void applyBorderless(Minecraft mc) {
		boolean want = Mods.bool(Mods.GENERAL_ID, "borderlessFullscreen");
		if (lastBorderless != null && lastBorderless == want) {
			return; // only act on a real change
		}
		lastBorderless = want;
		// Never fight vanilla's exclusive fullscreen (F11): let that own the
		// window; borderless applies from the windowed state only.
		if (want && !borderlessApplied) {
			if (mc.isFullScreen()) {
				lastBorderless = null; // retry once we're back in windowed mode
				return;
			}
			try {
				savedX = Display.getX();
				savedY = Display.getY();
				savedW = Math.max(320, mc.displayWidth);
				savedH = Math.max(240, mc.displayHeight);
				DisplayMode desktop = Display.getDesktopDisplayMode();
				if (desktop == null) {
					return;
				}
				System.setProperty(UNDECORATED, "true");
				Display.setDisplayMode(new DisplayMode(desktop.getWidth(), desktop.getHeight()));
				Display.setLocation(0, 0);
				mc.resize(desktop.getWidth(), desktop.getHeight());
				borderlessApplied = true;
			} catch (Throwable t) {
				System.setProperty(UNDECORATED, "false");
			}
		} else if (!want && borderlessApplied) {
			try {
				System.setProperty(UNDECORATED, "false");
				Display.setDisplayMode(new DisplayMode(savedW, savedH));
				Display.setLocation(savedX, savedY);
				Display.setResizable(true);
				mc.resize(savedW, savedH);
				borderlessApplied = false;
			} catch (Throwable t) {
				// fail-soft
			}
		}
	}
}
