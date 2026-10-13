package com.origin.client.client.mixin.world;

import com.origin.client.client.gui.Pose;
import com.origin.client.client.mods.ItemPhysics;
import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Item Size Customizer + Item Physics on dropped items. 1.8.9's
// RenderEntityItem.doRender pushes a matrix and calls its private pose helper
// func_177077_a (unnamed in MCP), which: translates to the entity + bob
// (0.25 x ground scale + the sine bob), spins about Y, and -- for flat items --
// offsets the stack along Z; then the copies are drawn and doRender pops. At
// that helper's RETURN the GL matrix holds exactly vanilla's pose, so this:
//   1. takes the flat-stack offset off, then the spin, then the bob
//      -> the matrix is at the entity origin (1.16.5's render HEAD);
//   2. Item Size: scales there (render-only; hitbox and pickup untouched);
//   3. puts vanilla's bob + spin back and lets ItemPhysics replace them
//      (it undoes them itself, as on 1.16.5);
//   4. puts the stack offset back, so the copies pile on top of the new pose.
// All inside vanilla's own push/pop. Mod(s) off = nothing touched.
@Mixin(RenderEntityItem.class)
public abstract class RenderEntityItemMixin {
	@Inject(method = "func_177077_a", at = @At("RETURN"))
	private void originclient$itemPose(EntityItem item, double x, double y, double z, float partialTicks,
									   IBakedModel model, CallbackInfoReturnable<Integer> cir) {
		boolean size = Mods.on("itemsize");
		boolean physics = ItemPhysics.active();
		if ((!size && !physics) || item == null || item.getEntityItem() == null || model == null) {
			return;
		}
		int copies = cir.getReturnValueI();
		if (copies <= 0) {
			return;
		}
		try {
			float s = ItemSizes.DEFAULT;
			if (size) {
				Item it = item.getEntityItem().getItem();
				Object id = it == null ? null : Item.itemRegistry.getNameForObject(it);
				if (id instanceof ResourceLocation) {
					s = ItemSizes.get((ResourceLocation) id);
				}
			}
			if (!physics && s == ItemSizes.DEFAULT) {
				return;
			}
			Pose pose = new Pose();
			boolean bobbing = ((RenderEntityItem) (Object) this).shouldBob();
			float scaleY = model.getItemCameraTransforms().getTransform(ItemCameraTransforms.TransformType.GROUND).scale.y;
			float now = (float) item.getAge() + partialTicks;
			float bob = bobbing ? MathHelper.sin(now / 10.0F + item.hoverStart) * 0.1F + 0.1F : 0F;
			float spinDeg = (now / 20.0F + item.hoverStart) * (180F / (float) Math.PI);
			float stackZ = model.isGui3d() ? 0F : -0.046875F * (float) (copies - 1) * 0.5F;

			// 1. back to the entity origin
			pose.translate(0.0, 0.0, -stackZ);
			pose.mulPose(Pose.Axis.YP.rotationDegrees(-spinDeg));
			pose.translate(0.0, -(bob + 0.25F * scaleY), 0.0);
			// 2. Item Size, at the origin (so a big item still stands on the ground)
			if (s != ItemSizes.DEFAULT) {
				pose.scale(s, s, s);
			}
			// 3. vanilla's bob + spin again; Item Physics swaps them for its own pose
			pose.translate(0.0, bob + 0.25F * scaleY, 0.0);
			pose.mulPose(Pose.Axis.YP.rotationDegrees(spinDeg));
			if (physics) {
				ItemPhysics.apply(item, partialTicks, pose, bobbing);
			}
			// 4. the flat-stack offset, on top of whichever pose won
			pose.translate(0.0, 0.0, stackZ);
		} catch (Throwable ignored) {
			// worst case the item keeps (part of) vanilla's float + spin this frame
		}
	}
}
