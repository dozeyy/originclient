package com.origin.client.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.7 Animations -> 1.7 Item Positions, flat-item check (26.3 ItemStackRenderState$LayerRenderState,
// javap-verified): private SpecialModelRenderer<Object> specialRenderer; non-null = the item is
// drawn by a special (custom) renderer, which 1.21.1 read as BakedModel.isCustomRenderer().
// Such items stay vanilla. If this stops applying, the cast throws, the mod latches off (vanilla).
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface OldAnimationsItemLayerAccessor {
	@Accessor("specialRenderer")
	SpecialModelRenderer<Object> originclient$specialRenderer();
}
