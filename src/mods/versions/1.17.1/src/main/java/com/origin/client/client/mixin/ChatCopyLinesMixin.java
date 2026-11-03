package com.origin.client.client.mixin;

import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
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
// Target: ChatComponent (1.17.1 bytecode, loom cache):
//   - private addMessage(Component,int,int,boolean): every line reaches trimmedMessages
//     here, new messages and re-wraps (rescaleChat) alike, each line added at index 0.
//     HEAD notes the old top line; RETURN maps every line above it to this message, so a
//     wrapped message copies whole. GuiMessage has no equals(), so the weak map is by
//     identity and forgets lines as soon as chat drops them.
//   - originclient$copyTextAt repeats getClickedComponentStyleAt's own hit-test
//     (isChatFocused/hideGui/isChatHidden, the -2/-40 offsets, scale, line spacing,
//     9 px lines, chatScrollbarPos) but returns the line instead of a style.
// If this stops applying: ChatComponent no longer implements ChatCopy.Lines, so ChatCopy
// returns false and right-click stays vanilla. If only the mapping hooks fail, the
// visible line is copied instead of the whole message.
@Mixin(ChatComponent.class)
public abstract class ChatCopyLinesMixin implements ChatCopy.Lines {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private List<GuiMessage<FormattedCharSequence>> trimmedMessages;
	@Shadow private int chatScrollbarPos;

	@Shadow
	private boolean isChatFocused() {
		throw new AssertionError();
	}

	@Shadow
	private boolean isChatHidden() {
		throw new AssertionError();
	}

	@Shadow
	public abstract double getScale();

	@Shadow
	public abstract int getWidth();

	@Shadow
	public abstract int getLinesPerPage();

	private final Map<Object, Component> originclient$lineOwner =
			Collections.synchronizedMap(new WeakHashMap<Object, Component>());
	private Object originclient$copyHead;

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", at = @At("HEAD"), require = 1)
	private void originclient$copyLinesStart(Component message, int id, int time, boolean refresh, CallbackInfo ci) {
		originclient$copyHead = trimmedMessages.isEmpty() ? null : trimmedMessages.get(0);
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", at = @At("RETURN"), require = 1)
	private void originclient$copyLinesEnd(Component message, int id, int time, boolean refresh, CallbackInfo ci) {
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
		if (!isChatFocused() || minecraft.options.hideGui || isChatHidden()) {
			return null;
		}
		double x = Mth.floor((mouseX - 2.0) / getScale());
		double y = Mth.floor((minecraft.getWindow().getGuiScaledHeight() - mouseY - 40.0)
				/ (getScale() * (minecraft.options.chatLineSpacing + 1.0)));
		if (x < 0.0 || y < 0.0) {
			return null;
		}
		int shown = Math.min(getLinesPerPage(), trimmedMessages.size());
		if (x > Mth.floor(getWidth() / getScale()) || y >= 9 * shown + shown) {
			return null;
		}
		int index = (int) (y / 9.0 + chatScrollbarPos);
		if (index < 0 || index >= trimmedMessages.size()) {
			return null;
		}
		GuiMessage<FormattedCharSequence> line = trimmedMessages.get(index);
		Component owner = originclient$lineOwner.get(line);
		return owner != null ? ChatCopy.plain(owner) : ChatCopy.visible(line.getMessage());
	}
}
