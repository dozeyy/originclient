package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// In-world menus never blur (DECISIONS 2026-11-04) -- Iris's Shader Packs screen included.
// Iris overrides Screen's blur method on that screen without calling super, so
// MenuBlurMixin never runs there, and Iris blurs at min(Menu Background Blurriness, its own
// fade-in). Its blur reads this vanilla option, so the option is the one hook every Iris
// era shares (1.21.1 processFixedBlur, 1.21.4 renderBlurredBackground(), 26.x
// extractBlurredBackground): a vanilla method, remapped like any other, no Iris mixin to
// break. MenuBlur.irisOptionRadius changes the value only while Iris's screen is open.
@Mixin(Options.class)
public class IrisBlurOptionMixin {
	@Inject(method = "getMenuBackgroundBlurriness", at = @At("RETURN"), cancellable = true)
	private void originclient$irisBlur(CallbackInfoReturnable<Integer> cir) {
		try {
			int vanilla = cir.getReturnValue();
			int radius = MenuBlur.irisOptionRadius(Minecraft.getInstance().screen, vanilla);
			if (radius != vanilla) {
				cir.setReturnValue(radius);
			}
		} catch (Throwable t) {
			// the player's own option stands
		}
	}
}
