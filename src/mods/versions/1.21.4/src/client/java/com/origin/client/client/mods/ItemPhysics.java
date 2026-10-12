package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.client.ext.ItemPhysicsState;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;

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
 * (translate up by bob + 0.25 x ground scale) and spin (rotate about Y). This
 * first UNDOES exactly those two vanilla transforms (same maths as 1.21.1's
 * render, source-verified), then applies the physics pose. Items keep their
 * GROUND model transform (size, the per-model offset), applied later by vanilla.
 *
 * The physics pose is one shape for every state, so air -> ground can ease:
 *   translate(height) . [about the model centre: tilt(Z), tumble(X)]
 * The model centre sits ground.translation.y above the pivot (ItemTransform
 * translates before the model's own -0.5 centring), so rotating about the pivot
 * itself -- the old behaviour -- swung the item round in a circle instead of
 * tumbling it in place.
 *
 * PER-VERSION (1.21.2-1.21.4): works from the item's render state instead of the
 * live entity -- ageInTicks (age + partial tick), bobOffset, count and the baked
 * GROUND transform are vanilla's own state fields. What the state lacks is
 * recorded at extraction by ItemPhysicsMixin onto the state (ItemPhysicsState):
 * the resting flag and this item's easing memory ({@link Anim}, kept per entity
 * here, since a render state is reused across every item). The maths is 1.21.1's,
 * input for input.
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
	public static final class Anim {
		boolean started;  // false until first drawn
		float time;       // ageInTicks when last drawn
		float tumble;     // degrees about X
		float tilt;       // degrees about Z
		float height;     // pivot lift above the entity origin
	}

	private static final Map<ItemEntity, Anim> ANIMS = new WeakHashMap<>();

	private ItemPhysics() {
	}

	/** Extraction (render thread): this item's easing memory, or null with the mod off. */
	public static Anim animFor(ItemEntity e) {
		return Mods.on(MOD) ? ANIMS.computeIfAbsent(e, k -> new Anim()) : null;
	}

	public static void apply(ItemEntityRenderState s, PoseStack pose) {
		if (!Mods.on(MOD)) {
			return;
		}
		ItemPhysicsState ext = (ItemPhysicsState) s;
		Anim st = ext.originclient$getAnim();
		if (st == null) {
			return;   // extracted before the mod was switched on: vanilla this frame
		}
		ItemTransform ground = s.item.transform();
		float scaleY = ground.scale.y();
		float centre = ground.translation.y();
		boolean block = s.item.isGui3d();

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float now = s.ageInTicks;
		float bob = Mth.sin(now / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(s.ageInTicks, s.bobOffset)));
		pose.translate(0.0F, -(bob + 0.25F * scaleY), 0.0F);

		// 2) where this item is heading: tumbling in the air, or lying at rest.
		boolean resting = ext.originclient$isResting();
		float restHeight;
		if (block) {
			// the model's bottom face (centre - half its scaled unit cube) on the ground
			restHeight = -(centre - 0.5F * scaleY);
		} else {
			// lying on its back (local +Z -> world up, so vanilla's stacked copies pile
			// upward), lifted by the stack's half-depth + the sprite's own thickness.
			// s.count is vanilla's rendered-copies count (getRenderedAmount).
			int copies = Math.max(1, s.count);
			float halfStack = 0.09375F * (copies - 1) * 0.5F * ground.scale.z();
			restHeight = halfStack + ground.scale.z() / 32F + 0.01F - centre;
		}

		float dt = st.started ? now - st.time : -1;
		if (!st.started || dt < 0 || dt > 20) {
			// first sight (or a long gap): start in place, no animation into it
			st.started = true;
			st.tumble = resting ? restTumble(block, 0) : Mth.RAD_TO_DEG * s.bobOffset;
			st.tilt = resting ? 0 : tiltFor(s.bobOffset);
			st.height = resting ? restHeight : 0.25F * scaleY;
			dt = 0;
		}
		st.time = now;

		if (!resting) {
			st.tumble = (st.tumble + dt * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed")) % 360f;
			st.tilt = ease(st.tilt, tiltFor(s.bobOffset), dt);
			// vanilla's resting height without the bob
			st.height = ease(st.height, 0.25F * scaleY, dt);
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
