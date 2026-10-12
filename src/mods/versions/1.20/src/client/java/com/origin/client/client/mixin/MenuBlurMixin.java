package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Menu Blur: swaps the PIECE of vanilla's in-world menu backdrop, never the
// whole renderBackground -- ScreenBackgroundMixin hangs the cursor glow off
// renderBackground's TAIL, and cancelling that method would lose it.
// 1.20 / 1.20.1 Screen (verified against decompiled 1.20): vanilla has no menu
// blur yet (that is 1.20.5) and no renderTransparentBackground helper either
// (that is 1.20.2). In a world, renderBackground(GuiGraphics) draws the dark
// backdrop as ONE GuiGraphics.fillGradient(0, 0, width, height, top, bottom)
// call -- for every in-world screen that draws a backdrop: pause, options, and
// every inventory/container (InventoryScreen & co. call renderBackground
// themselves). Out of a world the same method draws dirt instead, which never
// reaches this call. So redirecting that one call is where all three Menu Blur
// kinds draw their blur + tint. It asks MenuBlur.handles(screen) first; when it
// says no (mod off, kind toggled off, no world) vanilla's gradient draws untouched.
@Mixin(Screen.class)
public class MenuBlurMixin {

	@Redirect(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fillGradient(IIIIII)V"))
	private void originclient$menuBlur(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int top, int bottom) {
		Screen self = (Screen) (Object) this;
		boolean handled = false;
		try {
			if (MenuBlur.handles(self)) {
				MenuBlur.blur(Minecraft.getInstance().getFrameTime());
				MenuBlur.tint(guiGraphics, self);
				handled = true;
			}
		} catch (Throwable t) {
			// fall back to vanilla's gradient below
		}
		if (!handled) {
			guiGraphics.fillGradient(x0, y0, x1, y1, top, bottom);
		}
	}
}
