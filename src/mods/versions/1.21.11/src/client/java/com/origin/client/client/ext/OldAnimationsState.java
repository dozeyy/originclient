package com.origin.client.client.ext;

/**
 * Duck interface mixed into vanilla's {@code AvatarRenderState} (OldAnimationsAvatarStateMixin)
 * so 1.7 Animations' Third Person Block can carry its flag from EXTRACTION to SUBMIT.
 *
 * <p>Why: on 1.21.1 ItemInHandLayer.renderArmWithItem still had the LivingEntity and the
 * ItemStack, so the hook could ask "is this player sword blocking?" on the spot. 1.21.9+
 * submits from the baked render state only (no entity), so AvatarRenderer.extractRenderState
 * answers while it still has the player and stores the answer here.
 *
 * <p>Lives in {@code client.ext}, NOT the mixin package: a duck interface inside the mixin
 * package is loaded by the mixin classloader and fails with IllegalClassLoadError at runtime
 * (compiles clean; see the 1.21.4 SDF port notes and ItemScaleState).
 */
public interface OldAnimationsState {
	/** blockTurn: the main-hand sword gets 1.7's block turn (looks-only sword block); false = vanilla. */
	void originclient$setOldBlock(boolean blockTurn);

	boolean originclient$blockTurn();
}
