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
 * The physics pose is one shape for every state, so air -> ground can ease:
 *   translate(height) . [about the model centre: tilt(Z), tumble(X)]
 * Rotating about the model centre (not the pivot below it) is what keeps the
 * item tumbling in place instead of swinging round in a circle.
 *
 * PER-VERSION DELTA (1.21.10, ItemEntityRenderer source-verified, extract/submit):
 *  - The live entity only exists at EXTRACTION, so the per-item easing state
 *    (keyed weakly by the ItemEntity, as on 1.21.1) is advanced there
 *    ({@link #extract}) and the resulting tumble / tilt / height ride the render
 *    state (ItemPhysicsState duck) to SUBMIT ({@link #apply}). Clock = the
 *    state's ageInTicks (age + partialTick); heading = the state's bobOffset.
 *  - Hooked by ItemPhysicsMixin right before ItemEntityRenderer.submit hands the
 *    pose to submitMultipleFromCount -- after vanilla's bob and spin, which
 *    apply() first UNDOES. Vanilla's lift is the item model's own bounding box
 *    -- translate(0, bob + (-box.minY + 1/16)) -- not 1.21.1's 0.25 x ground
 *    scale, and the model centre is that box's middle; "flat vs block" is
 *    vanilla's own test from submitMultipleFromCount (box depth > 1/16 = a 3D
 *    model whose copies scatter; otherwise a flat sprite whose copies stack
 *    along +Z, 1.5 x its depth apart, centred on the stack).
 */
public final class ItemPhysics {
	private static final String MOD = "itemphysics";
	// Degrees per tick at Rotation Speed 1.0x (default 0.5x = half a turn a second).
	private static final float DEG_PER_TICK = 18f;
	// Easing time constant, in ticks, for landing (~0.1 s to settle flat).
	private static final float SETTLE_TICKS = 1.5f;
	// Largest per-item tilt of the tumble axis while airborne.
	private static final float MAX_TILT_DEG = 25f;
	// Vanilla's gap between the model's bottom and the entity origin (1/16 block).
	private static final float GROUND_GAP = 0.0625F;

	/** Per-item animation state; weak keys, so unloaded items drop out by themselves. */
	private static final class State {
		float time;     // ageInTicks (age + partialTick) when last extracted
		float tumble;   // degrees about X
		float tilt;     // degrees about Z
		float height;   // pivot lift above the entity origin
	}

	private static final Map<ItemEntity, State> STATES = new WeakHashMap<>();

	private ItemPhysics() {
	}

	/** Extraction (render thread, entity in hand): advance the pose and store it on the state. */
	public static void extract(ItemEntity e, ItemEntityRenderState s, ItemPhysicsState out) {
		if (!Mods.on(MOD) || s.item.isEmpty()) {
			out.originclient$setPose(false, 0f, 0f, 0f);
			return;
		}
		AABB box = s.item.getModelBoundingBox();
		float lift = -((float) box.minY) + GROUND_GAP;
		float centre = (float) (box.minY + box.maxY) / 2.0F;
		float depth = (float) box.getZsize();
		boolean block = depth > GROUND_GAP;

		// Where this item is heading: tumbling in the air, or lying at rest.
		boolean resting = e.onGround() || e.isInWater();
		float restHeight;
		if (block) {
			// the model's bottom face on the ground
			restHeight = -((float) box.minY);
		} else {
			// lying on its back (local +Z -> world up, so vanilla's stacked copies pile
			// upward), lifted by the stack's half-depth + the sprite's own half
			// thickness so nothing sinks into the floor
			int copies = Math.max(1, s.count);
			float halfStack = depth * 1.5F * (copies - 1) / 2.0F;
			restHeight = halfStack + depth / 2.0F + 0.01F - centre;
		}

		float now = s.ageInTicks;
		State st = STATES.get(e);
		float dt = st == null ? -1 : now - st.time;
		if (st == null || dt < 0 || dt > 20) {
			// first sight (or a long gap): start in place, no animation into it
			st = new State();
			st.tumble = resting ? restTumble(block, 0) : Mth.RAD_TO_DEG * s.bobOffset;
			st.tilt = resting ? 0 : tiltFor(s.bobOffset);
			st.height = resting ? restHeight : lift;
			STATES.put(e, st);
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
		out.originclient$setPose(true, st.tumble, st.tilt, st.height);
	}

	/** Submit: replace vanilla's bob + spin with the pose extract() stored. */
	public static void apply(ItemEntityRenderState s, ItemPhysicsState ps, PoseStack pose) {
		if (!Mods.on(MOD) || s.item.isEmpty() || !ps.originclient$posed()) {
			return;
		}
		AABB box = s.item.getModelBoundingBox();
		float lift = -((float) box.minY) + GROUND_GAP;
		float centre = (float) (box.minY + box.maxY) / 2.0F;

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float bob = Mth.sin(s.ageInTicks / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(s.ageInTicks, s.bobOffset)));
		pose.translate(0.0F, -(bob + lift), 0.0F);

		// 2) physics pose. bobOffset is a per-entity random angle: a stable heading.
		pose.mulPose(Axis.YP.rotation(s.bobOffset));
		pose.translate(0.0F, ps.originclient$height() + centre, 0.0F);
		pose.mulPose(Axis.ZP.rotationDegrees(ps.originclient$tilt()));
		pose.mulPose(Axis.XP.rotationDegrees(ps.originclient$tumble()));
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
