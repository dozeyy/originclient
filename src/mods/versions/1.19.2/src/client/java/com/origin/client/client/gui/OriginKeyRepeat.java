package com.origin.client.client.gui;

import com.origin.client.client.packs.ResourcePackHubScreen;
import com.origin.client.client.shaders.ShaderBrowserScreen;
import com.origin.client.client.waypoints.WaypointScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;

/**
 * Held Backspace / arrow keys repeat inside Origin's text fields (pre-1.19.3 only).
 *
 * <p>Up to 1.19.2, {@code KeyboardHandler.keyPress} forwards GLFW REPEAT events to a
 * screen's {@code keyPressed} only while {@code sendRepeatsToGui} is on. Vanilla's own
 * text screens (chat, sign, anvil, book) switch it on in {@code init} and off in
 * {@code removed}; without that, holding Backspace deletes one letter and stops, while
 * typed letters still repeat (the GLFW char callback ignores the flag). 1.19.3 removed
 * the flag and always forwards repeats, which is what 1.21.1 (the design truth) does,
 * so 1.19.3+ modules have no copy of this class.
 *
 * <p>One Fabric screen hook instead of edits in every screen: the screens listed in
 * {@link #hasTextInput} are the ones with a {@code charTyped} text field. Their key
 * handlers already tolerate repeats because the same code runs on 1.21.1 (e.g. the
 * mod menu ignores repeats of its open key). The HUD editor is deliberately left out:
 * it has no text field and closes on a key press. Fail soft: if the flag can't be set
 * the screen just behaves as before (single-step Backspace).
 */
public final class OriginKeyRepeat {
	private OriginKeyRepeat() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!hasTextInput(screen)) {
				return;
			}
			set(client, true);
			// removed() runs before the next screen's init, so a vanilla text screen
			// opened next still switches the flag back on for itself.
			ScreenEvents.remove(screen).register(s -> set(client, false));
		});
	}

	private static boolean hasTextInput(Screen screen) {
		return screen instanceof OriginModMenuScreen
				|| screen instanceof OriginItemGridScreen
				|| screen instanceof FriendsScreen
				|| screen instanceof ResourcePackHubScreen
				|| screen instanceof ShaderBrowserScreen
				|| screen instanceof WaypointScreen;
	}

	private static void set(net.minecraft.client.Minecraft client, boolean on) {
		try {
			client.keyboardHandler.setSendRepeatsToGui(on);
		} catch (Throwable ignored) {
			// never let a key-repeat nicety break opening or closing a screen
		}
	}
}
