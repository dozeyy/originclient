package com.origin.client.client.ext;

/**
 * Duck interface mixed into vanilla's {@code AvatarRenderState} (OldAnimationsAvatarStateMixin)
 * so 1.7 Animations' Third Person Block can carry two flags from EXTRACTION to SUBMIT.
 *
 * <p>Why: on 1.21.1 ItemInHandLayer.renderArmWithItem still had the LivingEntity and the
 * ItemStack, so the hook could ask "is this the off-hand shield next to a sword?" on the spot.
 * 1.21.9+ submits from the baked render state only (an ItemStackRenderState, no stack, no
 * entity), so AvatarRenderer.extractRenderState answers both questions while it still has the
 * player and stores the answers here.
 *
 * <p>Lives in {@code client.ext}, NOT the mixin package: a duck interface inside the mixin
 * package is loaded by the mixin classloader and fails with IllegalClassLoadError at runtime
 * (compiles clean; see the 1.21.4 SDF port notes and ItemScaleState).
 */
public interface OldAnimationsState {
	/** hideOffhand: the off-hand shield next to a sword is not drawn; blockTurn: the main-hand
	 *  sword gets 1.7's block turn. Both false = vanilla. */
	void originclient$setOldAnim(boolean hideOffhand, boolean blockTurn);

	boolean originclient$hideOffhand();

	boolean originclient$blockTurn();
}
