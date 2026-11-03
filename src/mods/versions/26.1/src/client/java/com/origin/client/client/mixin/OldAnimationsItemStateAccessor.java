package com.origin.client.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.7 Animations -> 1.7 Item Positions, flat-item check (26.1.2 ItemStackRenderState, javap-verified):
//   private int activeLayerCount; private ItemStackRenderState$LayerRenderState[] layers;
// BakedModel.isCustomRenderer() is gone on 26.x; a custom-drawn item (shield, trident, banner,
// chest, head) is now a layer with a special renderer. OldAnimations.isFlat walks the active
// layers through this and OldAnimationsItemLayerAccessor. If these stop applying, the first
// cast throws, the hook's catch latches the mod off, and every hand looks vanilla.
@Mixin(ItemStackRenderState.class)
public interface OldAnimationsItemStateAccessor {
	@Accessor("activeLayerCount")
	int originclient$activeLayerCount();

	@Accessor("layers")
	ItemStackRenderState.LayerRenderState[] originclient$layers();
}
