package com.origin.client.client.mixin;

import com.origin.client.client.ext.OldAnimationsState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// 1.7 Animations -> Third Person Block, the carrier. 1.21.2+ render-state era: ItemInHandLayer
// gets PlayerRenderState (javap: renderArmWithItem(S extends ArmedEntityRenderState,
// ItemStackRenderState, HumanoidArm, ...)), never the player, so OldAnimationsThirdPersonMixin
// writes the hide/turn arms here at extract time. Both null = vanilla, so a state that never
// went through the extract hook draws vanilla. If this stops applying, the layer's
// instanceof check fails and third person stays vanilla (the HOOKS row catches it in CI).
@Mixin(PlayerRenderState.class)
public class OldAnimationsStateMixin implements OldAnimationsState {
	@Unique
	private HumanoidArm originclient$hideArm;
	@Unique
	private HumanoidArm originclient$turnArm;

	@Override
	public HumanoidArm originclient$oldHideArm() {
		return originclient$hideArm;
	}

	@Override
	public HumanoidArm originclient$oldTurnArm() {
		return originclient$turnArm;
	}

	@Override
	public void originclient$setOldArms(HumanoidArm hide, HumanoidArm turn) {
		originclient$hideArm = hide;
		originclient$turnArm = turn;
	}
}
