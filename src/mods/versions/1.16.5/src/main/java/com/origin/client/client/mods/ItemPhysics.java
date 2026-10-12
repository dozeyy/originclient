package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.client.renderer.block.model.ItemTransforms;

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
 * Hooked by ItemPhysicsMixin right after ItemEntityRenderer.render applies its
 * spin (1.16.5 has no renderMultipleFromCount: the copies are drawn by a loop
 * inline in render, after the spin) -- i.e. after vanilla's bob (translate up by
 * bob + 0.25 x ground scale) and spin (rotate about Y). This first UNDOES exactly
 * those two transforms (same maths as vanilla's render; decompiled 1.16.4), then applies the physics pose. Items keep their GROUND model
 * transform (size, the per-model offset), applied later by vanilla.
 * The model is read from the ItemModelShaper (its base model): ItemRenderer's
 * getModel gained a seed argument inside this era, and only the ground transform
 * and isGui3d are needed here, which overrides don't change.
 *
 * The physics pose is one shape for every state, so air -> ground can ease:
 *   translate(height) . [about the model centre: tilt(Z), tumble(X)]
 * The model centre sits ground.translation.y above the pivot (ItemTransform
 * translates before the model's own -0.5 centring), so rotating about the pivot
 * itself -- the old behaviour -- swung the item round in a circle instead of
 * tumbling it in place.
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
		float time;     // age + partialTick when last drawn
		float tumble;   // degrees about X
		float tilt;     // degrees about Z
		float height;   // pivot lift above the entity origin
	}

	private static final Map<ItemEntity, State> STATES = new WeakHashMap<>();

	private ItemPhysics() {
	}

	public static void apply(ItemEntity e, float partialTick, PoseStack pose) {
		if (!Mods.on(MOD)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		BakedModel model = mc.getItemRenderer().getItemModelShaper().getItemModel(e.getItem());
		ItemTransform ground = model.getTransforms().getTransform(ItemTransforms.TransformType.GROUND);
		float scaleY = ground.scale.y();
		float centre = ground.translation.y();
		boolean block = model.isGui3d();

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float now = (float) e.getAge() + partialTick;
		float bob = Mth.sin(now / 10.0F + e.bobOffs) * 0.1F + 0.1F;
		pose.mulPose(Vector3f.YP.rotation(-e.getSpin(partialTick)));
		pose.translate(0.0F, -(bob + 0.25F * scaleY), 0.0F);

		// 2) where this item is heading: tumbling in the air, or lying at rest.
		boolean resting = e.isOnGround() || e.isInWater();
		float restHeight;
		if (block) {
			// the model's bottom face (centre - half its scaled unit cube) on the ground
			restHeight = -(centre - 0.5F * scaleY);
		} else {
			// lying on its back (local +Z -> world up, so vanilla's stacked copies pile
			// upward), lifted by the stack's half-depth + the sprite's own thickness
			int copies = renderedCopies(e.getItem().getCount());
			float halfStack = 0.09375F * (copies - 1) * 0.5F * ground.scale.z();
			restHeight = halfStack + ground.scale.z() / 32F + 0.01F - centre;
		}

		State st = STATES.get(e);
		float dt = st == null ? -1 : now - st.time;
		if (st == null || dt < 0 || dt > 20) {
			// first sight (or a long gap): start in place, no animation into it
			st = new State();
			st.tumble = resting ? restTumble(block, 0) : (180F / (float) Math.PI) * e.bobOffs;
			st.tilt = resting ? 0 : tiltFor(e);
			st.height = resting ? restHeight : 0.25F * scaleY;
			STATES.put(e, st);
			dt = 0;
		}
		st.time = now;

		if (!resting) {
			st.tumble = (st.tumble + dt * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed")) % 360f;
			st.tilt = ease(st.tilt, tiltFor(e), dt);
			// vanilla's resting height without the bob
			st.height = ease(st.height, 0.25F * scaleY, dt);
		} else {
			float target = restTumble(block, st.tumble);
			st.tumble = ease(st.tumble, target, dt);
			st.tilt = ease(st.tilt, 0f, dt);
			st.height = ease(st.height, restHeight, dt);
		}

		// 3) physics pose. bobOffs is a per-entity random angle: a stable heading.
		pose.mulPose(Vector3f.YP.rotation(e.bobOffs));
		pose.translate(0.0F, st.height + centre, 0.0F);
		pose.mulPose(Vector3f.ZP.rotationDegrees(st.tilt));
		pose.mulPose(Vector3f.XP.rotationDegrees(st.tumble));
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
	private static float tiltFor(ItemEntity e) {
		return Mth.sin(e.bobOffs * 3.7F) * MAX_TILT_DEG;
	}

	/** Frame-rate independent approach of value toward target over dt ticks. */
	private static float ease(float value, float target, float dt) {
		float k = 1f - (float) Math.exp(-dt / SETTLE_TICKS);
		return value + (target - value) * k;
	}

	/** Vanilla's ItemEntityRenderer.getRenderedAmount (package-private there). */
	private static int renderedCopies(int count) {
		if (count <= 1) {
			return 1;
		} else if (count <= 16) {
			return 2;
		} else if (count <= 32) {
			return 3;
		}
		return count <= 48 ? 4 : 5;
	}
}
