package com.origin.client.client.mixin.menus;

import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: GuiMainMenu (vanilla 1.8.9 + Forge's patch). Accessors and invokers
// only name members, so they hold even if another mod rewrites the class body,
// as long as these members keep their names (unproven against OptiFine's copy,
// hence the fail-soft below).
// Why: the clean look (2026-10-19) puts 1.8.9's OWN title panorama -- the
// panorama_0..5 cube with vanilla's seven blur passes -- behind Origin's title,
// every out-of-world menu and the loading scenes, drawn by vanilla's renderer
// (MenuPanorama owns one hidden GuiMainMenu and drives it). renderSkybox and the
// fields it reads are private; initGui (which normally fills the two textures)
// is never called on the hidden screen because it also builds buttons and the
// Realms notice, so the textures are handed in through these setters.
// If it stops applying: the cast in MenuPanorama fails once, MenuPanorama
// latches off, and every caller falls back to a plain dark fill -- never a crash.
@Mixin(GuiMainMenu.class)
public interface GuiMainMenuAccessor {
	@Accessor("panoramaTimer")
	void originclient$setPanoramaTimer(int ticks);

	@Accessor("viewportTexture")
	void originclient$setViewportTexture(DynamicTexture texture);

	@Accessor("backgroundTexture")
	void originclient$setBackgroundTexture(ResourceLocation location);

	@Invoker("renderSkybox")
	void originclient$renderSkybox(int mouseX, int mouseY, float partialTicks);
}
