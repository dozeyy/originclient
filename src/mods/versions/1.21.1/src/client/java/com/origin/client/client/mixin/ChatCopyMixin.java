package com.origin.client.client.mixin;

import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

// Chat -> Right-Click to Copy, the ChatComponent half (logic: mods/ChatCopy).
// Target: ChatComponent's own private hit-test, the same chain vanilla's
// getClickedComponentStyleAt runs for a left-click link (screenToChatX/Y ->
// getMessageLineIndexAt), so a right-click lands on exactly the line a link click
// would, with chat scale, scroll and the hidden-chat check already applied.
// Era: 1.20 - 1.21.10 (these three methods are gone in 1.21.11, which hit-tests
// through captureClickableText instead). GuiMessage.Line has no link to its message
// in this era, so ChatCopy.resolve maps the line back through allMessages.
// If a @Shadow stops matching, mixin apply fails at boot (CI boot-smoke catches it);
// the rest of chat doesn't depend on this class.
@Mixin(ChatComponent.class)
public abstract class ChatCopyMixin implements ChatCopy.Lookup {
	@Shadow @Final private List<GuiMessage> allMessages;
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

	@Override
	public String originclient$copyTextAt(double mouseX, double mouseY) {
		int line = getMessageLineIndexAt(screenToChatX(mouseX), screenToChatY(mouseY));
		if (line < 0 || line >= trimmedMessages.size()) {
			return null;
		}
		return ChatCopy.resolve(line, trimmedMessages.size(),
				i -> trimmedMessages.get(i).endOfEntry(),
				i -> trimmedMessages.get(i).content(),
				allMessages.size(),
				i -> allMessages.get(i).content());
	}
}
