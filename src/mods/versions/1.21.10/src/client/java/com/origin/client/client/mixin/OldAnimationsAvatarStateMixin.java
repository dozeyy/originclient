package com.origin.client.client.mixin;

import com.origin.client.client.ext.OldAnimationsState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// 1.7 Animations -> Third Person Block, the carrier. Adds two flags to AvatarRenderState
// (1.21.9+: the player render state; OldAnimationsThirdPersonMixin writes them in
// AvatarRenderer.extractRenderState, OldAnimationsHandLayerMixin reads them at submit).
// Default false, so a state that never went through extract (menu previews) draws vanilla.
// If this stops applying, the layer's instanceof check fails and both flags read as off:
// the shield shows and the sword is held like vanilla.
@Mixin(AvatarRenderState.class)
public class OldAnimationsAvatarStateMixin implements OldAnimationsState {
	@Unique
	private boolean originclient$oldHideOffhand;
	@Unique
	private boolean originclient$oldBlockTurn;

	@Override
	public void originclient$setOldAnim(boolean hideOffhand, boolean blockTurn) {
		this.originclient$oldHideOffhand = hideOffhand;
		this.originclient$oldBlockTurn = blockTurn;
	}

	@Override
	public boolean originclient$hideOffhand() {
		return originclient$oldHideOffhand;
	}

	@Override
	public boolean originclient$blockTurn() {
		return originclient$oldBlockTurn;
	}
}
