package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin background (charcoal + rings + grain) behind every out-of-world
// menu -- options, world select, server list, create world, etc. -- so the
// whole menu tree reads as one interface with the main menu. The website's
// mouse-follow spotlight is drawn on EVERY menu (Will), right after the
// backdrop so it sits over the background but under the widgets that render
// afterward.
//
// Background replacement is gated on Minecraft.level == null: in-game screens
// (pause, in-game options) keep vanilla's blurred-world backdrop -- replacing
// that would hide the world the player is standing in. The cursor glow is NOT
// gated: out-of-world it draws inside the cancel path, in-world via the TAIL
// hook over vanilla's blur (TAIL never runs when HEAD cancelled, so the two
// paths are exclusive and the glow draws exactly once either way).
//
// Two hooks (both javap-confirmed against the mapped 1.21.1 Screen):
//  - renderBackground(GuiGraphics,int,int,float): the screen-level backdrop
//    (panorama + blur + menu texture). Cancelled and replaced wholesale.
//    TitleScreen overrides this method, so its own mixin path (which already
//    draws the glow) is unaffected -- no double glow there.
//  - renderMenuBackgroundTexture(...): the static helper option/selection
//    LISTS use to tile their darker strip behind rows. Cancelled so lists sit
//    transparently on the Origin background instead of vanilla's texture.
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackdrop(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Cancel vanilla's backdrop only when the Origin one actually drew
		// (fail-soft contract): a failed draw releases the vanilla backdrop
		// instead of leaving the screen black.
		if (Minecraft.getInstance().level == null
				&& OriginScreenRenderer.renderTitleBackground(guiGraphics)) {
			OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
			ci.cancel();
		}
	}

	// In-world menus (pause, in-game options): vanilla's blurred-world backdrop
	// stays, the spotlight draws on top of it.
	@Inject(method = "renderBackground", at = @At("TAIL"))
	private void originclient$inWorldGlow(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
	}

	@Inject(method = "renderMenuBackgroundTexture", at = @At("HEAD"), cancellable = true)
	private static void originclient$noListTexture(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, float uOffset, float vOffset, int width, int height, CallbackInfo ci) {
		// Gated on renderer health: if the Origin backdrop is broken, lists
		// get their vanilla strip back along with the vanilla background.
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	// Same hover test the title screen uses: bloom the spotlight while any
	// visible widget is hovered, matching the website's hover targets.
	private boolean originclient$hoveringClickable() {
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				return true;
			}
		}
		return false;
	}
}
