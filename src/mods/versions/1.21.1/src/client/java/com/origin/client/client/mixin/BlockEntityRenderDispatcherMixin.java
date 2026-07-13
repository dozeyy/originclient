package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > Performance > Tile Entity Distance. Same percentage-of-render-
// distance model as Entity Distance, but for block entities (chests, signs,
// banners, etc.). Skips the render call entirely once a block entity is past
// the configured fraction of the render radius.
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$tileEntityCull(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
			MultiBufferSource bufferSource, CallbackInfo ci) {
		double pct = Mods.num(Mods.PERFORMANCE_ID, "tileEntityDistance");
		if (pct >= 100) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		BlockPos pos = blockEntity.getBlockPos();
		double max = mc.options.getEffectiveRenderDistance() * 16.0 * (pct / 100.0);
		double dx = pos.getX() + 0.5 - cam.x;
		double dy = pos.getY() + 0.5 - cam.y;
		double dz = pos.getZ() + 0.5 - cam.z;
		if (dx * dx + dy * dy + dz * dz > max * max) {
			ci.cancel();
		}
	}
}
