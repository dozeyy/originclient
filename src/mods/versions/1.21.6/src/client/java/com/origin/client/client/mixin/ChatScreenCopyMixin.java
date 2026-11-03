package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Chat -> Right-Click to Copy, the ChatScreen half (logic: mods/ChatCopy).
// Target: ChatScreen.mouseClicked(DDI)Z at its final fall-through, the
// super.mouseClicked call. By then vanilla has offered the click to the command
// suggestion list and run its left-click handling (chat queue, links, click events),
// so all of that stays exactly vanilla; only a right-click that nothing took reaches
// us. A right-click on a chat line is consumed (copy + action-bar note); anything else,
// or the toggle off, falls through to vanilla untouched.
// If the INVOKE target stops matching, mixin apply fails at boot (require = 1, caught
// by CI boot-smoke) rather than the feature silently dying.
@Mixin(ChatScreen.class)
public abstract class ChatScreenCopyMixin {
	@Inject(method = "mouseClicked", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(DDI)Z"),
			cancellable = true, require = 1)
	private void originclient$copyChatLine(double mouseX, double mouseY, int button,
										   CallbackInfoReturnable<Boolean> cir) {
		if (button != InputConstants.MOUSE_BUTTON_RIGHT || !ChatCopy.enabled()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			String text = ((ChatCopy.Lookup) mc.gui.getChat()).originclient$copyTextAt(mouseX, mouseY);
			if (text == null || text.isEmpty()) {
				return;
			}
			ChatCopy.toClipboard(text);
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal(ChatCopy.COPIED), true);
			}
			cir.setReturnValue(true);
		} catch (RuntimeException | LinkageError e) {
			ChatCopy.fail(e);
		}
	}
}
