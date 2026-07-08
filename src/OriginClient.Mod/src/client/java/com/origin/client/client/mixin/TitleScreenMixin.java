package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Re-skins the main menu: the Origin background (charcoal + rotating rings +
// grain) replaces the panorama, and the "Origin" wordmark replaces the vanilla
// "Minecraft" logo. Buttons and all other vanilla text are left untouched
// (kept default for now, per Will).
//
// Strategy (all targets confirmed via javap against the mapped 1.21.1 jar):
//  - Draw the Origin background at render() HEAD -- render() is guaranteed to
//    run every frame, so the background is always painted, under the logo/
//    buttons that draw afterward.
//  - Cancel both renderPanorama and renderBackground so vanilla's own backdrop
//    (whichever path render() uses) never paints over ours. Both are
//    background-only methods on TitleScreen, safe to suppress; widgets are
//    drawn by the separate widget pass, unaffected.
//  - Redirect the LogoRenderer.renderLogo(GuiGraphics,int,float) call to draw
//    the Origin wordmark instead.
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$background(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderTitleBackground(guiGraphics);
	}

	@Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressPanorama(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ci.cancel();
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V"))
	private void originclient$logo(LogoRenderer instance, GuiGraphics guiGraphics, int screenWidth, float alpha) {
		OriginScreenRenderer.renderTitleWordmark(guiGraphics);
	}

	// Remove the yellow splash text.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/client/gui/Font;I)V"))
	private void originclient$noSplash(SplashRenderer instance, GuiGraphics guiGraphics, int screenWidth, Font font, int color) {
		// draw nothing
	}

	// Remove the bottom version line (the only drawString in render()).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
	private int originclient$noVersion(GuiGraphics instance, Font font, String text, int x, int y, int color) {
		return 0; // draw nothing
	}
}
