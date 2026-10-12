package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HitColor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hit Color -> Color Armor. 1.21.1 shape (source-verified):
//   renderArmorPiece(PoseStack, MultiBufferSource, T entity, EquipmentSlot, int light, A model)
//     -> renderModel(PoseStack, MultiBufferSource, int light, A model, int color, ResourceLocation)
// renderModel never sees the entity, so renderArmorPiece's HEAD records whether
// the wearer is mid-flash, and renderModel's colour argument (the 2nd int: light
// is the 1st) is tinted on the way in. Trims and the enchant glint are left alone.
// T erases to LivingEntity and A to HumanoidModel.
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

	@Inject(method = "renderArmorPiece", at = @At("HEAD"))
	private void originclient$recordHurt(PoseStack poseStack, MultiBufferSource buffer, LivingEntity entity,
										 EquipmentSlot slot, int light, HumanoidModel<?> model, CallbackInfo ci) {
		HitColor.armorHurt = entity.hurtTime > 0 || entity.deathTime > 0;
	}

	@ModifyVariable(method = "renderModel", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private int originclient$tintArmor(int color) {
		return HitColor.tintArmor(color);
	}
}
