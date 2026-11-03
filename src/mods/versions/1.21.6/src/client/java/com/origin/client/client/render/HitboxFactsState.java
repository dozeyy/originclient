package com.origin.client.client.render;

/**
 * The Hitbox mod's live-entity facts, stored ON the EntityRenderState itself
 * (mixin/EntityRenderStateHitboxMixin adds the fields; HitboxFacts fills and
 * reads them). No side map: 1.21.10 builds a new render state per entity per
 * frame, and a map entry (plus a facts object) per state per frame was garbage
 * behind a lock for as long as hitboxes were on.
 *
 * Lives outside the mixin package on purpose: a class in a mixin package can't
 * be loaded as a normal class (IllegalClassLoadError), and every caller casts to
 * this interface.
 */
public interface HitboxFactsState {

	/** The Mod Menu switch this entity belongs to; null = never captured (the draw falls back to the type). */
	String origin$hitboxCategory();

	/** Hurt flash running (LivingEntity.hurtTime > 0). */
	boolean origin$hitboxDamaged();

	/** It is what your crosshair is on. */
	boolean origin$hitboxHittable();

	/** It is you: your own look line only ever shows as an Iris shadow, so it's skipped. */
	boolean origin$hitboxSelf();

	void origin$setHitboxFacts(String category, boolean damaged, boolean hittable, boolean self);
}
