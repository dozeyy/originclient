package com.origin.client.client.mixin;

import com.origin.client.client.cosmetics.CapeTextures;
import com.origin.client.client.cosmetics.CosmeticsService;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Origin capes ride Minecraft's own cape slot: the skin the game already
 * uses (including Nick Hider's replacement, applied earlier in PlayerInfo)
 * comes back with only its cape swapped for the player's equipped Origin
 * cape. Vanilla's cape layer then draws it with its usual physics, the
 * "Cape" skin option, and the elytra rules all intact.
 *
 * PER-VERSION DELTA (1.21.11): PlayerSkin moved to world.entity.player and is
 * PlayerSkin(body, cape, elytra, model, secure), each texture a
 * ClientAsset.Texture whose texturePath() is the id the renderers bind. Our
 * frames are registered under their own id, so the cape (and the default
 * elytra) are ClientAsset.ResourceTexture(id, texturePath) with both set --
 * the same construction the Capes mod uses on 1.21.9-1.21.11. getSkin is still
 * AbstractClientPlayer's (it implements ClientAvatarEntity.getSkin), and
 * AvatarRenderer copies it into the render state at extract time.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CosmeticCapeMixin {
	// Our cape textures only cover the cape, not the elytra area vanilla capes
	// also hold. With a cape and no elytra texture, vanilla's wings layer would
	// draw the elytra with the cape's texture, so elytra get their normal one.
	private static final Identifier ORIGIN$ELYTRA = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void origin$cosmeticCape(CallbackInfoReturnable<PlayerSkin> cir) {
		try {
			PlayerSkin skin = cir.getReturnValue();
			if (skin == null) {
				return;
			}
			String id = CosmeticsService.lookOf(((AbstractClientPlayer) (Object) this).getUUID()).cape();
			Identifier cape = id != null ? CapeTextures.current(id) : null;
			if (cape == null) {
				return;
			}
			ClientAsset.Texture elytra = skin.elytra() != null ? skin.elytra()
					: new ClientAsset.ResourceTexture(ORIGIN$ELYTRA, ORIGIN$ELYTRA);
			cir.setReturnValue(new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(cape, cape), elytra,
					skin.model(), skin.secure()));
		} catch (Throwable t) {
			// a cosmetic must never break skin lookup
		}
	}
}
