package com.origin.client.client.mixin;

import com.origin.client.client.ext.OldAnimationsState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// 1.7 Animations -> Third Person Block, the carrier. Adds one flag to AvatarRenderState
// (1.21.9+: the player render state; OldAnimationsThirdPersonMixin writes them in
// AvatarRenderer.extractRenderState, OldAnimationsHandLayerMixin reads them at submit).
// Default false, so a state that never went through extract (menu previews) draws vanilla.
// If this stops applying, the layer's instanceof check fails and the flag reads as off:
// the sword is held like vanilla in F5.
@Mixin(AvatarRenderState.class)
public class OldAnimationsAvatarStateMixin implements OldAnimationsState {
	@Unique
	private boolean originclient$oldBlockTurn;

	@Override
	public void originclient$setOldBlock(boolean blockTurn) {
		this.originclient$oldBlockTurn = blockTurn;
	}

	@Override
	public boolean originclient$blockTurn() {
		return originclient$oldBlockTurn;
	}
}
