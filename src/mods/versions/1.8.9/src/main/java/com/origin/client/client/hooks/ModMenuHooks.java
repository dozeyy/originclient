package com.origin.client.client.hooks;

import com.origin.client.client.OriginKeyBindings;
import com.origin.client.client.gui.OriginScreen;
import com.origin.client.client.hud.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

/**
 * The mod-menu area's Forge hooks: the Right Shift key opens the Right Shift
 * screen (HudEditorScreen in quick mode -- the HUD editor plus the centre group
 * Settings / Waypoints / MODS / Cosmetics / Friends). The screens themselves
 * close on the same key or Esc.
 *
 * 1.8.9: InputEvent.KeyInputEvent (FML bus) fires right after vanilla has
 * updated the key bindings for a keyboard event in the world (no screen open),
 * so the press is consumed in the same frame it happened -- the per-tick poll
 * the other versions use (OriginClientMod's end tick) would wait for the next
 * tick. Both are safe together: isPressed() CONSUMES the press, so whichever
 * runs first opens the screen once and the other sees nothing. MouseInputEvent
 * covers a bind moved onto a mouse button (1.8.9 binds mouse buttons as -100+b).
 */
public class ModMenuHooks {
	public ModMenuHooks() {
	}

	@SubscribeEvent
	public void onKeyInput(InputEvent.KeyInputEvent event) {
		openIfPressed();
	}

	@SubscribeEvent
	public void onMouseInput(InputEvent.MouseInputEvent event) {
		openIfPressed();
	}

	private static void openIfPressed() {
		try {
			Minecraft mc = Minecraft.getMinecraft();
			KeyBinding key = OriginKeyBindings.openModMenu;
			if (mc == null || key == null || mc.currentScreen != null || mc.thePlayer == null) {
				return;
			}
			boolean pressed = false;
			while (key.isPressed()) {   // drain queued presses: one press, one screen
				pressed = true;
			}
			// a screen class that already failed this session stays closed (vanilla floor)
			if (pressed && !OriginScreen.isBroken(HudEditorScreen.class)) {
				mc.displayGuiScreen(new HudEditorScreen(true));
			}
		} catch (Throwable t) {
			// fail soft: Right Shift does nothing rather than crash the game
		}
	}
}
