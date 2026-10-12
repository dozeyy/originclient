package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

// Combo Counter + Reach Display state. Fed by MultiPlayerGameModeMixin (every
// melee swing that lands on an entity) and confirmed here, once per client tick,
// by watching the target's hurt animation: a swing only counts as a HIT when the
// target visibly takes damage shortly after. That is what keeps blocked/
// invulnerable-frame swings out of the combo and the reach readout -- the same
// thing the player sees. Nothing is sent anywhere; it's all local observation.
public final class CombatStats {
	private CombatStats() {
	}

	/** How long after a swing the target's hurt flash may still confirm it. */
	private static final long CONFIRM_WINDOW_MS = 500;
	/** No confirmed hit for this long ends the combo (Lunar's "time window"). */
	private static final long COMBO_EXPIRE_MS = 2000;
	/** The reach readout falls back to zero this long after the last hit. */
	private static final long REACH_EXPIRE_MS = 3000;

	public static volatile int combo = 0;
	public static volatile double lastReach = 0;

	private static int pendingTargetId = -1;
	private static int pendingHurtSeen = 0;
	private static double pendingReach = 0;
	private static long pendingAt = 0;

	private static long lastHitAt = 0;
	private static int selfHurtSeen = 0;

	/** Called from the attack mixin for every entity the player swings at. */
	public static void onAttack(Entity target) {
		if (!(target instanceof LivingEntity)) {
			return;
		}
		LivingEntity living = (LivingEntity) target;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		// Reach = eye to the exact point the crosshair ray met the target's
		// hitbox -- the same ray Minecraft.startAttack used to pick it.
		double reach;
		HitResult hr = mc.hitResult;
		if (hr instanceof EntityHitResult && ((EntityHitResult) hr).getEntity() == target) {
			reach = mc.player.getEyePosition(1.0F).distanceTo(hr.getLocation());
		} else {
			reach = mc.player.getEyePosition(1.0F).distanceTo(target.getBoundingBox().getCenter());
		}
		pendingTargetId = target.getId();
		pendingHurtSeen = living.hurtTime;
		pendingReach = reach;
		pendingAt = System.currentTimeMillis();
	}

	/** Once per client tick: confirm pending hits, end stale combos. */
	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (mc.player == null || mc.level == null) {
			combo = 0;
			lastReach = 0;
			pendingTargetId = -1;
			return;
		}
		// Taking damage ends your combo (rising edge of our own hurt animation).
		int selfHurt = mc.player.hurtTime;
		if (selfHurt > selfHurtSeen) {
			combo = 0;
		}
		selfHurtSeen = selfHurt;

		if (pendingTargetId != -1) {
			Entity e = mc.level.getEntity(pendingTargetId);
			if (!(e instanceof LivingEntity) || now - pendingAt > CONFIRM_WINDOW_MS) {
				pendingTargetId = -1;
			} else if (((LivingEntity) e).hurtTime > pendingHurtSeen) {
				// hurtTime only ever rises when a fresh hurt starts -> our hit landed
				combo++;
				lastReach = pendingReach;
				lastHitAt = now;
				pendingTargetId = -1;
			} else {
				pendingHurtSeen = ((LivingEntity) e).hurtTime;
			}
		}
		if (combo > 0 && now - lastHitAt > COMBO_EXPIRE_MS) {
			combo = 0;
		}
		if (lastReach > 0 && now - lastHitAt > REACH_EXPIRE_MS) {
			lastReach = 0;
		}
	}
}
