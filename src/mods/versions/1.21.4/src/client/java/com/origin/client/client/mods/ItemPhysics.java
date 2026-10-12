package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.client.ext.ItemPhysicsState;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;

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
 * PER-VERSION (1.21.2-1.21.4): works from the item's render state instead of the
 * live entity -- ageInTicks (age + partial tick), bobOffset, count and the baked
 * GROUND transform are vanilla's own state fields; the resting flag is recorded
 * at extraction by ItemPhysicsMixin. The maths is 1.21.1's, input for input.
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
		ItemTransform ground = s.item.transform();
		float scaleY = ground.scale.y();

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float bob = Mth.sin(s.ageInTicks / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(s.ageInTicks, s.bobOffset)));
		pose.translate(0.0F, -(bob + 0.25F * scaleY), 0.0F);

		// 2) physics pose. bobOffset is a per-entity random angle: a stable heading.
		boolean resting = ((ItemPhysicsState) s).originclient$isResting();
		pose.mulPose(Axis.YP.rotation(s.bobOffset));
		if (!resting) {
			// airborne: vanilla's resting height without the bob, tumbling
			pose.translate(0.0F, 0.25F * scaleY, 0.0F);
			float deg = s.ageInTicks * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed");
			pose.mulPose(Axis.XP.rotationDegrees(deg % 360f));
		} else if (s.item.isGui3d()) {
			// blocks: drop the model so its bottom face (ground offset - half its
			// scaled unit cube) touches the ground
			pose.translate(0.0F, -(ground.translation.y() - 0.5F * scaleY), 0.0F);
		} else {
			// flat items: tip the sprite onto its back (local +Z -> world up, so
			// vanilla's stacked copies pile upward) and lift it by the stack's
			// half-depth + the sprite's own thickness so nothing sinks into the floor.
			// s.count is vanilla's rendered-copies count (getRenderedAmount).
			int copies = Math.max(1, s.count);
			float halfStack = 0.09375F * (copies - 1) * 0.5F * ground.scale.z();
			float lift = halfStack + ground.scale.z() / 32F + 0.01F;
			pose.translate(0.0F, lift, 0.0F);
			pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
			// cancel the GROUND transform's upward offset, which now points sideways
			pose.translate(0.0F, -ground.translation.y(), 0.0F);
		}
	}
}
