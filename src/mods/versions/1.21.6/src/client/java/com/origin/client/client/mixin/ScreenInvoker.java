package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.Renderable;
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
	<T extends GuiEventListener & Renderable & NarratableEntry> T originclient$addRenderableWidget(T widget);

	// Target: Screen.renderPanorama(GuiGraphics, float) (protected, javap-verified
	// 1.21.6). Why: the clean menus draw THIS version's own Mojang panorama
	// (GameRenderer.getPanorama(), the cubemap at minecraft:textures/gui/title/background/)
	// behind every out-of-world menu and loading scene, so each version module
	// shows its own update's panorama with no bundled copy. If it stops
	// applying, OriginScreenRenderer's catch falls back to vanilla's backdrop.
	@Invoker("renderPanorama")
	void originclient$renderPanorama(net.minecraft.client.gui.GuiGraphics guiGraphics, float partialTick);

	// Target: Screen.renderBlurredBackground(GuiGraphics) (protected, javap-verified
	// 1.21.6; 1.21.6 swapped 1.21.2's no-arg form for the GuiGraphics): asks the
	// deferred GUI for the frame's one blur (GuiGraphics.blurBeforeThisStratum,
	// skipped at Menu Background Blurriness 0). Why: the panorama is blurred by
	// vanilla's own blur, which follows that Accessibility option. Same failure
	// path as above.
	@Invoker("renderBlurredBackground")
	void originclient$renderBlurredBackground(net.minecraft.client.gui.GuiGraphics guiGraphics);
}
