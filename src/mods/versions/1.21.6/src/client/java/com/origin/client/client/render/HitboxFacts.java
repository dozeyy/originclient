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

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the Hitbox mod needs to know about a LIVE entity, carried to the
 * render-state era's draw (1.21.6-1.21.10), where the draw only sees an
 * EntityRenderState. HitboxFactsMixin fills it while vanilla extracts the
 * entity's hitboxes; HitboxMixin reads it back by the same state.
 *
 * Without it the state era had only the EntityType, so the Passive / Monsters /
 * Projectiles switches never matched anything and Show Hittable / Show Damaged /
 * Show Look Vector did nothing. These are the 1.21.1 / 1.21.11 checks, run on
 * the live entity.
 *
 * Keyed by state identity in a WeakHashMap: a state that is no longer used is
 * dropped by the GC. Render thread only (extraction and the draw both run
 * there); synchronized anyway so a stray caller can't corrupt the map.
 */
public final class HitboxFacts {

	/** The Mod Menu switch this entity belongs to (players, monsters, passive, ...). */
	public String category = "other";
	/** Hurt flash running (LivingEntity.hurtTime > 0). */
	public boolean damaged;
	/** It is what your crosshair is on. */
	public boolean hittable;
	/** It is you: your own look line only ever shows as an Iris shadow, so it's skipped. */
	public boolean self;

	private static final Map<EntityRenderState, HitboxFacts> BY_STATE = new WeakHashMap<>();

	private HitboxFacts() {
	}

	/** Records the live entity's facts against its render state. Never throws. */
	public static synchronized void capture(Entity entity, EntityRenderState state) {
		try {
			HitboxFacts f = BY_STATE.get(state);
			if (f == null) {
				f = new HitboxFacts();
				BY_STATE.put(state, f);
			}
			Minecraft mc = Minecraft.getInstance();
			f.category = categoryKey(entity);
			f.damaged = entity instanceof LivingEntity le && le.hurtTime > 0;
			f.hittable = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == entity;
			f.self = entity == mc.player;
		} catch (Throwable t) {
			// fail-soft: the draw falls back to the entity type alone
		}
	}

	/** The facts recorded for this state, or null when none were (draw falls back). */
	public static synchronized HitboxFacts of(EntityRenderState state) {
		return state == null ? null : BY_STATE.get(state);
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
