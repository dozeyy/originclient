package com.origin.client.client.mixin;

import com.origin.client.client.ext.ChatLineFinder;
import com.origin.client.client.mods.ChatCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

// Chat -> Right-Click to Copy, the ChatComponent half (logic: mods/ChatCopy).
// Era: 26.x. Vanilla's link click replays the chat layout through the public
// captureClickableText(collector, guiHeight, tick, DisplayMode); we replay it the same
// way with ChatLineFinder (FOREGROUND -> every line fully visible, so tick is unused),
// then find the hit line in trimmedMessages by identity of its content object.
// 26.x GuiMessage.Line carries parent(), so the whole message comes straight from it.
// If a @Shadow stops matching, mixin apply fails at boot (CI boot-smoke catches it);
// the rest of chat doesn't depend on this class.
@Mixin(ChatComponent.class)
public abstract class ChatCopyMixin implements ChatCopy.Lookup {
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
		((ChatComponent) (Object) this).captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), 0,
				ChatComponent.DisplayMode.FOREGROUND);
		FormattedCharSequence hit = finder.hit();
		if (hit == null) {
			return null;
		}
		for (GuiMessage.Line line : trimmedMessages) {
			if (line.content() == hit) {
				return ChatCopy.messageText(line.parent().content());
			}
		}
		return null;   // the queue / restricted-prompt line: not a chat message
	}
}
