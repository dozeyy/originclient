package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > Performance > Tile Entity Distance. Same percentage-of-render-
// distance model as Entity Distance, but for block entities (chests, signs,
// banners, etc.). Skips the render call entirely once a block entity is past
// the configured fraction of the render radius.
//
// PER-VERSION DELTA (1.21.11): BlockEntityRenderDispatcher.render is GONE —
// block entities became extract-then-submit, and the draw now goes through
// submit(S, PoseStack, SubmitNodeCollector, CameraRenderState). This file still
// named "render" until 2026-07-26, which with "defaultRequire": 0 meant the
// mixin was skipped in silence and the Tile Entity Distance slider did nothing
// at all. The block position now comes off the extracted render state, and the
// camera position straight off CameraRenderState (so CameraAccessor is no
// longer needed here).
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void originclient$tileEntityCull(BlockEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci) {
		double pct = Mods.num(Mods.PERFORMANCE_ID, "tileEntityDistance");
		if (pct >= 100) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = cameraRenderState.pos;
		BlockPos pos = state.blockPos;
		double max = mc.options.getEffectiveRenderDistance() * 16.0 * (pct / 100.0);
		double dx = pos.getX() + 0.5 - cam.x;
		double dy = pos.getY() + 0.5 - cam.y;
		double dz = pos.getZ() + 0.5 - cam.z;
		if (dx * dx + dy * dy + dz * dz > max * max) {
			ci.cancel();
		}
	}
}
