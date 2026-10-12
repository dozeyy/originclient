package com.origin.client.client.mixin;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.CosmeticsService;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Origin capes ride Minecraft's own cape slot: vanilla's cape layer then draws
 * the player's equipped Origin cape with its usual physics, the "Cape" skin
 * option, and the elytra rules all intact.
 *
 * Pre-1.20.2 era (no PlayerSkin record): the cape layer asks the player itself,
 * `isCapeLoaded() && getCloakTextureLocation() != null` (decompiled 1.16.4 /
 * 1.18.1 CapeLayer), so both answers are swapped here. The elytra layer prefers
 * getElytraTextureLocation() and otherwise wears the CAPE texture -- our cape
 * textures only cover the cape, so while an Origin cape is worn the elytra is
 * pointed at vanilla's own elytra texture (what 1.21.1 does through
 * PlayerSkin.elytraTexture). Nick Hider's skin swap lives on PlayerInfo and is
 * untouched by this.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CosmeticCapeMixin {
	private static final ResourceLocation ORIGIN$ELYTRA = new ResourceLocation("textures/entity/elytra.png");

	/** The Origin cape texture to wear right now, or null for vanilla's. */
	private ResourceLocation origin$cape() {
		String id = CosmeticsService.lookOf(((AbstractClientPlayer) (Object) this).getUUID()).cape();
		return id != null ? CapeTextures.current(id) : null;
	}

	@Inject(method = "getCloakTextureLocation", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticCape(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			ResourceLocation cape = origin$cape();
			if (cape != null) {
				cir.setReturnValue(cape);
			}
		} catch (Throwable t) {
			// a cosmetic must never break skin lookup
		}
	}

	@Inject(method = "isCapeLoaded", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticCapeLoaded(CallbackInfoReturnable<Boolean> cir) {
		try {
			if (!cir.getReturnValueZ() && origin$cape() != null) {
				cir.setReturnValue(true);
			}
		} catch (Throwable t) {
			// keep vanilla's answer
		}
	}

	@Inject(method = "getElytraTextureLocation", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticElytra(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			if (cir.getReturnValue() == null && origin$cape() != null) {
				cir.setReturnValue(ORIGIN$ELYTRA);
			}
		} catch (Throwable t) {
			// keep vanilla's answer
		}
	}

	@Inject(method = "isElytraLoaded", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticElytraLoaded(CallbackInfoReturnable<Boolean> cir) {
		try {
			if (!cir.getReturnValueZ() && origin$cape() != null) {
				cir.setReturnValue(true);
			}
		} catch (Throwable t) {
			// keep vanilla's answer
		}
	}
}
