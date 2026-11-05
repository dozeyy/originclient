package com.origin.client.client.mixin;

import com.origin.client.client.util.KeyTaps;
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
// that screen and never count. Target: private void onPress(long window, int button, int action, int mods)
// (1.16.5 to 1.21.8). A shape change fails the apply in boot-smoke, never the game.
@Mixin(MouseHandler.class)
public abstract class MouseTapsMixin {
	@Inject(method = "onPress", at = @At("HEAD"))
	private void originclient$countMouseTap(long window, int button, int action, int modifiers, CallbackInfo ci) {
		KeyTaps.onMouse(button, action, Minecraft.getInstance().screen != null);   // GLFW: press = 1, buttons 0..7
	}
}
