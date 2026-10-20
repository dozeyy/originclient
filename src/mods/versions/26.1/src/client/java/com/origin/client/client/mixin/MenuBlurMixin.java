package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Menu Blur: swaps the PIECES of vanilla's in-world menu backdrop, never the
// whole renderBackground -- ScreenBackgroundMixin hangs the cursor glow off
// renderBackground's TAIL, and cancelling that method would lose it.
// 1.21.11 Screen (source-verified against the decompiled class):
//   renderBackground -> isInGameUi() ? renderTransparentBackground(GuiGraphicsExtractor)
//                     : renderBlurredBackground(GuiGraphicsExtractor) + renderMenuBackground(GuiGraphicsExtractor)
//   (AbstractContainerScreen.isInGameUi() is true -> containers get the gradient)
// Each hook asks MenuBlur.handles(screen) first; when it says no (mod off, kind
// toggled off, no world) vanilla runs untouched. The strength itself goes in
// through MenuBlurRadiusMixin (the blur radius is a global uniform here).
@Mixin(Screen.class)
public class MenuBlurMixin {

	// Pause + in-game menus: Origin's blur marker (and strength) instead of the vanilla option.
	@Inject(method = "extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$menuBlur(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		// One blur per frame (GuiRenderState throws on a second). A frame that is
		// already blurred skips this one instead of crashing: 26.x's Friends
		// overlay blurs after redrawing a parent whose Origin backdrop blurred.
		if (com.origin.client.client.render.OriginScreenRenderer.frameAlreadyBlurred(guiGraphics)) {
			ci.cancel();
			return;
		}
		Screen self = (Screen) (Object) this;
		if (!OriginStyle.vanillaMenusOn(self) && MenuBlur.handles(self)) {
			MenuBlur.blur(guiGraphics);
			ci.cancel();
		}
	}

	// ...and the kind's tint instead of vanilla's in-world menu texture.
	@Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$menuTint(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!OriginStyle.vanillaMenusOn(self) && MenuBlur.handles(self)) {
			MenuBlur.tint(guiGraphics, self);
			ci.cancel();
		}
	}

	// Inventories/containers: vanilla never blurs these, only darkens with a
	// gradient. Replace that gradient with blur + the inventory tint.
	@Inject(method = "extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$inventoryBlur(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (self instanceof AbstractContainerScreen<?> && !OriginStyle.vanillaMenusOn(self) && MenuBlur.handles(self)) {
			MenuBlur.blur(guiGraphics);
			MenuBlur.tint(guiGraphics, self);
			ci.cancel();
		}
	}
}
