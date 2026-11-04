package com.origin.client.client.mixin.core;

import java.lang.reflect.Field;
import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: MouseHelper.ungrabMouseCursor, which frees the mouse when a menu opens.
// Why (Will, 2026-11-04: "alt tabbing out of borderless snaps my mouse to the center
// of the screen"): vanilla parks the cursor at the window centre and then calls
// Mouse.setGrabbed(false), and LWJGL 2 moves the real Windows cursor there with
// SetCursorPos whether or not the game has focus. Alt-tab opens the pause menu
// half a second later (EntityRenderer, Pause on Lost Focus), so the cursor, already
// free in the other app, was yanked back to the game's centre.
// Fix: while the game window is NOT active, free the mouse where it is. LWJGL 2's
// setGrabbed(false) only moves the cursor when it believes the mouse is grabbed
// (Mouse.isGrabbed), so that flag is cleared first; setGrabbed(false) then just
// shows the cursor and drops the clip. With focus, nothing changes (vanilla runs,
// Modern Mouse untouched). If LWJGL's field is ever missing, vanilla runs.
@Mixin(MouseHelper.class)
public abstract class MouseHelperUngrabMixin {
	private static Field originclient$grabbedFlag;
	private static boolean originclient$flagMissing;

	@Inject(method = "ungrabMouseCursor", at = @At("HEAD"), cancellable = true)
	private void originclient$releaseInPlace(CallbackInfo ci) {
		if (Display.isActive() || !Mouse.isGrabbed() || originclient$flagMissing) {
			return;
		}
		try {
			if (originclient$grabbedFlag == null) {
				Field f = Mouse.class.getDeclaredField("isGrabbed");
				f.setAccessible(true);
				originclient$grabbedFlag = f;
			}
			originclient$grabbedFlag.setBoolean(null, false);
			Mouse.setGrabbed(false);
			ci.cancel();
		} catch (Throwable t) {
			originclient$flagMissing = true;
			try {
				originclient$grabbedFlag.setBoolean(null, true); // hand vanilla the state it expects
			} catch (Throwable ignored) {
				// field never resolved: nothing was changed
			}
		}
	}
}
