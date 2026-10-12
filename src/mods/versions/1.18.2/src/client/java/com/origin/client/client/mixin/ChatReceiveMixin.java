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

// ChatEvents' dispatcher on 1.18.2 (Fabric API here has no client message
// events). Every incoming chat line reaches Gui.handleChat(ChatType, Component,
// UUID sender) from ClientPacketListener.handleChat (decompiled 1.18.1; 1.18.2
// keeps the shape). GAME_INFO is the action bar (Fabric's "overlay"). At HEAD
// the line runs through ChatEvents: hidden lines are cancelled; a modified line
// is handed back to this same method (guarded against re-entry) so vanilla's
// blocked-player check and chat listeners see the new text.
@Mixin(Gui.class)
public abstract class ChatReceiveMixin {
	@Unique
	private static boolean originclient$redispatching = false;

	@Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
	private void originclient$chatEvents(ChatType type, Component message, UUID sender, CallbackInfo ci) {
		if (originclient$redispatching) {
			return;
		}
		Component out;
		try {
			out = ChatEvents.dispatch(message, type == ChatType.GAME_INFO);
		} catch (Throwable t) {
			return;   // vanilla shows the line untouched
		}
		if (out == null) {
			ci.cancel();
		} else if (out != message) {
			ci.cancel();
			originclient$redispatching = true;
			try {
				((Gui) (Object) this).handleChat(type, out, sender);
			} finally {
				originclient$redispatching = false;
			}
		}
	}
}
