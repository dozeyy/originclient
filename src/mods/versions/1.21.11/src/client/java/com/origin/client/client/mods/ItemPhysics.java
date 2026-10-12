package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
 * Hooked by ItemPhysicsMixin right before ItemEntityRenderer.submit hands the
 * pose to submitMultipleFromCount -- i.e. after vanilla has applied its bob and
 * spin. This first UNDOES exactly those two vanilla transforms, then applies the
 * physics pose.
 *
 * PER-VERSION DELTA (1.21.11, ItemEntityRenderer source-verified): vanilla's
 * lift is no longer the GROUND transform's 0.25 x scale but the item model's
 * own bounding box -- translate(0, bob + (-box.minY + 1/16)) -- and "flat vs
 * block" is vanilla's own test from submitMultipleFromCount (box depth > 1/16 =
 * a 3D model whose copies scatter; otherwise a flat sprite whose copies stack
 * along +Z, 1.5 x its depth apart). The live entity is gone at submit time, so
 * "resting" comes from the extract pass (ItemPhysicsState duck).
 */
public final class ItemPhysics {
	private static final String MOD = "itemphysics";
	// Degrees per tick at Rotation Speed 1.0x (default 0.5x = half a turn a second).
	private static final float DEG_PER_TICK = 18f;

	private ItemPhysics() {
	}

	public static void apply(ItemEntityRenderState s, boolean resting, PoseStack pose) {
		if (!Mods.on(MOD) || s.item.isEmpty()) {
			return;
		}
		AABB box = s.item.getModelBoundingBox();
		float lift = -((float) box.minY) + 0.0625F;

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float bob = Mth.sin(s.ageInTicks / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(s.ageInTicks, s.bobOffset)));
		pose.translate(0.0F, -(bob + lift), 0.0F);

		// 2) physics pose. bobOffset is a per-entity random angle: a stable heading.
		pose.mulPose(Axis.YP.rotation(s.bobOffset));
		float depth = (float) box.getZsize();
		if (!resting) {
			// airborne: vanilla's resting height without the bob, tumbling
			pose.translate(0.0F, lift, 0.0F);
			float deg = s.ageInTicks * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed");
			pose.mulPose(Axis.XP.rotationDegrees(deg % 360f));
		} else if (depth > 0.0625F) {
			// blocks / 3D models: the model's bottom face on the ground
			pose.translate(0.0F, -((float) box.minY), 0.0F);
		} else {
			// flat items: tip the sprite onto its back (local +Z -> world up, so
			// vanilla's stacked copies pile upward), lifted by the stack's half-depth
			// + half the sprite's own thickness so nothing sinks into the floor, and
			// centred on its own middle.
			int copies = Math.max(1, s.count);
			float halfStack = depth * 1.5F * (copies - 1) / 2.0F;
			pose.translate(0.0F, halfStack + depth / 2.0F + 0.01F, 0.0F);
			pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
			pose.translate(0.0F, -((float) (box.minY + box.maxY) / 2.0F), 0.0F);
		}
	}
}
