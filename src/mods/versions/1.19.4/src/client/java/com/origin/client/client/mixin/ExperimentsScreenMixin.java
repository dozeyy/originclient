package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.worldselection.ExperimentsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// ExperimentsScreen (World Create > More > Experiments) tiles a dirt texture
// across its OWN content area directly in render(), on top of the already-
// suppressed renderBackground -- a different bug class from the list screens:
// this one doesn't skip renderBackground, it paints over it afterward. Out of
// world that area becomes the same translucent panel the lists use
// (OriginTheme.CLEAN_PANEL; 1.21.1 shows its menu-list background there), over
// the panorama backdrop. Fail-soft: backdrop unhealthy or Main Menu Style =
// Vanilla -> the vanilla dirt tile returns; if this stops applying, dirt. Pre-1.20 the draw is the inherited GuiComponent STATIC blit
// (owner = ExperimentsScreen in the bytecode) — the only blit of that shape
// in render() (bytecode-verified).
@Mixin(value = ExperimentsScreen.class, priority = 2000)
public class ExperimentsScreenMixin {

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/worldselection/ExperimentsScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIFFIIII)V"))
	private void originclient$noContentDirt(PoseStack poseStack, int x, int y,
											float u, float v, int width, int height, int texWidth, int texHeight) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.menuBackdropActive()
				&& !com.origin.client.client.OriginStyle.vanillaMenusOn(Minecraft.getInstance().screen)) {
			GuiComponent.fill(poseStack, x, y, x + width, y + height, com.origin.client.client.theme.OriginTheme.CLEAN_PANEL);
			return;
		}
		GuiComponent.blit(poseStack, x, y, u, v, width, height, texWidth, texHeight);
	}
}
