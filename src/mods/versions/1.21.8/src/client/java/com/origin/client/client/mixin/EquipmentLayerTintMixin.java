package com.origin.client.client.mixin;

import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Hit Color -> Color Armor, part 2 of 2 (part 1: HumanoidArmorLayerMixin).
// PER-VERSION (1.21.2+, verified against the decompiled 1.21.4 and 1.21.8
// EquipmentLayerRenderer): renderLayers(..., @Nullable ResourceLocation) draws
// each dyeable/base armour layer with ONE 5-arg Model.renderToBuffer(pose,
// consumer, light, overlay, colour) call -- the colour 1.21.1's renderModel took
// -- and the trim with the 4-arg overload (not matched here: trims stay as they
// are, like 1.21.1). HitColor.tintArmor is a no-op unless the humanoid armour
// layer is drawing a wearer that is mid-flash.
@Mixin(EquipmentLayerRenderer.class)
public class EquipmentLayerTintMixin {
	@ModifyArg(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/resources/ResourceLocation;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
			index = 4)
	private int originclient$tintArmor(int color) {
		try {
			return HitColor.tintArmor(color);
		} catch (Throwable t) {
			return color;
		}
	}
}
