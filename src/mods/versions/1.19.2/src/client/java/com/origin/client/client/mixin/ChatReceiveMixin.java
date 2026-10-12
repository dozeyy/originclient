package com.origin.client.client.mixin;

import com.origin.client.client.mods.ChatEvents;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ChatEvents' dispatcher on 1.19.2 (Fabric API here has no client message
// events). Every server SYSTEM line -- which is all of Hypixel's chat on a
// 1.19.1+ client -- reaches ChatListener.handleSystemMessage(Component, boolean
// overlay) from ClientPacketListener.handleSystemChat (decompiled 1.19.1, the
// 1.19.2 code base). At HEAD the line runs through ChatEvents: hidden lines are
// cancelled; a modified line is handed back to this same method (guarded
// against re-entry) so vanilla shows, narrates and logs the new text.
@Mixin(ChatListener.class)
public abstract class ChatReceiveMixin {
	@Unique
	private static boolean originclient$redispatching = false;

	@Inject(method = "handleSystemMessage", at = @At("HEAD"), cancellable = true)
	private void originclient$chatEvents(Component message, boolean overlay, CallbackInfo ci) {
		if (originclient$redispatching) {
			return;
		}
		Component out;
		try {
			out = ChatEvents.dispatch(message, overlay);
		} catch (Throwable t) {
			return;   // vanilla shows the line untouched
		}
		if (out == null) {
			ci.cancel();
		} else if (out != message) {
			ci.cancel();
			originclient$redispatching = true;
			try {
				((ChatListener) (Object) this).handleSystemMessage(out, overlay);
			} finally {
				originclient$redispatching = false;
			}
		}
	}
}
