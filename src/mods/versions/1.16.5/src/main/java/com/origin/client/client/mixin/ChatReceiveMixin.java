package com.origin.client.client.mixin;

import com.origin.client.client.mods.ChatEvents;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

// Incoming chat for Auto GG + Hypixel (ChatEvents). 1.16.5:
// ClientPacketListener.handleChat hands every server line to
// Gui.handleChat(ChatType, Component, UUID) (public; decompiled 1.16.4), which passes it to the chat / action-bar listeners.
// A hidden line is cancelled here; a rewritten one is re-delivered once through
// the same method (guarded so the rewrite isn't processed twice), so vanilla's
// own blocking rules and listeners still see it exactly as they would.
@Mixin(Gui.class)
public class ChatReceiveMixin {
	@Unique
	private static boolean originclient$redelivering = false;

	@Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
	private void originclient$chatEvents(ChatType type, Component message, UUID sender, CallbackInfo ci) {
		if (originclient$redelivering || message == null) {
			return;
		}
		try {
			boolean overlay = type == ChatType.GAME_INFO;
			Component shown = ChatEvents.modify(message, overlay);
			if (!ChatEvents.allow(shown, overlay)) {
				ci.cancel();
				return;
			}
			ChatEvents.received(shown, overlay);
			if (shown != message) {
				ci.cancel();
				originclient$redelivering = true;
				try {
					((Gui) (Object) this).handleChat(type, shown, sender);
				} finally {
					originclient$redelivering = false;
				}
			}
		} catch (Throwable t) {
			// deliver the line untouched
		}
	}
}
