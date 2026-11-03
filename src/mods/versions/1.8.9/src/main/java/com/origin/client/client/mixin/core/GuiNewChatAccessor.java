package com.origin.client.client.mixin.core;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// Target: GuiNewChat's private chatLines (one entry per message, newest first),
// drawnChatLines (the wrapped lines on screen, newest first) and scrollPos.
// Why: Right-Click to Copy (mods/ChatCopy) finds the line under the mouse the
// same way vanilla's getChatComponent does, then the whole message it belongs to;
// 1.8.9 has no public getter for any of the three. Accessors, not reflection:
// the refmap carries the SRG names. GuiNewChat is not replaced by Forge or OptiFine.
// If it stops applying: the instanceof guard in ChatCopy turns the feature off
// (right-click does what vanilla does: nothing), never a crash.
@Mixin(GuiNewChat.class)
public interface GuiNewChatAccessor {
	@Accessor("chatLines")
	List<ChatLine> originclient$chatLines();

	@Accessor("drawnChatLines")
	List<ChatLine> originclient$drawnChatLines();

	@Accessor("scrollPos")
	int originclient$scrollPos();
}
