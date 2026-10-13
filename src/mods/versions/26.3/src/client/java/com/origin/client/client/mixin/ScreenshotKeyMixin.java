package com.origin.client.client.mixin;

import com.origin.client.client.mods.ScreenshotChat;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

// Screenshot chat buttons: the F2 screenshot posts its "Saved screenshot as ..."
// chat line through the Consumer<Component> handed to
// Screenshot.grab(File, RenderTarget, Consumer). Wrapping that callback lets
// ScreenshotChat see the saved file and append its buttons to that same chat line.
//
// PER-VERSION DELTA (26.3): KeyboardHandler.keyPress no longer calls grab itself
// (the key now goes through Screenshot.grab(Minecraft, boolean)), so the wrap
// moved onto grab(File, RenderTarget, Consumer)'s own callback parameter. The
// world-icon auto screenshot uses takeScreenshot, not grab, and is untouched.
@Mixin(Screenshot.class)
public class ScreenshotKeyMixin {
	@ModifyVariable(method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
			at = @At("HEAD"), argsOnly = true)
	private static Consumer<Component> originclient$screenshotChat(Consumer<Component> vanilla) {
		try {
			return ScreenshotChat.wrap(vanilla);
		} catch (Throwable t) {
			return vanilla;
		}
	}
}
