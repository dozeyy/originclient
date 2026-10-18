package com.origin.client.client.mixin.loading;

import com.mojang.blaze3d.platform.Window;
import com.origin.client.client.OriginHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The window half of the launcher-to-game handoff (OriginHandoff): when Origin
 * Launcher asks for it, the game window is created hidden, then placed under
 * the launcher's loading scene and shown fully transparent, so it can fade in
 * instead of popping up. CallbackInfo-only handlers, so no constructor
 * descriptor to get wrong; OriginHandoff does nothing (the window opens like
 * vanilla's) without the launcher's property or in fullscreen.
 */
@Mixin(Window.class)
public abstract class WindowHandoffMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
	private void originclient$createHidden(CallbackInfo ci) {
		OriginHandoff.beforeWindowCreate(((Window) (Object) this).isFullscreen());
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void originclient$placeAndShow(CallbackInfo ci) {
		OriginHandoff.windowCreated((Window) (Object) this);
	}
}
