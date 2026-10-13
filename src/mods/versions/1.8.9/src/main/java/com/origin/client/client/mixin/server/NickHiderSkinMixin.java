package com.origin.client.client.mixin.server;

import com.origin.client.client.mods.NickHider;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider skins. 1.8.9 has no PlayerSkin record: a NetworkPlayerInfo hands
// out its skin as getLocationSkin() (the texture) + getSkinType() ("default" /
// "slim"), and both the player model (AbstractClientPlayer.getLocationSkin /
// getSkinType read their NetworkPlayerInfo; RenderManager picks the arm model
// from getSkinType) and the tab-list heads ask it there. Both answers are swapped
// together so the arms match the texture. NickHider returns null to keep the
// real one. NetworkPlayerInfo is not OptiFine-replaced; HEAD + cancel anyway.
@Mixin(NetworkPlayerInfo.class)
public abstract class NickHiderSkinMixin {

	@Inject(method = "getLocationSkin", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenSkin(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			NickHider.Skin skin = NickHider.skinFor(((NetworkPlayerInfo) (Object) this).getGameProfile());
			if (skin != null) {
				cir.setReturnValue(skin.texture());
			}
		} catch (Throwable t) {
			// fall through to the real skin
		}
	}

	@Inject(method = "getSkinType", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenModel(CallbackInfoReturnable<String> cir) {
		try {
			NickHider.Skin skin = NickHider.skinFor(((NetworkPlayerInfo) (Object) this).getGameProfile());
			if (skin != null) {
				cir.setReturnValue(skin.model());
			}
		} catch (Throwable t) {
			// fall through to the real model
		}
	}
}
