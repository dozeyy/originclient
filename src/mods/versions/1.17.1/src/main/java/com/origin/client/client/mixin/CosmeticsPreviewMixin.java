package com.origin.client.client.mixin;

import com.origin.client.client.gui.CosmeticsScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Cosmetics screen's turning preview. Before 1.19.4 the only entity-in-GUI
 * draw is InventoryScreen.renderEntityInInventory(x, y, size, mouseX, mouseY,
 * entity), which aims the body at the mouse. While CosmeticsScreen draws its
 * preview (previewYaw set), this swaps in the preview's own yaw and a level
 * head, right before vanilla hands the entity to the renderer (the
 * RenderSystem.runAsFancy call wraps that draw). Vanilla saved the real
 * rotations before setting its own and restores them after the draw, so the
 * player's actual facing is never touched. If this ever fails to apply, the
 * preview simply faces the viewer instead of turning.
 */
@Mixin(InventoryScreen.class)
public abstract class CosmeticsPreviewMixin {
	@Inject(method = "renderEntityInInventory",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;runAsFancy(Ljava/lang/Runnable;)V"))
	private static void originclient$previewYaw(int x, int y, int size, float mouseX, float mouseY,
			LivingEntity entity, CallbackInfo ci) {
		float yaw = CosmeticsScreen.previewYaw;
		if (Float.isNaN(yaw)) {
			return;
		}
		entity.yBodyRot = yaw;
		entity.setYRot(yaw);
		entity.setXRot(0);
		entity.yHeadRot = yaw;
		entity.yHeadRotO = yaw;
	}
}
