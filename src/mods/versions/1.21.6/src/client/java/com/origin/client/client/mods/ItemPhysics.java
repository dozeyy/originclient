package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.client.ext.ItemPhysicsState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * Item Physics (Lunar parity): dropped items stop floating and spinning in place.
 * In the air they tumble end over end (Rotation Speed); once they land -- or
 * float on water -- flat items lie flat on the ground and blocks sit on it, each
 * at its own fixed random heading. Render-only: the entity, its hitbox and pickup
 * are untouched.
 *
 * Hooked by ItemPhysicsMixin right before ItemEntityRenderer.render hands the
 * pose to renderMultipleFromCount -- i.e. after vanilla has applied its bob
 * (translate up by bob + 0.25 x ground scale) and spin (rotate about Y). This
 * first UNDOES exactly those two vanilla transforms (same maths as 1.21.1's
 * render, source-verified), then applies the physics pose. Items keep their
 * GROUND model transform (size, the per-model offset), applied later by vanilla.
 *
 * PER-VERSION (1.21.6-1.21.8): works from the item's render state instead of the
 * live entity (ageInTicks, bobOffset, count; the resting flag is recorded at
 * extraction by ItemPhysicsMixin). This era places a dropped item by its MODEL
 * BOUNDING BOX (ItemStackRenderState.getModelBoundingBox, already in GROUND
 * space) instead of 1.21.1's "0.25 x ground scale" -- vanilla lifts it by
 * -minY + 1/16, and stacks flat copies along Z by 1.5 x the box depth -- so the
 * undo and the resting poses use the box the same way: the same looks as 1.21.1
 * (blocks sit on the ground, flat items lie on their back, airborne items tumble),
 * from this era's geometry.
 */
public final class ItemPhysics {
	private static final String MOD = "itemphysics";
	// Degrees per tick at Rotation Speed 1.0x (default 0.5x = half a turn a second).
	private static final float DEG_PER_TICK = 18f;

	private ItemPhysics() {
	}

	public static void apply(ItemEntityRenderState s, PoseStack pose) {
		if (!Mods.on(MOD)) {
			return;
		}
		AABB box = s.item.getModelBoundingBox();
		float lift = -(float) box.minY + 0.0625F;   // vanilla 1.21.8's resting lift

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float bob = Mth.sin(s.ageInTicks / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(s.ageInTicks, s.bobOffset)));
		pose.translate(0.0F, -(bob + lift), 0.0F);

		// 2) physics pose. bobOffset is a per-entity random angle: a stable heading.
		boolean resting = ((ItemPhysicsState) s).originclient$isResting();
		pose.mulPose(Axis.YP.rotation(s.bobOffset));
		float depth = (float) box.getZsize();
		if (!resting) {
			// airborne: vanilla's resting height without the bob, tumbling
			pose.translate(0.0F, lift, 0.0F);
			float deg = s.ageInTicks * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed");
			pose.mulPose(Axis.XP.rotationDegrees(deg % 360f));
		} else if (depth > 0.0625F) {
			// blocks (vanilla's own "thick model" test): the model's bottom face on the ground
			pose.translate(0.0F, -(float) box.minY, 0.0F);
		} else {
			// flat items: tip the sprite onto its back (local +Z -> world up, so
			// vanilla's stacked copies -- 1.5 x the box depth apart, centred on the
			// origin -- pile upward) and lift the bottom copy's lower face onto the
			// ground. s.count is vanilla's rendered-copies count.
			int copies = Math.max(1, s.count);
			float halfStack = depth * 1.5F * (copies - 1) / 2.0F;
			pose.translate(0.0F, halfStack - (float) box.minZ + 0.01F, 0.0F);
			pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
			// centre the sprite on the spot (its local Y now points sideways)
			pose.translate(0.0F, -(float) (box.minY + box.maxY) / 2.0F, 0.0F);
		}
	}
}
