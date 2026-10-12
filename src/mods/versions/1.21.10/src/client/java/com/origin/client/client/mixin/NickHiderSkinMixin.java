package com.origin.client.client.mixin;

import com.origin.client.client.mods.NickHider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nick Hider skins: PlayerInfo.getSkin() is where both the player model
// (AbstractClientPlayer.getSkin reads its PlayerInfo) and the tab-list heads
// get a skin (1.21.1 source-verified), so one hook covers both. NickHider
// returns null to keep the real one.
@Mixin(PlayerInfo.class)
public abstract class NickHiderSkinMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void originclient$hiddenSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		try {
			PlayerSkin skin = NickHider.skinFor(((PlayerInfo) (Object) this).getProfile());
			if (skin != null) {
				cir.setReturnValue(skin);
			}
		} catch (Throwable t) {
			// fall through to the real skin
		}
	}
}
