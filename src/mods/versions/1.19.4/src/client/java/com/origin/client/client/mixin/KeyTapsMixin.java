package com.origin.client.client.mixin;

import com.origin.client.client.util.KeyTaps;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds util/KeyTaps (G121-7): every key PRESS (it counts those made with no screen open), so Origin's
// toggle keys catch a tap shorter than a client tick. Observes only (HEAD, no cancel):
// vanilla's key handling is untouched. Target: public void keyPress(long window, int key,
// int scancode, int action, int modifiers), the GLFW-callback shape 1.16.5 to 1.21.8 share
// (javap 1.20 to 1.21.8, 2026-11-04; ScreenshotKeyMixin targets the same method here).
// Also tells KeyTaps whether F3 was held when the key went down (BMOD-1): F3+B / F3+G ...
// are vanilla's debug combos, so Origin's raw-key features ignore those presses.
@Mixin(KeyboardHandler.class)
public abstract class KeyTapsMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void originclient$countTap(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
		// A key's state is updated before its callback runs, so F3's own press would read F3 as
		// held: F3 is never part of its own combo.
		boolean f3Held = key != org.lwjgl.glfw.GLFW.GLFW_KEY_F3
				&& org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F3) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
		KeyTaps.onKey(key, action, f3Held, Minecraft.getInstance().screen != null);
	}
}
