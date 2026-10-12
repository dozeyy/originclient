package com.origin.client.client.mixin;

import com.origin.client.client.ext.ItemPhysicsState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Carries Item Physics' resting flag and its per-item key (the entity) on the
// dropped-item render state (see ItemPhysicsState). Default false = airborne until extraction says otherwise.
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
	private ItemEntity originclient$entity = null;

	@Override
	public ItemEntity originclient$entity() {
		return originclient$entity;
	}

	@Override
	public void originclient$setEntity(ItemEntity entity) {
		this.originclient$entity = entity;
	}
}
