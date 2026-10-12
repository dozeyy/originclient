package com.origin.client.client.mixin;

import com.origin.client.client.ext.ItemPhysicsState;
import com.origin.client.client.mods.ItemPhysics;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Carries Item Physics' resting flag on the dropped-item render state (see
// ItemPhysicsState) plus the item's easing memory. Default false = airborne until
// extraction says otherwise; a null anim = draw vanilla.
@Mixin(ItemEntityRenderState.class)
public class ItemPhysicsStateMixin implements ItemPhysicsState {
	@Unique
	private boolean originclient$resting = false;

	@Override
	public boolean originclient$isResting() {
		return originclient$resting;
	}

	@Override
	public void originclient$setResting(boolean resting) {
		this.originclient$resting = resting;
	}

	@Unique
	private ItemPhysics.Anim originclient$anim = null;

	@Override
	public ItemPhysics.Anim originclient$getAnim() {
		return originclient$anim;
	}

	@Override
	public void originclient$setAnim(ItemPhysics.Anim anim) {
		this.originclient$anim = anim;
	}
}
