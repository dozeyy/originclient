package com.origin.client.client.ext;

import net.minecraft.world.entity.HumanoidArm;

/**
 * 1.7 Animations' third-person facts, carried on PlayerRenderState (OldAnimationsStateMixin).
 * Set in PlayerRenderer.extractRenderState (where the player still exists), read in
 * ItemInHandLayer.renderArmWithItem (which only sees the state). Lives outside the mixin
 * package: a duck interface inside it is an IllegalClassLoadError at first use (1.21.4).
 */
public interface OldAnimationsState {
	/** The arm whose item (the off-hand shield next to a sword) isn't drawn, or null. */
	HumanoidArm originclient$oldHideArm();

	/** The sword arm that gets 1.7's block turn this frame, or null. */
	HumanoidArm originclient$oldTurnArm();

	void originclient$setOldArms(HumanoidArm hide, HumanoidArm turn);
}
