package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Menu Blur. 1.19.3 Screen (decompiled): renderBackground(PoseStack) calls
// renderBackground(PoseStack, 0), which is
//   if (level != null) this.fillGradient(pose, 0, 0, width, height, 0xC0101010, 0xD0101010);
//   else renderDirtBackground(vOffset);
// -- the one dark gradient EVERY in-world screen gets (pause, options and every
// container alike; no blur and no separate container backdrop before 1.20.5).
// That single fillGradient call (an instance method here; static from 1.19.4)
// is redirected: when MenuBlur.handles(screen)
// it blurs the world and draws the kind's tint instead, otherwise the gradient
// is drawn exactly as vanilla would. Only the call is swapped, never the method,
// so ScreenBackgroundMixin's cursor glow at renderBackground's TAIL still runs.
@Mixin(Screen.class)
public class MenuBlurMixin {

	@Redirect(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;I)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/screens/Screen;fillGradient(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private void originclient$menuBlur(Screen receiver, PoseStack poseStack, int x1, int y1, int x2, int y2, int from, int to) {
		Screen self = (Screen) (Object) this;
		try {
			if (MenuBlur.handles(self)) {
				MenuBlur.blur(Minecraft.getInstance().getFrameTime());
				MenuBlur.tint(new Gfx(poseStack), self);
				return;
			}
		} catch (Throwable t) {
			// fall through to vanilla's gradient
		}
		// Vanilla's own gradient (Gfx.fillGradient is the same quad, z 0).
		new Gfx(poseStack).fillGradient(x1, y1, x2, y2, from, to);
	}
}
