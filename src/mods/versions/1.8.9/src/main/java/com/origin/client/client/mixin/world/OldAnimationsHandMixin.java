package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations, first person. 1.8.9 shape (vineflower of the mapped Forge jar):
//   public void renderItemInFirstPerson(float partialTicks)
//     f = 1 - lerp(prevEquippedProgress, equippedProgress); rotateArroundXAndY(pitch, yaw);
//     setLightMapFromPlayer(p); rotateWithPlayerRotations((EntityPlayerSP) p, pt);
//     enableRescaleNormal; push; [map | using: switch(action) with swing 0 | idle: swing chain];
//     renderItem(p, itemToRender, FIRST_PERSON); pop; disableRescaleNormal;
//     RenderHelper.disableStandardItemLighting()
//   -> HEAD, cancellable: when OldAnimations.pose() takes this frame's item, the hook runs that same
//      setup through the shadowed vanilla helpers, draws the 1.7 chain and cancels; anything else
//      (maps, the bare arm, a switch that's off) falls through to vanilla untouched.
// ItemRenderer is OptiFine-replaced, so HEAD only (no INVOKE points). The shadowed helpers are
// vanilla members OptiFine keeps; calling them keeps OptiFine's own light-map logic (dynamic lights).
// If any shadow or the target stops matching, the mixin doesn't apply: the hand looks vanilla
// (defaultRequire 0, fail-soft) and the in-world HOOKS check fails in CI.
@Mixin(ItemRenderer.class)
public abstract class OldAnimationsHandMixin {
	@Shadow private ItemStack itemToRender;
	@Shadow private float equippedProgress;
	@Shadow private float prevEquippedProgress;

	@Shadow
	private void rotateArroundXAndY(float angle, float angleY) {
	}

	@Shadow
	private void setLightMapFromPlayer(AbstractClientPlayer clientPlayer) {
	}

	@Shadow
	private void rotateWithPlayerRotations(EntityPlayerSP entityplayerspIn, float partialTicks) {
	}

	@Inject(method = "renderItemInFirstPerson", at = @At("HEAD"), cancellable = true)
	private void originclient$oldHand(float partial, CallbackInfo ci) {
		boolean setUp = false;
		try {
			EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
			ItemStack stack = itemToRender;
			int pose = OldAnimations.pose(p, stack);
			if (pose == 0) {
				return;
			}
			float equip = 1.0F - (prevEquippedProgress + (equippedProgress - prevEquippedProgress) * partial);
			float swing = p.getSwingProgress(partial);
			float pitch = p.prevRotationPitch + (p.rotationPitch - p.prevRotationPitch) * partial;
			float yaw = p.prevRotationYaw + (p.rotationYaw - p.prevRotationYaw) * partial;
			rotateArroundXAndY(pitch, yaw);
			setLightMapFromPlayer(p);
			rotateWithPlayerRotations(p, partial);
			setUp = true;
			GlStateManager.enableRescaleNormal();
			GlStateManager.pushMatrix();
			try {
				OldAnimations.drawFirstPerson((ItemRenderer) (Object) this, p, partial, pose, swing, stack, equip);
			} finally {
				// balanced even if a draw throws
				GlStateManager.popMatrix();
				GlStateManager.disableRescaleNormal();
				RenderHelper.disableStandardItemLighting();
			}
		} catch (Throwable t) {
			OldAnimations.fail("first-person hand", t);
		} finally {
			// once vanilla's setup ran, vanilla must not run it again (the arm sway rotation isn't
			// pushed); a frame without a hand beats a doubled one, and the mod is latched off anyway
			if (setUp) {
				ci.cancel();
			}
		}
	}
}
