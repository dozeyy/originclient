package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.TextInputManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 26.3 only: keeps SDL3 text input switched on while an Origin screen is open.
 *
 * WHY: SDL3 sends typed characters (SDL_EVENT_TEXT_INPUT -> charTyped) only while
 * text input is started for the window. 26.3's TextInputManager starts it for the
 * widget that has focus (a vanilla EditBox does that itself), but Origin's own
 * fields (Mod Menu search, option search and text rows, waypoint name, shader /
 * pack / item search, Friends chat) are drawn by the screen and read charTyped
 * directly, so nothing started it: typed letters never arrived on 26.3 (Backspace
 * and Enter still did; those are key events). GLFW versions deliver characters
 * unconditionally, so they need none of this.
 *
 * Called once a client tick (OriginClientMod.onEndTick): while the Mod Menu or any
 * OriginWindow is open, text input is started with that screen as its owner, and
 * started again if something stopped it; when that screen goes, it is stopped for
 * that owner (a vanilla EditBox's own text input is left alone). Fail-soft: if
 * anything here throws, the screens work as before, without typed letters.
 */
public final class OriginTextFocus {
	private static Screen owner;
	private static java.lang.reflect.Field enabledField;
	private static boolean noField;

	private OriginTextFocus() {
	}

	public static void tick(Minecraft mc) {
		try {
			Screen s = mc.gui.screen();
			boolean wants = s instanceof OriginModMenuScreen || s instanceof OriginWindow;
			TextInputManager tim = mc.textInputManager();
			if (owner != null && owner != s) {
				tim.stopTextInput(owner);
				owner = null;
			}
			if (wants && (owner != s || !enabled(tim))) {
				tim.startTextInput(s);
				owner = s;
			}
		} catch (Throwable t) {
			owner = null;
		}
	}

	/** TextInputManager.textInputEnabled (private; 26.x is unobfuscated, so the name holds).
	 *  Unreadable means "assume still on": the screen then starts it once, on open. */
	private static boolean enabled(TextInputManager tim) {
		if (noField) {
			return true;
		}
		try {
			if (enabledField == null) {
				enabledField = TextInputManager.class.getDeclaredField("textInputEnabled");
				enabledField.setAccessible(true);
			}
			return enabledField.getBoolean(tim);
		} catch (ReflectiveOperationException | RuntimeException e) {
			noField = true;
			return true;
		}
	}
}
