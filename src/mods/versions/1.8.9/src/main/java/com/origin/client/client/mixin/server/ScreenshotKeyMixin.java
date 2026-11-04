package com.origin.client.client.mixin.server;

import com.origin.client.client.mods.ScreenshotChat;
import com.origin.client.client.mods.ScreenshotSaver;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

// Screenshot chat buttons: on 1.8.9, F2 (Minecraft.dispatchKeypresses) prints
// whatever ScreenShotHelper.saveScreenshot(...) RETURNS -- the "Saved screenshot
// as ..." line, built after the PNG is written (synchronously, on the main
// thread). The 4-arg overload delegates to this 5-arg one, so hooking its RETURN
// sees every save; ScreenshotChat.decorate appends the buttons to that same line
// (a failure line is returned untouched). RETURN is the OptiFine-safe shape
// should OptiFine touch this class. Forge 1.8.9 has no ScreenshotEvent (1.11+).
//
// Async save (ScreenshotSaver, 2026-11-04): the HEAD hook reads the pixels and hands the
// PNG write to an IO thread, returning a placeholder; the decorated line is posted when the
// file exists, so the RETURN hook below only sees the saves ScreenshotSaver leaves to vanilla.
@Mixin(ScreenShotHelper.class)
public class ScreenshotKeyMixin {
	@Inject(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/shader/Framebuffer;)Lnet/minecraft/util/IChatComponent;",
			at = @At("HEAD"), cancellable = true)
	private static void originclient$asyncSave(File gameDir, String name, int width, int height,
			Framebuffer buffer, CallbackInfoReturnable<IChatComponent> cir) {
		try {
			IChatComponent pending = ScreenshotSaver.begin(gameDir, name, width, height, buffer);
			if (pending != null) {
				cir.setReturnValue(pending);
			}
		} catch (Throwable t) {
			// vanilla saves it on this thread
		}
	}

	@Inject(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/shader/Framebuffer;)Lnet/minecraft/util/IChatComponent;",
			at = @At("RETURN"), cancellable = true)
	private static void originclient$screenshotChat(File gameDir, String name, int width, int height,
			Framebuffer buffer, CallbackInfoReturnable<IChatComponent> cir) {
		try {
			IChatComponent out = ScreenshotChat.decorate(cir.getReturnValue());
			if (out != null && out != cir.getReturnValue()) {
				cir.setReturnValue(out);
			}
		} catch (Throwable t) {
			// vanilla's line as-is
		}
	}
}
