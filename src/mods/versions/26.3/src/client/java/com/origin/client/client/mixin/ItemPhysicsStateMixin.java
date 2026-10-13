package com.origin.client.client.mixin;

import com.origin.client.client.ext.ItemPhysicsState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Carries Item Physics' pose on the dropped-item render state (see the
// ItemPhysicsState duck). Default posed = false: a state that never went through
// ItemPhysicsMixin's extract hook keeps vanilla's float + spin.
@Mixin(ItemEntityRenderState.class)
public class ItemPhysicsStateMixin implements ItemPhysicsState {
	@Unique
	private boolean originclient$posed = false;
	@Unique
	private float originclient$tumble, originclient$tilt, originclient$height;

	@Override
	public boolean originclient$posed() {
		return originclient$posed;
	}

	@Override
	public float originclient$tumble() {
		return originclient$tumble;
	}

	@Override
	public float originclient$tilt() {
		return originclient$tilt;
	}

	@Override
	public float originclient$height() {
		return originclient$height;
	}

	@Override
	public void originclient$setPose(boolean posed, float tumble, float tilt, float height) {
		this.originclient$posed = posed;
		this.originclient$tumble = tumble;
		this.originclient$tilt = tilt;
		this.originclient$height = height;
	}
}
