package com.origin.client.client.mixin.menus;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.util.List;

// 1.8.9: GuiScreen's buttonList and actionPerformed are protected. The menus
// area reads the list from outside the screen (OriginForeignWidgets) and replays
// a button's own action after the Smart Disconnect confirmation (MenuHooks) --
// the 1.16.5 equivalents (Screen.children(), Button.onPress()) are public there.
// Accessor/Invoker, not reflection: the refmap carries the SRG names.
// If either fails to apply, callers' instanceof guards turn the feature off.
@Mixin(GuiScreen.class)
public interface GuiScreenAccessor {
	@Accessor("buttonList")
	List<GuiButton> originclient$buttonList();

	@Invoker("actionPerformed")
	void originclient$actionPerformed(GuiButton button) throws IOException;
}
