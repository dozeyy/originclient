package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hit Color -> Color Armor, part 1 of 2. PER-VERSION (1.21.2+ render-state era,
// verified against the decompiled 1.21.4 and 1.21.8 HumanoidArmorLayer):
//   render(PoseStack, MultiBufferSource, int light, S state, float, float)
//     -> renderArmorPiece x4 -> EquipmentLayerRenderer.renderLayers(...)
// The layer no longer sees the entity, only its render state; the state's
// hasRedOverlay is vanilla's own "mid-flash" flag (hurtTime > 0 || deathTime > 0,
// LivingEntityRenderer.extractRenderState) -- the same test 1.21.1's hook made.
// It is held for exactly the span of this layer (cleared at RETURN), so the
// shared EquipmentLayerRenderer's other users (elytra wings, horse/wolf armour)
// are never tinted. Part 2 (the tint itself) is EquipmentLayerTintMixin.
// S erases to HumanoidRenderState; the full descriptor keeps the injector off
// the synthetic render(..., EntityRenderState, ...) bridge.
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
			at = @At("HEAD"))
	private void originclient$recordHurt(PoseStack poseStack, MultiBufferSource buffer, int light,
										 HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		HitColor.armorHurt = state.hasRedOverlay;
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
			at = @At("RETURN"))
	private void originclient$clearHurt(PoseStack poseStack, MultiBufferSource buffer, int light,
										HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		HitColor.armorHurt = false;
	}
}
