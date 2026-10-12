package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider skins, pre-PlayerSkin era. 1.20 / 1.20.1 have no PlayerSkin record
// (that arrived in 1.20.2): PlayerInfo hands out the skin as four separate
// getters (verified against decompiled 1.20 PlayerInfo) -- getSkinLocation(),
// getModelName() ("default" / "slim"), getCapeLocation(), getElytraLocation().
// Every reader goes through them: the player model (AbstractClientPlayer.
// getSkinTextureLocation / getModelName, which picks the wide or slim renderer),
// the cape + elytra layers, and the tab-list heads. Hooking all four keeps the
// texture, arm width and cape of one skin together -- the same thing 1.21.1's
// single getSkin() hook swaps in one go. NickHider returns null to keep the real one.
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

	@Inject(method = {"getCapeLocation", "getElytraLocation"}, at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenCape(CallbackInfoReturnable<ResourceLocation> cir) {
		try {
			ResourceLocation[] cape = NickHider.capeFor(((PlayerInfo) (Object) this).getProfile());
			if (cape != null) {
				cir.setReturnValue(cape[0]);   // null = no cape, as with a default skin
			}
		} catch (Throwable t) {
			// fall through to the real cape
		}
	}
}
