package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Third Person Blocking. 1.8.9 LayerHeldItem (vineflower of the mapped jar):
//   public void doRenderLayer(EntityLivingBase, float, float, float partialTicks, float, float,
//       float, float scale)
//     body: push; [child scale]; ((ModelBiped) getMainModel()).postRenderArm(0.0625F);
//     translate(-0.0625, 0.4375, 0.0625); [rod / skull]; [sneak]; renderItem(THIRD_PERSON); pop
//   -> right after postRenderArm: 1.7's block turn so a blocking player's sword lies across the
//      body (1.8 dropped it). The arm itself is already 1.21.1's BLOCK pose in 1.8 (ModelBiped
//      heldItemRight 3), and 1.8.9 has no shield to hide, so this is the whole switch here.
// LayerHeldItem is not an OptiFine-replaced class; the INVOKE target is the one cast call in the
// body. If it stops applying: the sword is held like vanilla 1.8 while blocking.
@Mixin(LayerHeldItem.class)
public abstract class OldAnimationsHandLayerMixin {
	@Inject(method = "doRenderLayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/ModelBiped;postRenderArm(F)V", shift = At.Shift.AFTER))
	private void originclient$blockTurn(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
										float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
										float scale, CallbackInfo ci) {
		try {
			OldAnimations.thirdPersonBlock(entity);
		} catch (Throwable t) {
			OldAnimations.fail("third-person sword", t);
		}
	}
}
