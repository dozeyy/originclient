package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;

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
 */
public final class ItemPhysics {
	private static final String MOD = "itemphysics";
	// Degrees per tick at Rotation Speed 1.0x (default 0.5x = half a turn a second).
	private static final float DEG_PER_TICK = 18f;

	private ItemPhysics() {
	}

	public static void apply(ItemEntity e, float partialTick, PoseStack pose) {
		if (!Mods.on(MOD)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		BakedModel model = mc.getItemRenderer().getModel(e.getItem(), e.level(), null, e.getId());
		ItemTransform ground = model.getTransforms().getTransform(ItemDisplayContext.GROUND);
		float scaleY = ground.scale.y();

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float bob = Mth.sin(((float) e.getAge() + partialTick) / 10.0F + e.bobOffs) * 0.1F + 0.1F;
		pose.mulPose(Axis.YP.rotation(-e.getSpin(partialTick)));
		pose.translate(0.0F, -(bob + 0.25F * scaleY), 0.0F);

		// 2) physics pose. bobOffs is a per-entity random angle: a stable heading.
		boolean resting = e.onGround() || e.isInWater();
		pose.mulPose(Axis.YP.rotation(e.bobOffs));
		if (!resting) {
			// airborne: vanilla's resting height without the bob, tumbling
			pose.translate(0.0F, 0.25F * scaleY, 0.0F);
			float deg = ((float) e.getAge() + partialTick) * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed");
			pose.mulPose(Axis.XP.rotationDegrees(deg % 360f));
		} else if (model.isGui3d()) {
			// blocks: drop the model so its bottom face (ground offset - half its
			// scaled unit cube) touches the ground
			pose.translate(0.0F, -(ground.translation.y() - 0.5F * scaleY), 0.0F);
		} else {
			// flat items: tip the sprite onto its back (local +Z -> world up, so
			// vanilla's stacked copies pile upward) and lift it by the stack's
			// half-depth + the sprite's own thickness so nothing sinks into the floor
			int copies = renderedCopies(e.getItem().getCount());
			float halfStack = 0.09375F * (copies - 1) * 0.5F * ground.scale.z();
			float lift = halfStack + ground.scale.z() / 32F + 0.01F;
			pose.translate(0.0F, lift, 0.0F);
			pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
			// cancel the GROUND transform's upward offset, which now points sideways
			pose.translate(0.0F, -ground.translation.y(), 0.0F);
		}
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
