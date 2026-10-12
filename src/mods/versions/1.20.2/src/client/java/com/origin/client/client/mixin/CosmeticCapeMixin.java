package com.origin.client.client.mixin;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.CosmeticsService;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Origin capes ride Minecraft's own cape slot: the skin the game already
 * uses (including Nick Hider's replacement, applied earlier in PlayerInfo)
 * comes back with only its cape swapped for the player's equipped Origin
 * cape (AbstractClientPlayer.getSkin / PlayerSkin exist from 1.20.2, as on
 * 1.21.1). Vanilla's cape layer then draws it with its usual physics, the
 * "Cape" skin option, and the elytra rules all intact.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CosmeticCapeMixin {
	// Our cape textures only cover the cape, not the elytra area vanilla capes
	// also hold, so elytra keep their normal look while an Origin cape is worn.
	private static final ResourceLocation ORIGIN$ELYTRA = new ResourceLocation("textures/entity/elytra.png");

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticCape(CallbackInfoReturnable<PlayerSkin> cir) {
		try {
			PlayerSkin skin = cir.getReturnValue();
			if (skin == null) {
				return;
			}
			String id = CosmeticsService.lookOf(((AbstractClientPlayer) (Object) this).getUUID()).cape();
			ResourceLocation cape = id != null ? CapeTextures.current(id) : null;
			if (cape == null) {
				return;
			}
			ResourceLocation elytra = skin.elytraTexture() != null ? skin.elytraTexture() : ORIGIN$ELYTRA;
			cir.setReturnValue(new PlayerSkin(skin.texture(), skin.textureUrl(), cape, elytra, skin.model(), skin.secure()));
		} catch (Throwable t) {
			// a cosmetic must never break skin lookup
		}
	}
}
