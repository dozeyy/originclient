package com.origin.client.client.mixin;

import com.origin.client.client.mods.MenuBlur;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Menu Blur's strength. PER-VERSION (1.21.6-1.21.8, verified against the
// decompiled 1.21.6 and 1.21.8 GameRenderer / GlobalSettingsUniform): there is
// no blurEffect field to drive as on 1.21.1, and processBlurEffect() takes no
// radius -- the "blur" post chain reads it from the frame's Globals uniform,
// written once at the top of GameRenderer.render by
//   globalSettingsUniform.update(width, height, glintStrength, gameTime,
//                                deltaTracker, options.getMenuBackgroundBlurriness())
// Its last argument (index 5) is swapped for MenuBlur.radiusFor(vanilla): Origin's
// strength while Menu Blur draws the screen about to render, the option otherwise.
@Mixin(GameRenderer.class)
public class MenuBlurRadiusMixin {
	@ModifyArg(method = "render",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;I)V"),
			index = 5)
	private int originclient$menuBlurRadius(int radius) {
		try {
			return MenuBlur.radiusFor(radius);
		} catch (Throwable t) {
			return radius;
		}
	}
}
