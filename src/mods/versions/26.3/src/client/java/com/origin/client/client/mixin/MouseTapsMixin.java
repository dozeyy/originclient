package com.origin.client.client.mixin;

import com.origin.client.client.util.KeyTaps;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds util/KeyTaps with mouse-button PRESSES (INPUT-4), as KeyTapsMixin does for keys: an Origin
// key bound to a mouse button (Mouse 4 / 5 for Toggle Sprint, JG-4) is read raw once per tick, so a
// quick side-button click that went down and up between two ticks was missed. Observes only (HEAD,
// no cancel): vanilla's click handling is untouched. Clicks made with a screen open are clicks on
// that screen and never count. Target: the 1.21.9+ shape 26.3 keeps
// (javap 26.3: onButton(long, MouseButtonInfo, int)). A shape change fails the apply in
// boot-smoke, never the game.
@Mixin(MouseHandler.class)
public abstract class MouseTapsMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void originclient$countMouseTap(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		KeyTaps.onMouse(originclient$button(info.button()), action == InputConstants.PRESS ? 1 : 0,
				Minecraft.getInstance().gui.screen() != null);
	}

	// 26.3 (SDL3) numbers the buttons its own way; KeyTaps / util/KeyCodes use GLFW's
	// (0 left, 1 right, 2 middle, 3.. side buttons). Unknown -> -1, which KeyTaps ignores.
	private static int originclient$button(int b) {
		if (b == InputConstants.MOUSE_BUTTON_LEFT) {
			return 0;
		}
		if (b == InputConstants.MOUSE_BUTTON_RIGHT) {
			return 1;
		}
		if (b == InputConstants.MOUSE_BUTTON_MIDDLE) {
			return 2;
		}
		if (b == InputConstants.MOUSE_BUTTON_4) {
			return 3;
		}
		if (b == InputConstants.MOUSE_BUTTON_5) {
			return 4;
		}
		if (b == InputConstants.MOUSE_BUTTON_6) {
			return 5;
		}
		if (b == InputConstants.MOUSE_BUTTON_7) {
			return 6;
		}
		return b == InputConstants.MOUSE_BUTTON_8 ? 7 : -1;
	}
}
