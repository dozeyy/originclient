package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

// Combo Counter + Reach Display state. Fed by HudHooks' AttackEntityEvent (every
// melee swing that lands on an entity) and confirmed here, once per client tick,
// by watching the target's hurt animation: a swing only counts as a HIT when the
// target visibly takes damage shortly after. That is what keeps blocked/
// invulnerable-frame swings out of the combo and the reach readout -- the same
// thing the player sees. Nothing is sent anywhere; it's all local observation.
//
// 1.8.9: the swing hook is Forge's AttackEntityEvent (posted by the local
// player's attackTargetEntityWithCurrentItem, which PlayerControllerMP.attackEntity
// runs client-side), the crosshair hit is Minecraft.objectMouseOver, and the
// hurt animation is EntityLivingBase.hurtTime -- the same signals 1.16.5 reads.
public final class CombatStats {
	private CombatStats() {
	}

	/** How long after a swing the target's hurt flash may still confirm it. */
	private static final long CONFIRM_WINDOW_MS = 500;
	// No confirmed hit for the Combo mod's "Reset After" seconds ends the combo
	// (Lunar's "time window"). Taking a hit always ends it too -- not a setting.
	/** The reach readout falls back to zero this long after the last hit. */
	private static final long REACH_EXPIRE_MS = 3000;

	public static volatile int combo = 0;
	public static volatile double lastReach = 0;

	private static int pendingTargetId = -1;
	private static int pendingHurtSeen = 0;
	private static double pendingReach = 0;
	private static long pendingAt = 0;
	private static boolean pendingCountsForCombo = true;

	private static long lastHitAt = 0;
	private static long lastComboHitAt = 0;
	private static int selfHurtSeen = 0;

	/** Called from the attack hook for every entity the player swings at. */
	public static void onAttack(Entity target) {
		if (!(target instanceof EntityLivingBase)) {
			return;
		}
		EntityLivingBase living = (EntityLivingBase) target;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null) {
			return;
		}
		// Reach = eye to the exact point the crosshair ray met the target's
		// hitbox -- the same ray Minecraft.clickMouse used to pick it.
		Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
		double reach;
		MovingObjectPosition hr = mc.objectMouseOver;
		if (hr != null && hr.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
				&& hr.entityHit == target && hr.hitVec != null) {
			reach = eye.distanceTo(hr.hitVec);
		} else {
			AxisAlignedBB bb = target.getEntityBoundingBox();
			reach = eye.distanceTo(new Vec3((bb.minX + bb.maxX) / 2.0, (bb.minY + bb.maxY) / 2.0, (bb.minZ + bb.maxZ) / 2.0));
		}
		// Players Only: a swing at a mob neither counts nor breaks the combo. Reach
		// still shows every hit -- the option belongs to the Combo mod.
		boolean countsForCombo = !(Mods.bool("combo", "playersOnly") && !(target instanceof EntityPlayer));
		pendingTargetId = target.getEntityId();
		pendingCountsForCombo = countsForCombo;
		pendingHurtSeen = living.hurtTime;
		pendingReach = reach;
		pendingAt = System.currentTimeMillis();
	}

	/** Once per client tick: confirm pending hits, end stale combos. */
	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (mc.thePlayer == null || mc.theWorld == null) {
			combo = 0;
			lastReach = 0;
			pendingTargetId = -1;
			return;
		}
		// Taking damage ends your combo (rising edge of our own hurt animation).
		int selfHurt = mc.thePlayer.hurtTime;
		if (selfHurt > selfHurtSeen) {
			combo = 0;
		}
		selfHurtSeen = selfHurt;

		if (pendingTargetId != -1) {
			Entity e = mc.theWorld.getEntityByID(pendingTargetId);
			if (!(e instanceof EntityLivingBase) || now - pendingAt > CONFIRM_WINDOW_MS) {
				pendingTargetId = -1;
			} else if (((EntityLivingBase) e).hurtTime > pendingHurtSeen) {
				// hurtTime only ever rises when a fresh hurt starts -> our hit landed
				if (pendingCountsForCombo) {
					combo++;
					lastComboHitAt = now;
				}
				lastReach = pendingReach;
				lastHitAt = now;
				pendingTargetId = -1;
			} else {
				pendingHurtSeen = ((EntityLivingBase) e).hurtTime;
			}
		}
		long comboExpireMs = (long) (Mods.num("combo", "resetAfter") * 1000);
		if (combo > 0 && now - lastComboHitAt > comboExpireMs) {
			combo = 0;
		}
		if (lastReach > 0 && now - lastHitAt > REACH_EXPIRE_MS) {
			lastReach = 0;
		}
	}
}
