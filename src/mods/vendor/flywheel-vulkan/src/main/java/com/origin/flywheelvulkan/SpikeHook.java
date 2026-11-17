package com.origin.flywheelvulkan;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.origin.flywheelvulkan.spike.InstancedCubeSpike;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.phys.Vec3;

/**
 * CI-only (-Dorigin.flywheel_vulkan.spike=true): draws the spike's instanced cube row in the
 * world, 6 blocks in front of where the camera first stood in each level, at eye height, after
 * everything else in the level. Re-anchored per level: dev-shots opens a second (flat) world,
 * and a row anchored in the first world is out of view there (run 44002855831).
 */
final class SpikeHook {
	private static Vec3 base;
	private static Object baseLevel;

	private SpikeHook() {
	}

	static void register() {
		FlywheelVulkanClient.LOGGER.info("[spike] enabled: drawing {} instanced cubes after the level", InstancedCubeSpike.INSTANCES);
		WorldRenderEvents.LAST.register(ctx -> {
			Vec3 cam = ctx.camera().getPosition();
			if (base == null || baseLevel != ctx.world()) {
				Vector3f look = ctx.camera().getLookVector();
				baseLevel = ctx.world();
				base = new Vec3(Math.floor(cam.x + look.x() * 6) - 5, Math.floor(cam.y) - 1, Math.floor(cam.z + look.z() * 6));
				FlywheelVulkanClient.LOGGER.info("[spike] row anchored at {} (camera {})", base, cam);
			}
			Matrix4f viewProj = new Matrix4f(ctx.projectionMatrix()).mul(ctx.positionMatrix());
			InstancedCubeSpike.draw(viewProj, cam.x, cam.y, cam.z, base.x, base.y, base.z);
		});
	}
}
