package com.origin.client.client.mixin;

import com.origin.client.client.mods.ScreenshotChat;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Consumer;

// Screenshot chat buttons: F2 is handled in KeyboardHandler.keyPress, which calls
// Screenshot.grab(File, RenderTarget, Consumer<Component>) with a callback that
// posts the "Saved screenshot as ..." chat line (decompiled 1.18.1; 1.17.1
// has the same 3-arg grab -- the module's OriginDevShots calls its 4-arg
// sibling -- and it is the only grab call in keyPress). Wrapping that callback lets ScreenshotChat see
// the saved file and append its buttons to that same chat line. Only the F2 path is wrapped -- other grab callers
// (dev screenshot tooling) are untouched.
@Mixin(KeyboardHandler.class)
public class ScreenshotKeyMixin {
	@ModifyArg(method = "keyPress",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V"),
			index = 2)
	private Consumer<Component> originclient$screenshotChat(Consumer<Component> vanilla) {
		try {
			return ScreenshotChat.wrap(vanilla);
		} catch (Throwable t) {
			return vanilla;
		}
	}
}
