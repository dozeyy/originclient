package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// Chat mod: prepends a muted [HH:mm] timestamp to incoming chat lines when
// the option is on. Opacity/scale are driven through the vanilla options
// from the feature tick (they already exist as accessibility settings —
// no reason to re-implement rendering for those).
@Mixin(ChatComponent.class)
public class ChatTimestampMixin {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component originclient$timestamp(Component message) {
		if (Mods.on("chat") && Mods.bool("chat", "timestamps")) {
			return Component.literal("[" + LocalTime.now().format(TIME) + "] ")
					.withStyle(ChatFormatting.DARK_GRAY)
					.append(message);
		}
		return message;
	}
}
