package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Swing While Using, the held half. 1.8.9 Minecraft (vineflower of the mapped jar):
//   private void sendClickBlockToController(boolean leftClick)
//     if (leftClickCounter <= 0 && !thePlayer.isUsingItem()) { ... onPlayerDamageBlock; swingItem(); }
//   runTick calls it every tick with (currentScreen == null && keyBindAttack.isKeyDown() && inGameHasFocus).
//   -> HEAD: holding attack on a block while blocking/eating/drawing loops the swing, 1.7's
//      block-hit look (1.7's version of this method had no isUsingItem test).
// The swing is OldAnimations.visualSwing: EntityLivingBase.swingItem's body without
// EntityPlayerSP's C0APacketAnimation, so servers see nothing new. Minecraft is partly OptiFine-
// patched, so HEAD only. If it stops applying: no swing while holding, as vanilla 1.8.
// The click half is OldAnimationsKeyBindingMixin.
@Mixin(Minecraft.class)
public abstract class OldAnimationsKeysMixin {
	@Inject(method = "sendClickBlockToController", at = @At("HEAD"))
	private void originclient$holdSwingWhileUsing(boolean leftClick, CallbackInfo ci) {
		try {
			Minecraft mc = (Minecraft) (Object) this;
			if (leftClick && mc.thePlayer != null && mc.thePlayer.isUsingItem() && mc.objectMouseOver != null
					&& mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
					&& OldAnimations.on("swingWhileUsing")) {
				OldAnimations.visualSwing(mc.thePlayer);
			}
		} catch (Throwable t) {
			OldAnimations.fail("swing while using", t);
		}
	}
}
