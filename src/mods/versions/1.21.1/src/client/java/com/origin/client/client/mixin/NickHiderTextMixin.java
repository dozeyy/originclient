package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.network.chat.contents.PlainTextContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Nick Hider: every literal text Minecraft lays out or draws (chat, nametags,
// tab list, scoreboard, titles) hands its string to a consumer through one of
// LiteralContents' two visit methods (1.21.1 source-verified: each is a single
// accept(...) call). Swapping the string there means the width maths, line
// wrapping and drawing all see the same replaced text. NickHider.replace is a
// no-op returning the same string while the mod is off.
@Mixin(PlainTextContents.LiteralContents.class)
public class NickHiderTextMixin {
	@ModifyArg(method = "visit(Lnet/minecraft/network/chat/FormattedText$StyledContentConsumer;Lnet/minecraft/network/chat/Style;)Ljava/util/Optional;",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/chat/FormattedText$StyledContentConsumer;accept(Lnet/minecraft/network/chat/Style;Ljava/lang/String;)Ljava/util/Optional;"),
			index = 1)
	private String originclient$hideNamesStyled(String text) {
		try {
			return NickHider.replace(text);
		} catch (Throwable t) {
			return text;
		}
	}

	@ModifyArg(method = "visit(Lnet/minecraft/network/chat/FormattedText$ContentConsumer;)Ljava/util/Optional;",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/chat/FormattedText$ContentConsumer;accept(Ljava/lang/String;)Ljava/util/Optional;"),
			index = 0)
	private String originclient$hideNamesPlain(String text) {
		try {
			return NickHider.replace(text);
		} catch (Throwable t) {
			return text;
		}
	}
}
