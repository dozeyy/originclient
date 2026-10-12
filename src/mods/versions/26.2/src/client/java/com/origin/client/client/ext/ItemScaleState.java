package com.origin.client.client.ext;

/**
 * Duck interface mixed into vanilla's {@code ItemEntityRenderState} so the Item
 * Size Customizer can carry a per-item render scale from EXTRACTION to SUBMIT.
 *
 * <p>Why this exists at all (1.21.11): on 1.21.1 the whole dropped-item render
 * happened in one call that still had the live {@code ItemEntity}, so the mixin
 * could look the item's id up on the spot. This era split entity rendering into
 * an extract pass (which has the entity) and a submit pass (which has only the
 * baked render state, and no way back to the ItemStack). The scale therefore has
 * to be computed during extraction and stashed on the state.
 *
 * <p>This lives in {@code client.ext}, NOT in the mixin package, deliberately:
 * a duck interface declared inside the mixin package is loaded by the mixin
 * classloader and blows up with {@code IllegalClassLoadError} at runtime — a
 * wedge that compiles perfectly cleanly (see the 1.21.4 SDF port notes).
 */
public interface ItemScaleState {
	float originclient$getItemScale();

	void originclient$setItemScale(float scale);
}
