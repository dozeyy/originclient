package com.origin.client.client.mixin;

import com.origin.client.client.ext.ItemScaleState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Adds the per-item render scale field to ItemEntityRenderState (Item Size
// Customizer). The state is a fresh object per render invocation, so each dropped
// item carries its own scale — set in ItemEntityScaleMixin#extractRenderState,
// read in #render. Defaults to 1.0 (no scaling) so a state that was never touched
// renders at normal size.
@Mixin(ItemEntityRenderState.class)
public class ItemEntityRenderStateMixin implements ItemScaleState {
	@Unique
	private float originclient$itemScale = 1.0f;

	@Override
	public void originclient$setItemScale(float scale) {
		originclient$itemScale = scale;
	}

	@Override
	public float originclient$getItemScale() {
		return originclient$itemScale;
	}
}
