package com.origin.client.client.ext;

/**
 * Duck interface mixed into vanilla's {@code ItemEntityRenderState} so Item
 * Physics knows, at RENDER time, whether the dropped item is resting (on the
 * ground or floating on water) or still in the air.
 *
 * <p>1.21.2+ split entity rendering into an extract pass (has the live
 * {@code ItemEntity}) and a render pass (has only the baked state); vanilla's
 * state carries the bob/spin inputs but not onGround/isInWater, so
 * ItemPhysicsMixin records it during extraction -- together with the item's own
 * easing memory, since one render state is reused for every dropped item. Lives in {@code client.ext},
 * not the mixin package, for the same classloader reason as ItemScaleState.
 */
public interface ItemPhysicsState {
	boolean originclient$isResting();

	void originclient$setResting(boolean resting);

	/** This item's easing memory (ItemPhysics.Anim), or null with the mod off. */
	com.origin.client.client.mods.ItemPhysics.Anim originclient$getAnim();

	void originclient$setAnim(com.origin.client.client.mods.ItemPhysics.Anim anim);
}
