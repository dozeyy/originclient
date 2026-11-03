package com.origin.client.client.mixin;

import com.origin.client.client.render.HitboxFactsState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Target: EntityRenderState (a plain class on 1.21.6-1.21.10): adds four fields
// and the HitboxFactsState duck interface. No injection.
//
// Why: the hitbox draw (HitboxMixin) only sees the render state, so the live
// entity's facts (category, hurt, under the crosshair, is-it-you) recorded by
// HitboxFactsMixin ride on the state itself -- no per-frame map entry or object.
//
// If it stops applying: HitboxFacts sees no HitboxFactsState and the draw falls
// back to the entity type alone (Passive / Monsters / Projectiles match nothing,
// tints and look line stay off). Nothing crashes.
@Mixin(EntityRenderState.class)
public class EntityRenderStateHitboxMixin implements HitboxFactsState {

	@Unique
	private String originclient$hitboxCategory;
	@Unique
	private boolean originclient$hitboxDamaged, originclient$hitboxHittable, originclient$hitboxSelf;

	@Override
	public String origin$hitboxCategory() {
		return originclient$hitboxCategory;
	}

	@Override
	public boolean origin$hitboxDamaged() {
		return originclient$hitboxDamaged;
	}

	@Override
	public boolean origin$hitboxHittable() {
		return originclient$hitboxHittable;
	}

	@Override
	public boolean origin$hitboxSelf() {
		return originclient$hitboxSelf;
	}

	@Override
	public void origin$setHitboxFacts(String category, boolean damaged, boolean hittable, boolean self) {
		originclient$hitboxCategory = category;
		originclient$hitboxDamaged = damaged;
		originclient$hitboxHittable = hittable;
		originclient$hitboxSelf = self;
	}
}
