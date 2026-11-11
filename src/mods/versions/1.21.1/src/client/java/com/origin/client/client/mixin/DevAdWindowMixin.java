package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.Window;
import com.origin.client.client.OriginDevAd;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only ad capture: the game window opens without taking focus, so it never steals the desktop's keyboard. */
@Mixin(Window.class)
public abstract class DevAdWindowMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
	private void originad$noFocus(CallbackInfo ci) {
		if (OriginDevAd.ON) {
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		}
	}
}
