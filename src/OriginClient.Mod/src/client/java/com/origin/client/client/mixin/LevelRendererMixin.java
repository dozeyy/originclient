package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Three render-only client mods in one target class:
//  - Weather Toggle: skips rain/snow rendering entirely (server weather and
//    gameplay effects untouched — this is draw-skipping only).
//  - Custom Sky "Flat" mode: skips the sky pass, leaving the fog color as a
//    cheap flat backdrop (also removes the sky's render cost).
//  - Block Overlay: replaces the vanilla hit outline with a configurable
//    color/opacity/thickness one (thickness is emulated by re-emitting the
//    edges with tiny offsets — line-width state isn't reliable on core GL).
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
	private void originclient$skipWeather(LightTexture lightTexture, float partialTick,
										  double camX, double camY, double camZ, CallbackInfo ci) {
		if (Mods.on("weather")) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
	private void originclient$flatSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
									  Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
		if (Mods.on("customsky") && Mods.mode("customsky", "mode").equals("Flat")) {
			skyFogSetup.run();
			ci.cancel();
		}
	}

	@Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
	private void originclient$blockOverlay(PoseStack poseStack, VertexConsumer consumer, Entity entity,
										   double camX, double camY, double camZ, BlockPos pos,
										   BlockState state, CallbackInfo ci) {
		if (!Mods.on("blockoverlay")) {
			return;
		}
		var level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		ci.cancel();

		int color = Mods.color("blockoverlay", "color");
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		int passes = (int) Math.max(1, Math.min(3, Mods.num("blockoverlay", "thickness")));

		VoxelShape shape = state.getShape(level, pos, net.minecraft.world.phys.shapes.CollisionContext.of(entity));
		double ox = pos.getX() - camX, oy = pos.getY() - camY, oz = pos.getZ() - camZ;
		PoseStack.Pose pose = poseStack.last();

		for (int i = 0; i < passes; i++) {
			// Pseudo-thickness: each extra pass re-draws the edges expanded a
			// hair outward, which reads as a thicker line at any distance.
			float grow = i * 0.0035f;
			shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
				float ax = (float) (x1 + ox) + (x1 < 0.5 ? -grow : grow);
				float ay = (float) (y1 + oy) + (y1 < 0.5 ? -grow : grow);
				float az = (float) (z1 + oz) + (z1 < 0.5 ? -grow : grow);
				float bx = (float) (x2 + ox) + (x2 < 0.5 ? -grow : grow);
				float by = (float) (y2 + oy) + (y2 < 0.5 ? -grow : grow);
				float bz = (float) (z2 + oz) + (z2 < 0.5 ? -grow : grow);
				float nx = bx - ax, ny = by - ay, nz = bz - az;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len > 0) {
					nx /= len;
					ny /= len;
					nz /= len;
				}
				consumer.addVertex(pose, ax, ay, az).setColor(r, g, b, 1f).setNormal(pose, nx, ny, nz);
				consumer.addVertex(pose, bx, by, bz).setColor(r, g, b, 1f).setNormal(pose, nx, ny, nz);
			});
		}
	}
}
