package com.origin.client.client.mixin;

import com.origin.client.client.util.KeyTaps;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds util/KeyTaps (G121-7): every key PRESS (it counts those made with no screen open), so Origin's
// toggle keys catch a tap shorter than a client tick. Observes only (HEAD, no cancel):
// vanilla's key handling is untouched. Target: the 1.21.9+ shape
// private void keyPress(long window, int action, KeyEvent event) (javap 1.21.10 / 1.21.11,
// 2026-11-04), which 26.x keeps. A shape change fails the apply in boot-smoke, never the game.
// Also tells KeyTaps whether F3 was held when the key went down (BMOD-1): F3+B / F3+G ...
// are vanilla's debug combos, so Origin's raw-key features ignore those presses.
@Mixin(KeyboardHandler.class)
public abstract class KeyTapsMixin {
	private static boolean originclient$logged;

	@Inject(method = "keyPress", at = @At("HEAD"))
	private void originclient$countTap(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (!originclient$logged) {
			originclient$logged = true;   // one line: proof the hook is live (boot-smoke greps it)
			com.origin.client.OriginClient.LOGGER.info("Origin: key taps hook live");
		}
		int key = event.key();
		// A key's state is updated before its callback runs, so F3's own press would read F3 as
		// held: F3 is never part of its own combo.
		boolean f3Held = key != org.lwjgl.glfw.GLFW.GLFW_KEY_F3
				&& org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F3) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
		KeyTaps.onKey(key, action, f3Held, Minecraft.getInstance().gui.screen() != null);
	}
}
