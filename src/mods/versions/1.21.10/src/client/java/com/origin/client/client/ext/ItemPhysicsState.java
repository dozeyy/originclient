package com.origin.client.client.ext;

/**
 * Duck interface mixed into vanilla's {@code ItemEntityRenderState} so Item
 * Physics can carry "is this item resting?" from EXTRACTION (which has the live
 * ItemEntity: onGround / isInWater) to SUBMIT (which only has the state).
 * Same reason and same package rule as {@link ItemScaleState}: a duck declared in
 * the mixin package would be loaded by the mixin classloader and fail at runtime.
 */
public interface ItemPhysicsState {
	boolean originclient$isResting();

	void originclient$setResting(boolean resting);
}
