package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.client.ext.ItemPhysicsState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Item Physics (Lunar parity): dropped items stop floating and spinning in place.
 * In the air they tumble end over end around their own centre, each on its own
 * slight tilt (Rotation Speed); once they land -- or float on water -- they ease
 * down flat: flat items onto their back, blocks onto their nearest face, each at
 * its own fixed random heading. Render-only: the entity, its hitbox and pickup
 * are untouched.
 *
 * Hooked by ItemPhysicsMixin right before ItemEntityRenderer.render hands the
 * pose to renderMultipleFromCount -- i.e. after vanilla has applied its bob
 * (translate up by bob + the resting lift) and spin (rotate about Y). This first
 * UNDOES exactly those two vanilla transforms, then applies the physics pose.
 * Items keep their GROUND model transform (size, the per-model offset), applied
 * later by vanilla.
 *
 * The physics pose is one shape for every state, so air -> ground can ease:
 *   translate(height) . [about the model centre: tilt(Z), tumble(X)]
 * Rotating about the pivot itself -- the old behaviour -- swung the item round
 * in a circle instead of tumbling it in place.
 *
 * PER-VERSION (1.21.6-1.21.8): works from the item's render state instead of the
 * live entity (ageInTicks, bobOffset, count; the resting flag and the entity --
 * the key for the per-item easing state, since vanilla reuses ONE state object
 * for every item -- are recorded at extraction by ItemPhysicsMixin). This era
 * places a dropped item by its MODEL BOUNDING BOX (ItemStackRenderState.
 * getModelBoundingBox, already in GROUND space) instead of 1.21.1's "0.25 x
 * ground scale" -- vanilla lifts it by -minY + 1/16, and stacks flat copies
 * along Z by 1.5 x the box depth -- so the model centre, the block test and the
 * resting heights come from the box: the same looks as 1.21.1, from this era's
 * geometry.
 */
public final class ItemPhysics {
	private static final String MOD = "itemphysics";
	// Degrees per tick at Rotation Speed 1.0x (default 0.5x = half a turn a second).
	private static final float DEG_PER_TICK = 18f;
	// Easing time constant, in ticks, for landing (~0.1 s to settle flat).
	private static final float SETTLE_TICKS = 1.5f;
	// Largest per-item tilt of the tumble axis while airborne.
	private static final float MAX_TILT_DEG = 25f;

	/** Per-item animation state; weak keys, so unloaded items drop out by themselves. */
	private static final class State {
		float time;     // ageInTicks (age + partialTick) when last drawn
		float tumble;   // degrees about X
		float tilt;     // degrees about Z
		float height;   // pivot lift above the entity origin
	}

	private static final Map<ItemEntity, State> STATES = new WeakHashMap<>();

	private ItemPhysics() {
	}

	public static void apply(ItemEntityRenderState s, PoseStack pose) {
		if (!Mods.on(MOD)) {
			return;
		}
		ItemPhysicsState ext = (ItemPhysicsState) s;
		AABB box = s.item.getModelBoundingBox();
		float lift = -(float) box.minY + 0.0625F;   // vanilla 1.21.8's resting lift
		// The model centre above the pivot (the box is already in GROUND space).
		float centre = (float) (box.minY + box.maxY) / 2.0F;
		float depth = (float) box.getZsize();
		boolean block = depth > 0.0625F;   // vanilla's own "thick model" test

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float now = s.ageInTicks;
		float bob = Mth.sin(now / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(now, s.bobOffset)));
		pose.translate(0.0F, -(bob + lift), 0.0F);

		// 2) where this item is heading: tumbling in the air, or lying at rest.
		boolean resting = ext.originclient$isResting();
		float restHeight;
		if (block) {
			// the model's bottom face on the ground
			restHeight = -(float) box.minY;
		} else {
			// lying on its back (local +Z -> world up, so vanilla's stacked copies --
			// 1.5 x the box depth apart, centred on the origin -- pile upward), the
			// bottom copy's lower face just above the ground. s.count is vanilla's
			// rendered-copies count.
			int copies = Math.max(1, s.count);
			float halfStack = depth * 1.5F * (copies - 1) / 2.0F;
			restHeight = halfStack - (float) box.minZ + 0.01F - centre;
		}

		ItemEntity key = ext.originclient$entity();
		State st = key == null ? null : STATES.get(key);
		float dt = st == null ? -1 : now - st.time;
		if (st == null || dt < 0 || dt > 20) {
			// first sight (or a long gap): start in place, no animation into it
			st = new State();
			st.tumble = resting ? restTumble(block, 0) : Mth.RAD_TO_DEG * s.bobOffset;
			st.tilt = resting ? 0 : tiltFor(s.bobOffset);
			st.height = resting ? restHeight : lift;
			if (key != null) {
				STATES.put(key, st);
			}
			dt = 0;
		}
		st.time = now;

		if (!resting) {
			st.tumble = (st.tumble + dt * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed")) % 360f;
			st.tilt = ease(st.tilt, tiltFor(s.bobOffset), dt);
			// vanilla's resting height without the bob
			st.height = ease(st.height, lift, dt);
		} else {
			float target = restTumble(block, st.tumble);
			st.tumble = ease(st.tumble, target, dt);
			st.tilt = ease(st.tilt, 0f, dt);
			st.height = ease(st.height, restHeight, dt);
		}

		// 3) physics pose. bobOffset is a per-entity random angle: a stable heading.
		pose.mulPose(Axis.YP.rotation(s.bobOffset));
		pose.translate(0.0F, st.height + centre, 0.0F);
		pose.mulPose(Axis.ZP.rotationDegrees(st.tilt));
		pose.mulPose(Axis.XP.rotationDegrees(st.tumble));
		pose.translate(0.0F, -centre, 0.0F);
	}

	/**
	 * The resting tumble angle nearest the current one: blocks settle on whichever
	 * face is closest (every 90 deg), flat items always on their back (-90 deg, so
	 * the stack piles upward rather than into the floor).
	 */
	private static float restTumble(boolean block, float current) {
		if (block) {
			return Math.round(current / 90f) * 90f;
		}
		return current + Mth.wrapDegrees(-90f - current);
	}

	/** A fixed tilt per item, from its random bob offset (-MAX_TILT..MAX_TILT). */
	private static float tiltFor(float bobOffset) {
		return Mth.sin(bobOffset * 3.7F) * MAX_TILT_DEG;
	}

	/** Frame-rate independent approach of value toward target over dt ticks. */
	private static float ease(float value, float target, float dt) {
		float k = 1f - (float) Math.exp(-dt / SETTLE_TICKS);
		return value + (target - value) * k;
	}
}
