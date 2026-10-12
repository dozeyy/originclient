package com.origin.client.client.mixin;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.CosmeticsService;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Origin capes ride Minecraft's own cape slot: the cape texture the game asks
 * this player for comes back as their equipped Origin cape. Vanilla's cape
 * layer then draws it with its usual physics, the "Cape" skin option, and the
 * elytra rules all intact.
 *
 * Before 1.20.2 there is no PlayerSkin record to swap a field in (1.21.1's
 * hook): CapeLayer asks AbstractClientPlayer.isCapeLoaded() and
 * getCloakTextureLocation(), and ElytraLayer prefers isElytraLoaded() +
 * getElytraTextureLocation() over the cape. So all four are answered here:
 * the cape slot reports the Origin cape, and while one is worn the elytra slot
 * reports the player's own elytra texture or the default one -- our textures
 * only cover the cape, so elytra keep their normal look (as on 1.21.1).
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CosmeticCapeMixin {
	@Unique
	private static final ResourceLocation ORIGIN$ELYTRA = new ResourceLocation("textures/entity/elytra.png");

	@Unique
	private ResourceLocation originclient$originCape() {
		try {
			String id = CosmeticsService.lookOf(((AbstractClientPlayer) (Object) this).getUUID()).cape();
			return id != null ? CapeTextures.current(id) : null;
		} catch (Throwable t) {
			return null;   // a cosmetic must never break skin lookup
		}
	}

	@Inject(method = "isCapeLoaded", at = @At("RETURN"), cancellable = true)
	private void originclient$capeLoaded(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && originclient$originCape() != null) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getCloakTextureLocation", at = @At("RETURN"), cancellable = true)
	private void originclient$cosmeticCape(CallbackInfoReturnable<ResourceLocation> cir) {
		ResourceLocation cape = originclient$originCape();
		if (cape != null) {
			cir.setReturnValue(cape);
		}
	}

	@Inject(method = "isElytraLoaded", at = @At("RETURN"), cancellable = true)
	private void originclient$elytraLoaded(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && originclient$originCape() != null) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getElytraTextureLocation", at = @At("RETURN"), cancellable = true)
	private void originclient$elytra(CallbackInfoReturnable<ResourceLocation> cir) {
		if (cir.getReturnValue() == null && originclient$originCape() != null) {
			cir.setReturnValue(ORIGIN$ELYTRA);
		}
	}
}
