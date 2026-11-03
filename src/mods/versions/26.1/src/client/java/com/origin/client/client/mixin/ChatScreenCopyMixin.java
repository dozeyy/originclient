package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Chat -> Right-Click to Copy, the ChatScreen half (logic: mods/ChatCopy).
// Target: ChatScreen.mouseClicked(MouseButtonEvent, boolean) at its final fall-through,
// the super.mouseClicked call (1.21.9+ input events). By then vanilla has offered the
// click to the command suggestion list and run its left-click handling (chat queue,
// links, click events), so all of that stays exactly vanilla; only a right-click that
// nothing took reaches us. A right-click on a chat line is consumed (copy + action-bar
// note); anything else, or the toggle off, falls through to vanilla untouched.
// Right is InputConstants.MOUSE_BUTTON_RIGHT, never a literal: 26.3 renumbered the
// buttons (LEFT 1, RIGHT 3).
// If the INVOKE target stops matching, mixin apply fails at boot (require = 1, caught
// by CI boot-smoke) rather than the feature silently dying.
@Mixin(ChatScreen.class)
public abstract class ChatScreenCopyMixin {
	@Inject(method = "mouseClicked", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"),
			cancellable = true, require = 1)
	private void originclient$copyChatLine(MouseButtonEvent event, boolean doubleClick,
										   CallbackInfoReturnable<Boolean> cir) {
		if (event.button() != InputConstants.MOUSE_BUTTON_RIGHT || !ChatCopy.enabled()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			String text = ((ChatCopy.Lookup) mc.gui.getChat()).originclient$copyTextAt(event.x(), event.y());
			if (text == null || text.isEmpty()) {
				return;
			}
			ChatCopy.toClipboard(text);
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal(ChatCopy.COPIED));   // 26.x: displayClientMessage(c, true) became sendOverlayMessage
			}
			cir.setReturnValue(true);
		} catch (RuntimeException | LinkageError e) {
			ChatCopy.fail(e);
		}
	}
}
