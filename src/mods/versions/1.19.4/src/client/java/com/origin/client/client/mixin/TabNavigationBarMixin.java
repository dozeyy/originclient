package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Target: TabNavigationBar.render's fill(PoseStack,IIIII) -- the only fill in
// render() (bytecode-verified). Why: the tab header bar fills an OPAQUE black
// strip across the top; over the panorama backdrop that reads as a hole. 1.21.1
// (the design truth) draws a translucent dark header there, so out of world the
// strip becomes the same translucent panel as the lists (OriginTheme.CLEAN_PANEL).
// The header-separator line under it stays vanilla's, as on 1.21.1. Pre-1.20
// the fill is the inherited GuiComponent STATIC (owner = TabNavigationBar in the
// bytecode), so the handler takes no instance. If this stops applying (or the
// backdrop is unhealthy, or Main Menu Style = Vanilla): vanilla's black strip.
@Mixin(value = TabNavigationBar.class, priority = 2000)
public class TabNavigationBarMixin {

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/tabs/TabNavigationBar;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V"))
	private void originclient$noBar(PoseStack poseStack, int x1, int y1, int x2, int y2, int color) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.menuBackdropActive()
				&& !com.origin.client.client.OriginStyle.vanillaMenusOn(Minecraft.getInstance().screen)) {
			GuiComponent.fill(poseStack, x1, y1, x2, y2, com.origin.client.client.theme.OriginTheme.CLEAN_PANEL);
			return;
		}
		GuiComponent.fill(poseStack, x1, y1, x2, y2, color);
	}
}
