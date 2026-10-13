package com.origin.client.client.mixin.social;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.CosmeticsService;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Origin capes ride Minecraft's own cape slot: vanilla's cape layer then draws
 * the player's equipped Origin cape with its usual physics and the "Cape" skin
 * option intact.
 *
 * 1.8.9: LayerCape asks the player for getLocationCape() (non-null = draw it).
 * OptiFine REPLACES AbstractClientPlayer and answers that same method with its
 * own capes first, so this is a RETURN injection: whatever OptiFine or vanilla
 * decided, an equipped Origin cape wins; with none equipped their answer
 * stands. There is no elytra on 1.8.9, so 1.16.5's elytra overrides are gone.
 * Nick Hider's skin swap lives on NetworkPlayerInfo and is untouched by this.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CosmeticCapeMixin {
	@Inject(method = "getLocationCape", at = @At("RETURN"), cancellable = true)
	private void originclient$cosmeticCape(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			String id = CosmeticsService.lookOf(((AbstractClientPlayer) (Object) this).getUniqueID()).cape();
			ResourceLocation cape = id != null ? CapeTextures.current(id) : null;
			if (cape != null) {
				cir.setReturnValue(cape);
			}
		} catch (Throwable t) {
			// a cosmetic must never break skin lookup
		}
	}
}
