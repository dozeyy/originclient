package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.network.chat.TextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider: every literal text Minecraft lays out or draws (chat, nametags,
// tab list, scoreboard, titles) is a TextComponent, and both of Component's
// visit paths hand its string on through getContents() -- Component.visitSelf
// (styled and plain) is `consumer.accept(style, this.getContents())`, and
// TextComponent.getContents() returns the literal (decompiled 1.16.4 and 1.18.1
// agree, so 1.17.1 does too). Swapping the string there means the width maths,
// line wrapping and drawing all see the same replaced text -- the 1.17.1 spot
// for 1.21.1's LiteralContents.visit hook. getText() (equality, copies) keeps
// the real string. NickHider.replace is a no-op returning the same string
// while the mod is off.
@Mixin(TextComponent.class)
public class NickHiderTextMixin {

	@Inject(method = "getContents", at = @At("RETURN"), cancellable = true)
	private void originclient$hideNames(CallbackInfoReturnable<String> cir) {
		try {
			String text = cir.getReturnValue();
			String shown = NickHider.replace(text);
			if (shown != text) {
				cir.setReturnValue(shown);
			}
		} catch (Throwable t) {
			// keep the real text
		}
	}
}
