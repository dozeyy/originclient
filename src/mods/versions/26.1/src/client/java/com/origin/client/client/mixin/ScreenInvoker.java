package com.origin.client.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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

	// Target: Screen.extractPanorama(GuiGraphicsExtractor, float) (protected,
	// javap-verified 26.1.2 / 26.2 / 26.3; 1.21.1's renderPanorama). Why: the
	// clean menus draw THIS version's own Mojang panorama (Screen.PANORAMA)
	// behind every out-of-world menu and loading scene, so each version module
	// shows its own update's panorama with no bundled copy. If it stops applying,
	// OriginScreenRenderer's catch hands the backdrop back to vanilla.
	@Invoker("extractPanorama")
	void originclient$extractPanorama(GuiGraphicsExtractor guiGraphics, float partialTick);

	// Target: Screen.extractBlurredBackground(GuiGraphicsExtractor) (protected,
	// javap-verified 26.x; 1.21.1's renderBlurredBackground): asks for vanilla's
	// one-per-frame GUI blur when Accessibility > Menu Background Blurriness >= 1.
	// Why: the panorama is blurred by vanilla's own blur. Same failure path as above.
	@Invoker("extractBlurredBackground")
	void originclient$extractBlurredBackground(GuiGraphicsExtractor guiGraphics);
}
