package com.origin.client.client.render;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * The world-join / progress screens of this version (BMOD-4): connecting, generating or
 * receiving the world, progress and message screens. Vanilla caps the game at 60 FPS while
 * no world is loaded; Origin lifts that cap to the player's Max Framerate on real MENUS
 * only, so these keep vanilla's 60 (more frames there only compete with the world loading
 * on the same CPU). Per module: the list is exactly the screens this version's
 * mixin/loading draws Origin's loading picture over.
 */
public final class LoadingScreens {
	private LoadingScreens() {
	}

	/** True when {@code s} is a loading screen, not a menu (null = no screen = false). */
	public static boolean isLoading(Screen s) {
		return s instanceof ConnectScreen
				|| s instanceof GenericMessageScreen
				|| s instanceof LevelLoadingScreen
				|| s instanceof ProgressScreen;
	}
}
