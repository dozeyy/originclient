package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider skins. 1.16.5 has no PlayerSkin record: a PlayerInfo hands out its
// skin as getSkinLocation() (the texture) + getModelName() ("default"/"slim"),
// and both the player model (AbstractClientPlayer.getSkinTextureLocation /
// getModelName read their PlayerInfo) and the tab-list heads ask it there
// (decompiled 1.16.4). Both answers are swapped together so
// the arms match the texture. NickHider returns null to keep the real one.
@Mixin(PlayerInfo.class)
public abstract class NickHiderSkinMixin {

	@Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenSkin(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			NickHider.Skin skin = NickHider.skinFor(((PlayerInfo) (Object) this).getProfile());
			if (skin != null) {
				cir.setReturnValue(skin.texture());
			}
		} catch (Throwable t) {
			// fall through to the real skin
		}
	}

	@Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenModel(CallbackInfoReturnable<String> cir) {
		try {
			NickHider.Skin skin = NickHider.skinFor(((PlayerInfo) (Object) this).getProfile());
			if (skin != null) {
				cir.setReturnValue(skin.model());
			}
		} catch (Throwable t) {
			// fall through to the real model
		}
	}
}
