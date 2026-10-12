package com.origin.client.client.ext;

/**
 * Duck interface mixed into vanilla's {@code ItemEntityRenderState} so Item
 * Physics can carry the item's physics pose from EXTRACTION (which has the live
 * ItemEntity: onGround / isInWater, and is where the per-item easing state is
 * kept) to SUBMIT (which only has the state).
 * Same reason and same package rule as {@link ItemScaleState}: a duck declared in
 * the mixin package would be loaded by the mixin classloader and fail at runtime.
 */
public interface ItemPhysicsState {
	/** False = leave vanilla's float + spin alone (mod off, or never extracted). */
	boolean originclient$posed();

	/** Degrees about X (the tumble). */
	float originclient$tumble();

	/** Degrees about Z (the per-item tilt of the tumble axis). */
	float originclient$tilt();

	/** Pivot lift above the entity origin, in blocks. */
	float originclient$height();

	void originclient$setPose(boolean posed, float tumble, float tilt, float height);
}
