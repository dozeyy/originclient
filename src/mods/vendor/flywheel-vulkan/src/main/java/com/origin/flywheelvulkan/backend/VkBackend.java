package com.origin.flywheelvulkan.backend;

import com.origin.flywheelvulkan.FlywheelVulkanClient;

import com.origin.flywheelvulkan.shader.VkLayout;

import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.Vulkan;

/**
 * Registers origin:vulkan with Flywheel's backend registry.
 *
 * <p>Priority 100: below Flywheel's indirect (1000) and instancing (500), above off (0). Under
 * VulkanMod those two report unsupported (no GL capabilities), so BackendManagerImpl.chooseBackend
 * falls to the next lower supported one: this. Picked automatically, nothing for the player to set.
 *
 * <p>Supported only while: the reflection targets resolved, Flywheel's shader sources are loaded,
 * VulkanMod's device exists and this backend never failed this session. A shader pack does not turn
 * it off: with Origin's VulkaIris fork the instances draw through the pack's own programs
 * (PackDraw), and without it they draw unshaded (phase 1), never flywheel:off.
 * {@code -Dflywheelvulkan.forceOff=true} reports unsupported, only to measure the old fallback.
 */
public final class VkBackend {
	public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("origin", "vulkan");
	private static final int PRIORITY = 100;

	private static boolean internalsOk;
	private static boolean loggedSupported;

	private VkBackend() {
	}

	public static void register() {
		try {
			FlywheelInternals.resolve();
			// The Vulkan pipelines hard-code Flywheel's 36-byte InternalVertex layout (VkLayout).
			if (InternalVertex.STRIDE != VkLayout.VERTEX_STRIDE) {
				throw new IllegalStateException("InternalVertex.STRIDE " + InternalVertex.STRIDE + " != " + VkLayout.VERTEX_STRIDE);
			}
			internalsOk = true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			FlywheelVulkanClient.LOGGER.error("[flywheel-vulkan] Flywheel internals not as expected; origin:vulkan stays unsupported", e);
		}
		SimpleBackend.builder()
				.engineFactory(VkEngine::new)
				.priority(PRIORITY)
				.supported(VkBackend::supported)
				.register(ID);
	}

	private static final boolean FORCE_OFF = Boolean.getBoolean("flywheelvulkan.forceOff");

	static boolean supported() {
		boolean ok = internalsOk
				&& !VkDrawManager.failed()
				&& FlwPrograms.SOURCES != null
				&& Vulkan.getVkDevice() != null
				&& !FORCE_OFF;
		if (ok && !loggedSupported) {
			loggedSupported = true;
			FlywheelVulkanClient.LOGGER.info("[flywheel-vulkan] origin:vulkan is supported (VulkanMod device up, Flywheel shader sources loaded)");
		}
		return ok;
	}
}
