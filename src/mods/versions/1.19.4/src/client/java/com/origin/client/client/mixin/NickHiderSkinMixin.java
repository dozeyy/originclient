package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider skins. Pre-1.20.2 a player's skin is two PlayerInfo reads --
// getSkinLocation() (the texture) and getModelName() ("default" / "slim") --
// and both the player model (AbstractClientPlayer.getSkinTextureLocation /
// getModelName read its PlayerInfo) and the tab-list heads go through them
// (decompiled 1.19.4 / 1.19.3 / 1.19.1 / 1.18.1). NickHider answers both from
// one decision, or null to keep the real skin.
@Mixin(PlayerInfo.class)
public abstract class NickHiderSkinMixin {
	@Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenSkin(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			ResourceLocation skin = NickHider.skinFor(((PlayerInfo) (Object) this).getProfile());
			if (skin != null) {
				cir.setReturnValue(skin);
			}
		} catch (Throwable t) {
			// fall through to the real skin
		}
	}

	@Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenModel(CallbackInfoReturnable<String> cir) {
		try {
			String model = NickHider.modelFor(((PlayerInfo) (Object) this).getProfile());
			if (model != null) {
				cir.setReturnValue(model);
			}
		} catch (Throwable t) {
			// fall through to the real model
		}
	}
}
