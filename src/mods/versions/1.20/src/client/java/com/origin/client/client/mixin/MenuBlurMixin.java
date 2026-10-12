package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Menu Blur: swaps the PIECE of vanilla's in-world menu backdrop, never the
// whole renderBackground -- ScreenBackgroundMixin hangs the cursor glow off
// renderBackground's TAIL, and cancelling that method would lose it.
// 1.20.2-1.20.4 Screen (source-verified): vanilla has no menu blur yet (that is
// 1.20.5). In a world, renderBackground(GuiGraphics, int, int, float) calls
// renderTransparentBackground(GuiGraphics) -- the dark gradient -- for EVERY
// in-world screen: pause, options, and (through AbstractContainerScreen's
// override, which calls super.renderBackground) every inventory/container.
// So this one hook is where all three Menu Blur kinds draw their blur + tint.
// It asks MenuBlur.handles(screen) first; when it says no (mod off, kind
// toggled off, no world) vanilla's gradient draws untouched.
@Mixin(Screen.class)
public class MenuBlurMixin {

	@Inject(method = "renderTransparentBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
	private void originclient$menuBlur(GuiGraphics guiGraphics, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (MenuBlur.handles(self)) {
			MenuBlur.blur(Minecraft.getInstance().getFrameTime());
			MenuBlur.tint(guiGraphics, self);
			ci.cancel();
		}
	}
}
