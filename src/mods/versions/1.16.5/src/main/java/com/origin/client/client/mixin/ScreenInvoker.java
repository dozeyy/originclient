package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: Screen.addButton (protected). Why: TitleScreenMixin adds Origin's own
// "Mods" button to the main menu as a REAL widget so it gets vanilla click/focus/
// narration handling for free. 1.16.5 has no addRenderableWidget (1.17+): addButton
// is this era's "add to buttons + children" call (the module's PauseScreenMixin
// uses it too). If this stops applying, the title screen simply has no Origin Mods
// button -- the rest of the menu is unaffected.
@Mixin(Screen.class)
public interface ScreenInvoker {
	@Invoker("addButton")
	<T extends AbstractWidget> T originclient$addButton(T widget);
}
