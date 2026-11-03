package com.origin.client.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.phys.EntityHitResult;

/**
 * What the Hitbox mod needs to know about a LIVE entity, carried to the
 * render-state era's draw (1.21.6-1.21.10), where the draw only sees an
 * EntityRenderState. HitboxFactsMixin captures it while vanilla extracts the
 * entity's hitboxes; HitboxMixin reads it back from the same state.
 *
 * Without it the state era had only the EntityType, so the Passive / Monsters /
 * Projectiles switches never matched anything and Show Hittable / Show Damaged /
 * Show Look Vector did nothing. These are the 1.21.1 / 1.21.11 checks, run on
 * the live entity.
 *
 * The facts are stored as fields on the render state itself (HitboxFactsState,
 * added by mixin/EntityRenderStateHitboxMixin): nothing is allocated per entity
 * per frame, and there is no map or lock. Render thread only.
 */
public final class HitboxFacts {

	private HitboxFacts() {
	}

	/** Records the live entity's facts on its render state. Never throws. */
	public static void capture(Entity entity, EntityRenderState state) {
		try {
			if (!(state instanceof HitboxFactsState s)) {
				return;   // state mixin not applied: the draw falls back to the entity type alone
			}
			Minecraft mc = Minecraft.getInstance();
			s.origin$setHitboxFacts(categoryKey(entity),
					entity instanceof LivingEntity le && le.hurtTime > 0,
					mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == entity,
					entity == mc.player);
		} catch (Throwable t) {
			// fail-soft: the draw falls back to the entity type alone
		}
	}

	/** The facts captured on this state, or null when none were (the draw falls back). */
	public static HitboxFactsState of(EntityRenderState state) {
		return state instanceof HitboxFactsState s && s.origin$hitboxCategory() != null ? s : null;
	}

	/** Same order as 1.21.1 / 1.21.11: the specific kinds first, then the broad ones. */
	public static String categoryKey(Entity e) {
		if (e instanceof Player) return "players";
		if (e instanceof ItemEntity) return "items";
		if (e instanceof ItemFrame) return "itemFrames";
		if (e instanceof WitherSkull) return "witherSkulls";
		if (e instanceof AbstractHurtingProjectile) return "fireballs";
		if (e instanceof FireworkRocketEntity) return "fireworks";
		if (e instanceof Snowball) return "snowballs";
		if (e instanceof AbstractArrow) return "arrows";
		if (e instanceof ExperienceOrb) return "expOrbs";
		if (e instanceof Projectile) return "projectiles";
		if (e instanceof Monster) return "monsters";
		if (e instanceof Animal) return "passive";
		return "other";
	}
}
