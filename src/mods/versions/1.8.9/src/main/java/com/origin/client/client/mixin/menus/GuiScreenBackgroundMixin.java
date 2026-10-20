package com.origin.client.client.mixin.menus;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.MenuBlur;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every screen's own backdrop, in one place.
//
// OUT OF A WORLD: the Origin backdrop is drawn ONCE per menu by MenuHooks'
// GuiScreenEvent.DrawScreenEvent.Pre -- before any screen content, so it clears
// the frame for every screen (1.16.5's beforeRender screen event). These hooks
// only CANCEL vanilla's dirt so it can't paint over that backdrop:
//  - drawWorldBackground(int): what drawDefaultBackground calls; without a world
//    it tiles the dirt via drawBackground;
//  - drawBackground(int): the dirt itself, also called directly by some screens
//    (GuiDownloadTerrain, mods' screens).
// IN A WORLD: drawWorldBackground draws the flat darkening gradient behind every
// in-world screen -- pause, options AND every container screen -- so Menu Blur
// swaps it for blur + tint here (1.16.5's MenuBlurMixin on
// renderBackground(PoseStack, int)). MenuBlur.handles(screen) says no (mod off,
// kind toggled off, OptiFine shaders on, no framebuffer) -> vanilla's gradient.
// Fail-soft: nothing is cancelled unless the Origin draw is healthy.
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = GuiScreen.class, priority = 2000)
public abstract class GuiScreenBackgroundMixin {

	@Inject(method = "drawWorldBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$background(int tint, CallbackInfo ci) {
		try {
			GuiScreen self = (GuiScreen) (Object) this;
			if (Minecraft.getMinecraft().theWorld == null) {
				if (OriginScreenRenderer.isActive() && !OriginStyle.vanillaMenusOn(this)) {
					ci.cancel();
				}
				return;
			}
			if (MenuBlur.handles(self) && MenuBlur.blur(0f)) {
				MenuBlur.tint(new Gfx(), self);
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla's backdrop
		}
	}

	// IN A WORLD, screens that call drawBackground DIRECTLY (1.8.9's Resource Packs
	// screen: drawScreen opens with drawBackground(0), javap) painted an opaque dirt
	// sheet over the game (Will, 2026-10-20 playtest: "the resource pack screen is
	// black"; the clean out-of-world shot is fine, so in-world is the suspect). They get the same in-world backdrop as every other
	// in-world screen instead: drawWorldBackground (vanilla's darkened world, or
	// Menu Blur via the inject above). In a world drawWorldBackground never calls
	// drawBackground, so this cannot recurse.
	@Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressDirt(int tint, CallbackInfo ci) {
		try {
			if (!OriginScreenRenderer.isActive() || OriginStyle.vanillaMenusOn(this)) {
				return;
			}
			if (Minecraft.getMinecraft().theWorld == null) {
				ci.cancel();
				return;
			}
			((GuiScreen) (Object) this).drawWorldBackground(tint);
			ci.cancel();
		} catch (Throwable t) {
			// vanilla's dirt
		}
	}
}
