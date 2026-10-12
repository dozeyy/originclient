package com.origin.client.client.mixin;

import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Hit Color -> Color Armor, half 2 of 2 (see HumanoidArmorLayerMixin).
// 1.21.11 EquipmentLayerRenderer.renderLayers (the 11-arg overload, source-
// verified) submits each armour layer with order(n).submitModel(model, state,
// pose, armorCutoutNoCull(tex), light, NO_OVERLAY, COLOUR, null, outline, null);
// ordinal 0 is that armour model itself (ordinal 1 is its enchant glint, the
// later one the trim -- both left alone, as on 1.21.1). The colour (argument
// index 6) is pulled toward the hit colour while HumanoidArmorLayerMixin has
// flagged a hurt wearer; HitColor.tintArmor returns it untouched otherwise.
@Mixin(EquipmentLayerRenderer.class)
public class EquipmentLayerTintMixin {
	@ModifyArg(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
					ordinal = 0),
			index = 6)
	private int originclient$tintArmor(int color) {
		try {
			return HitColor.tintArmor(color);
		} catch (Throwable t) {
			return color;
		}
	}
}
