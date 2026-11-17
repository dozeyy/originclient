package com.origin.flywheelvulkan;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.origin.flywheelvulkan.spike.InstancedCubeSpike;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.phys.Vec3;

/**
 * CI-only (-Dorigin.flywheel_vulkan.spike=true): draws the spike's instanced cube row in the
 * world, 4 blocks in front of where the camera first stood, after everything else in the level.
 */
final class SpikeHook {
	private static Vec3 base;

	private SpikeHook() {
	}

	static void register() {
		FlywheelVulkanClient.LOGGER.info("[spike] enabled: drawing {} instanced cubes after the level", InstancedCubeSpike.INSTANCES);
		WorldRenderEvents.LAST.register(ctx -> {
			Vec3 cam = ctx.camera().getPosition();
			if (base == null) {
				Vector3f look = ctx.camera().getLookVector();
				base = new Vec3(Math.floor(cam.x + look.x() * 4) - 5, Math.floor(cam.y) - 1, Math.floor(cam.z + look.z() * 4));
			}
			Matrix4f viewProj = new Matrix4f(ctx.projectionMatrix()).mul(ctx.positionMatrix());
			InstancedCubeSpike.draw(viewProj, cam.x, cam.y, cam.z, base.x, base.y, base.z);
		});
	}
}
