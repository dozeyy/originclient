package com.origin.client.client.mixin;

import com.origin.client.client.ext.ChatLineFinder;
import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

// Chat -> Right-Click to Copy, the ChatComponent half (logic: mods/ChatCopy).
// Era: 1.21.11. The private hit-test chain (screenToChatX/Y, getMessageLineIndexAt)
// is gone; vanilla's link click replays the chat layout through the public
// captureClickableText(collector, guiHeight, tick, focused). We replay it the same way
// with ChatLineFinder (focused = true -> every line fully visible, so tick is unused),
// then find the hit line in trimmedMessages by identity of its content object.
// GuiMessage.Line has no parent link in this era, so ChatCopy.resolve maps the line
// back through allMessages.
// If a @Shadow stops matching, mixin apply fails at boot (CI boot-smoke catches it);
// the rest of chat doesn't depend on this class.
@Mixin(ChatComponent.class)
public abstract class ChatCopyMixin implements ChatCopy.Lookup {
	@Shadow @Final private List<GuiMessage> allMessages;
	@Shadow @Final private List<GuiMessage.Line> trimmedMessages;

	@Shadow
	private int getWidth() {
		throw new AssertionError();
	}

	@Shadow
	private double getScale() {
		throw new AssertionError();
	}

	@Override
	public String originclient$copyTextAt(double mouseX, double mouseY) {
		Minecraft mc = Minecraft.getInstance();
		ChatLineFinder finder = new ChatLineFinder(mouseX, mouseY, Mth.floor(getWidth() / getScale()),
				mc.options.chatLineSpacing().get());
		((ChatComponent) (Object) this).captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), 0, true);
		FormattedCharSequence hit = finder.hit();
		if (hit == null) {
			return null;
		}
		for (int line = 0; line < trimmedMessages.size(); line++) {
			if (trimmedMessages.get(line).content() == hit) {
				return ChatCopy.resolve(line, trimmedMessages.size(),
						i -> trimmedMessages.get(i).endOfEntry(),
						i -> trimmedMessages.get(i).content(),
						allMessages.size(),
						i -> allMessages.get(i).content());
			}
		}
		return null;   // the queue / restricted-prompt line: not a chat message
	}
}
