package com.origin.client.client.mixin;

import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

// Chat > Right-Click to Copy, the chat side (ChatCopy has the whole flow).
// Target: ChatComponent (1.19.4 bytecode, loom cache):
//   - private addMessage(Component,MessageSignature,int,GuiMessageTag,boolean): every
//     line reaches trimmedMessages here, new messages and re-wraps (refreshTrimmedMessage)
//     alike, each GuiMessage.Line added at index 0. HEAD notes the old top line; RETURN
//     maps every line above it to this message, so a wrapped message copies whole. Line
//     is a record, but its content is a fresh FormattedCharSequence per line, so the weak
//     map tells lines apart and forgets them as soon as chat drops them.
//   - originclient$copyTextAt repeats getClickedComponentStyleAt's own hit-test
//     (screenToChatX, screenToChatY, getMessageLineIndexAt -- which carries the
//     focus/hideGui/hidden checks, the width check and the scroll) but returns the line.
// If this stops applying: ChatComponent no longer implements ChatCopy.Lines, so ChatCopy
// returns false and right-click stays vanilla. If only the mapping hooks fail, the
// visible line is copied instead of the whole message.
@Mixin(ChatComponent.class)
public abstract class ChatCopyLinesMixin implements ChatCopy.Lines {
	@Shadow @Final private List<GuiMessage.Line> trimmedMessages;

	@Shadow
	private double screenToChatX(double x) {
		throw new AssertionError();
	}

	@Shadow
	private double screenToChatY(double y) {
		throw new AssertionError();
	}

	@Shadow
	private int getMessageLineIndexAt(double chatX, double chatY) {
		throw new AssertionError();
	}

	private final Map<Object, Component> originclient$lineOwner =
			Collections.synchronizedMap(new WeakHashMap<Object, Component>());
	private Object originclient$copyHead;

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
			at = @At("HEAD"), require = 1)
	private void originclient$copyLinesStart(Component message, MessageSignature signature, int time,
											 GuiMessageTag tag, boolean refresh, CallbackInfo ci) {
		originclient$copyHead = trimmedMessages.isEmpty() ? null : trimmedMessages.get(0);
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
			at = @At("RETURN"), require = 1)
	private void originclient$copyLinesEnd(Component message, MessageSignature signature, int time,
										   GuiMessageTag tag, boolean refresh, CallbackInfo ci) {
		Object head = originclient$copyHead;
		originclient$copyHead = null;
		try {
			int k = 0;
			int n = trimmedMessages.size();
			while (k < n && trimmedMessages.get(k) != head) {
				k++;
			}
			if (head != null && k == n) {
				return;   // the old top line is gone: can't tell which lines are new
			}
			for (int i = 0; i < k; i++) {
				originclient$lineOwner.put(trimmedMessages.get(i), message);
			}
		} catch (Throwable t) {
			// copying falls back to the visible line
		}
	}

	@Override
	public String originclient$copyTextAt(double mouseX, double mouseY) {
		int index = getMessageLineIndexAt(screenToChatX(mouseX), screenToChatY(mouseY));
		if (index < 0 || index >= trimmedMessages.size()) {
			return null;
		}
		GuiMessage.Line line = trimmedMessages.get(index);
		Component owner = originclient$lineOwner.get(line);
		return owner != null ? ChatCopy.plain(owner) : ChatCopy.visible(line.content());
	}
}
