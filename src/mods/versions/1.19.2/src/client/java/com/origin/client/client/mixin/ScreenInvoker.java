package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: Screen.addRenderableWidget (protected). Why: TitleScreenMixin adds
// Origin's own "Mods" / "Shaders" buttons to the main menu as REAL widgets so
// they get vanilla click/focus/narration handling for free. If this stops
// applying, the title screen simply has no Origin buttons in its side card —
// the rest of the menu is unaffected.
@Mixin(Screen.class)
public interface ScreenInvoker {
	@Invoker("addRenderableWidget")
	// Pre-1.19.3 the bound is Widget (renamed Renderable in 1.19.3); the invoker
	// binds on the ERASED descriptor (first bound GuiEventListener), so it is left off.
	<T extends GuiEventListener & NarratableEntry> T originclient$addRenderableWidget(T widget);
}
