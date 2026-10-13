package com.origin.client.client.mixin.core;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The Chat mod's Background Opacity on 1.8.9 (no "text background opacity"
 * option before 1.14): the FIRST drawRect in GuiNewChat.drawChat is the
 * per-line background box (the later two are the scrollbar), and its colour is
 * swapped for OriginClientMod.chatBackground(...).
 *
 * The call is an inherited static (Gui.drawRect called unqualified inside
 * GuiNewChat), and the bytecode owner of such a call can be either class
 * depending on how it was compiled, so both spellings are declared; whichever
 * matches applies and the other matches nothing (the config's defaultRequire 0
 * makes a non-match a no-op, never a crash). GuiNewChat is not an
 * OptiFine-replaced class, so this INVOKE point holds with OptiFine installed.
 */
@Mixin(GuiNewChat.class)
public abstract class GuiNewChatMixin {
	@Redirect(method = "drawChat", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V", ordinal = 0))
	private void originclient$lineBackground(int left, int top, int right, int bottom, int color) {
		Gui.drawRect(left, top, right, bottom, OriginClientMod.chatBackground(color));
	}

	@Redirect(method = "drawChat", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;drawRect(IIIII)V", ordinal = 0))
	private void originclient$lineBackgroundGui(int left, int top, int right, int bottom, int color) {
		Gui.drawRect(left, top, right, bottom, OriginClientMod.chatBackground(color));
	}
}
