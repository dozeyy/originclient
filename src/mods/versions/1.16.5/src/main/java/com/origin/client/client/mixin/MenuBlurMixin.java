package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Menu Blur. 1.16.5 Screen (decompiled 1.16.4):
//   renderBackground(PoseStack)      -> renderBackground(PoseStack, 0)
//   renderBackground(PoseStack, int) -> in a world: fillGradient(dark); else dirt
// Every in-world screen -- pause, options AND every container screen -- gets its
// darkened backdrop from that 2-arg method, so swapping it covers all three kinds.
// Only the 2-arg is cancelled: ScreenBackgroundMixin hangs the cursor glow off
// the 1-arg's TAIL, which still runs. MenuBlur.handles(screen) says no (mod off,
// kind toggled off, no world) -> vanilla runs untouched.
@Mixin(Screen.class)
public class MenuBlurMixin {

	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("HEAD"), cancellable = true)
	private void originclient$menuBlur(PoseStack poseStack, int vOffset, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		try {
			if (MenuBlur.handles(self)) {
				MenuBlur.blur(Minecraft.getInstance().getFrameTime());
				MenuBlur.tint(new Gfx(poseStack), self);
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla's darkened backdrop
		}
	}
}
