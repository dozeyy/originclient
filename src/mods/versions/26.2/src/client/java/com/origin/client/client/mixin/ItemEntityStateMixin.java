package com.origin.client.client.mixin;

import com.origin.client.client.ext.ItemScaleState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Carries the Item Size Customizer's per-item scale on the dropped-item render
// state, so ItemEntityScaleMixin can compute it in extractRenderState (where the
// ItemStack still exists) and apply it in submit (where it no longer does).
// Default 1.0 so a state that never went through our extract hook renders vanilla.
@Mixin(ItemEntityRenderState.class)
public class ItemEntityStateMixin implements ItemScaleState {
	@Unique
	private float originclient$itemScale = 1.0f;

	@Override
	public float originclient$getItemScale() {
		return originclient$itemScale;
	}

	@Override
	public void originclient$setItemScale(float scale) {
		this.originclient$itemScale = scale;
	}
}
