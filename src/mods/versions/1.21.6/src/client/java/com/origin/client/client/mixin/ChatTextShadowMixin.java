package com.origin.client.client.mixin;

import com.origin.client.client.ext.ChatTextState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat mod — "Text Shadow in Chat" for the 1.21.6+ eras.
 *
 * The chat line draw moved into a lambda inside ChatComponent.render, so there is
 * no stable call site to redirect. ChatTimestampMixin arms ChatTextState for the
 * span of that render instead, and this hook re-issues the draw without a shadow
 * while it is armed. Every other piece of GUI text is untouched: outside that
 * span the flag is false and this is a single static boolean read.
 */
@Mixin(GuiGraphics.class)
public class ChatTextShadowMixin {

	@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void originclient$chatShadow(Font font, FormattedCharSequence text, int x, int y, int color,
										 CallbackInfo ci) {
		if (ChatTextState.suppressShadow) {
			((GuiGraphics) (Object) this).drawString(font, text, x, y, color, false);
			ci.cancel();
		}
	}
}
