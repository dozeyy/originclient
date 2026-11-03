package com.origin.client.client.mixin;

import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Chat > Right-Click to Copy, the click (ChatCopy has the whole flow).
// Target: ChatScreen.mouseClicked(DDI)Z, at its call to input.mouseClicked (EditBox).
// Bytecode (1.17.1): commandSuggestions.mouseClicked runs first and returns early when it
// takes the click; the left-button branch (chat queue, component click events) returns
// early too; only then is the input box asked. Hooking that call means a right-click
// reaches us only when the suggestion list didn't want it, and left-click is never seen.
// If this stops applying: right-click on chat does nothing new (vanilla), nothing breaks.
@Mixin(ChatScreen.class)
public abstract class ChatCopyClickMixin {
	@Inject(method = "mouseClicked(DDI)Z", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/EditBox;mouseClicked(DDI)Z"),
			cancellable = true, require = 1)
	private void originclient$copyChatLine(double mouseX, double mouseY, int button,
										   CallbackInfoReturnable<Boolean> cir) {
		if (button == 1 && ChatCopy.copyAt(mouseX, mouseY)) {
			cir.setReturnValue(true);
		}
	}
}
