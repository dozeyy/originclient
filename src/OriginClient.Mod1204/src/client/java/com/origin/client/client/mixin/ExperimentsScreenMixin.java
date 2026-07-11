package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.ExperimentsScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// ExperimentsScreen (World Create > More > Experiments) tiles a dirt texture
// across its OWN content area in its renderBackground override (on 1.20.4;
// earlier versions did it in render()), on top of the already-suppressed
// Screen backdrop. Skip that blit too so the content area sits on the Origin
// backdrop like every other menu. Fail-soft: if Origin rendering is
// unhealthy, the vanilla dirt tile returns.
@Mixin(value = ExperimentsScreen.class, priority = 2000)
public class ExperimentsScreenMixin {

	@Redirect(method = "renderBackground", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"))
	private void originclient$noContentDirt(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y,
											float u, float v, int width, int height, int texWidth, int texHeight) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			return; // skip the tiled content-area dirt
		}
		guiGraphics.blit(texture, x, y, u, v, width, height, texWidth, texHeight);
	}
}
