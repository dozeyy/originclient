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
 * Origin capes ride Minecraft's own cape slot: the player's skin (including
 * Nick Hider's replacement, applied earlier in PlayerInfo) is untouched and
 * only the cape is swapped for the player's equipped Origin cape. Vanilla's
 * cape layer then draws it with its usual physics, the "Cape" skin option,
 * and the elytra rules all intact.
 *
 * PER-VERSION (1.20 / 1.20.1): there is no PlayerSkin record yet (1.20.2+), so
 * 1.21.1's single getSkin() swap becomes four AbstractClientPlayer getters,
 * the ones CapeLayer and ElytraLayer read: isCapeLoaded + getCloakTextureLocation
 * (the cape) and isElytraLoaded + getElytraTextureLocation (the elytra).
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CosmeticCapeMixin {
	// Our cape textures only cover the cape, not the elytra area vanilla capes
	// also hold, so elytra keep their normal look while an Origin cape is worn.
	@Unique
	private static final ResourceLocation ORIGIN$ELYTRA = new ResourceLocation("textures/entity/elytra.png");

	/** The equipped Origin cape's texture this frame, or null. */
	@Unique
	private ResourceLocation origin$cape() {
		try {
			String id = CosmeticsService.lookOf(((AbstractClientPlayer) (Object) this).getUUID()).cape();
			return id != null ? CapeTextures.current(id) : null;
		} catch (Throwable t) {
			return null;   // a cosmetic must never break skin lookup
		}
	}

	@Inject(method = "isCapeLoaded", at = @At("RETURN"), cancellable = true)
	private void origin$capeLoaded(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && origin$cape() != null) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getCloakTextureLocation", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticCape(CallbackInfoReturnable<ResourceLocation> cir) {
		ResourceLocation cape = origin$cape();
		if (cape != null) {
			cir.setReturnValue(cape);
		}
	}

	// ElytraLayer falls back to the cape texture when the elytra slot is empty;
	// with an Origin cape on, keep the elytra slot filled so it never does.
	@Inject(method = "isElytraLoaded", at = @At("RETURN"), cancellable = true)
	private void origin$elytraLoaded(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && origin$cape() != null) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getElytraTextureLocation", at = @At("RETURN"), cancellable = true)
	private void origin$elytra(CallbackInfoReturnable<ResourceLocation> cir) {
		if (cir.getReturnValue() == null && origin$cape() != null) {
			cir.setReturnValue(ORIGIN$ELYTRA);
		}
	}
}
