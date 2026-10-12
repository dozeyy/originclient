package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Menu Blur: swaps the PIECES of vanilla's in-world menu backdrop, never the
// whole renderBackground -- ScreenBackgroundMixin hangs the cursor glow off
// renderBackground's TAIL, and cancelling that method would lose it.
// 1.21.8 Screen (source-verified against the decompiled 1.21.6 and 1.21.8 client):
//   renderBackground -> renderBlurredBackground(GuiGraphics) + renderMenuBackground(GuiGraphics)
//   AbstractContainerScreen.renderBackground -> renderTransparentBackground(GuiGraphics)
// (1.21.6 handed renderBlurredBackground the GuiGraphics: the blur is a request
// on the deferred GUI -- see MenuBlur.)
// Each hook asks MenuBlur.handles(screen) first; when it says no (mod off, kind
// toggled off, no world) vanilla runs untouched.
@Mixin(Screen.class)
public class MenuBlurMixin {

	// Pause + in-game menus: Origin's blur strength instead of the vanilla option.
	@Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$menuBlur(GuiGraphics guiGraphics, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (MenuBlur.handles(self)) {
			MenuBlur.blur(guiGraphics);
			ci.cancel();
		}
	}

	// ...and the kind's tint instead of vanilla's in-world menu texture.
	@Inject(method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$menuTint(GuiGraphics guiGraphics, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (MenuBlur.handles(self)) {
			MenuBlur.tint(guiGraphics, self);
			ci.cancel();
		}
	}

	// Inventories/containers: vanilla never blurs these, only darkens with a
	// gradient. Replace that gradient with blur + the inventory tint.
	@Inject(method = "renderTransparentBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$inventoryBlur(GuiGraphics guiGraphics, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (self instanceof AbstractContainerScreen<?> && MenuBlur.handles(self)) {
			MenuBlur.blur(guiGraphics);
			MenuBlur.tint(guiGraphics, self);
			ci.cancel();
		}
	}
}
