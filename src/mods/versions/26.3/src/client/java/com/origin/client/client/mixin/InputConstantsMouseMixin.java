package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: InputConstants.grabMouse / releaseMouse (26.3, SDL3). Each one warps the
// real cursor to the window centre (SDL_WarpMouseInWindow) and then flips SDL's
// relative mouse mode. SDL3 warps whether or not the window has focus.
// Why (Will, 2026-11-04: "alt tabbing out of borderless snaps my mouse to the center
// of the screen; the mouse should be free as soon as I do it"): alt-tab makes
// Minecraft open the pause menu (Pause on Lost Focus), whose releaseMouse yanked the
// cursor, already free in the other app, back to the game's centre. A resize while
// the mouse is held (Window.onResize -> grabMouse) did the same.
// Fix: while the window lacks input focus, only the relative mode changes; the
// cursor is left where the player has it. With focus, vanilla runs unchanged.
// GLFW versions need none of this: GLFW skips glfwSetCursorPos while unfocused.
@Mixin(InputConstants.class)
public abstract class InputConstantsMouseMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private static void originclient$grabWithoutWarp(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (!originclient$hasFocus(window)) {
			SDLMouse.SDL_SetWindowRelativeMouseMode(window.handle(), true);
			ci.cancel();
		}
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private static void originclient$releaseInPlace(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (!originclient$hasFocus(window)) {
			SDLMouse.SDL_SetWindowRelativeMouseMode(window.handle(), false);
			ci.cancel();
		}
	}

	// SDL's own focus flag, the truth the warp would ignore. Any doubt counts as
	// focused, which keeps vanilla's behaviour.
	private static boolean originclient$hasFocus(Window window) {
		try {
			return (SDLVideo.SDL_GetWindowFlags(window.handle()) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0L;
		} catch (Throwable t) {
			return true;
		}
	}
}
