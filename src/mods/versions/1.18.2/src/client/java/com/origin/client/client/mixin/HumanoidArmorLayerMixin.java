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

// Hit Color -> Color Armor. Pre-1.21 shape (decompiled 1.19.4 / 1.19.3 / 1.19.1 /
// 1.18.1 HumanoidArmorLayer, identical on all four):
//   renderArmorPiece(PoseStack, MultiBufferSource, T entity, EquipmentSlot, int light, A model)
//     -> renderModel(PoseStack, MultiBufferSource, int light, ArmorItem, boolean glint,
//                    A model, boolean inner, float r, float g, float b, @Nullable String overlay)
// renderModel never sees the entity, so renderArmorPiece's HEAD records whether
// the wearer is mid-flash, and renderModel's three tint floats (leather dye, or
// 1.0 for every other armor) are pulled toward the hit colour on the way in.
// The 1.19.4 trim pass (renderTrim) and the enchant glint are left alone.
// T erases to LivingEntity and A to HumanoidModel.
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

	@Inject(method = "renderArmorPiece", at = @At("HEAD"))
	private void originclient$recordHurt(PoseStack poseStack, MultiBufferSource buffer, LivingEntity entity,
										 EquipmentSlot slot, int light, HumanoidModel<?> model, CallbackInfo ci) {
		HitColor.armorHurt = entity.hurtTime > 0 || entity.deathTime > 0;
	}

	@ModifyVariable(method = "renderModel", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private float originclient$tintRed(float red) {
		return HitColor.tintArmor(red, 0);
	}

	@ModifyVariable(method = "renderModel", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private float originclient$tintGreen(float green) {
		return HitColor.tintArmor(green, 1);
	}

	@ModifyVariable(method = "renderModel", at = @At("HEAD"), ordinal = 2, argsOnly = true)
	private float originclient$tintBlue(float blue) {
		return HitColor.tintArmor(blue, 2);
	}
}
