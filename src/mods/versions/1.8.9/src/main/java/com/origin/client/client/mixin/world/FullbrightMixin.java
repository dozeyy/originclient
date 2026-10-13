package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Full Bright: 1.8.9's EntityRenderer.updateLightmap reads gammaSetting and does
// NOT clamp it, so a high value brightens the whole lightmap. The boosted gamma
// is swapped in for that one method only (HEAD) and the player's own value put
// back at RETURN (WorldHooks.beginLightmap / endLightmap) -- options.txt never
// sees it, and turning the mod off is instant. HEAD/RETURN: EntityRenderer is
// OptiFine-replaced (OptiFine's lightmap reads the same option).
@Mixin(EntityRenderer.class)
public abstract class FullbrightMixin {
	@Inject(method = "updateLightmap", at = @At("HEAD"))
	private void originclient$boostGamma(float partialTicks, CallbackInfo ci) {
		try {
			WorldHooks.beginLightmap(Minecraft.getMinecraft().gameSettings);
		} catch (Throwable ignored) {
			// vanilla gamma
		}
	}

	@Inject(method = "updateLightmap", at = @At("RETURN"))
	private void originclient$restoreGamma(float partialTicks, CallbackInfo ci) {
		try {
			WorldHooks.endLightmap(Minecraft.getMinecraft().gameSettings);
		} catch (Throwable ignored) {
			// nothing was swapped
		}
	}
}
