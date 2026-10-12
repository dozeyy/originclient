package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.network.chat.TextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider: before 1.19 every literal text Minecraft lays out or draws (chat,
// nametags, tab list, scoreboard, titles) is a TextComponent, and both of
// Component's visit paths hand a consumer exactly its getContents() (decompiled
// 1.18.1: Component.visitSelf -> consumer.accept(this.getContents()); 1.19 moved
// this into LiteralContents.visit). Swapping the string there means the width
// maths, line wrapping and drawing all see the same replaced text.
// NickHider.replace is a no-op returning the same string while the mod is off.
@Mixin(TextComponent.class)
public class NickHiderTextMixin {
	@Inject(method = "getContents", at = @At("RETURN"), cancellable = true)
	private void originclient$hideNames(CallbackInfoReturnable<String> cir) {
		try {
			String text = cir.getReturnValue();
			String replaced = NickHider.replace(text);
			if (replaced != text) {
				cir.setReturnValue(replaced);
			}
		} catch (Throwable t) {
			// keep the original text
		}
	}
}
