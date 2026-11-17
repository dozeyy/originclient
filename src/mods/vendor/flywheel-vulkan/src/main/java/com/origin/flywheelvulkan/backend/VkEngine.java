package com.origin.flywheelvulkan.backend;

import java.util.List;

import com.origin.flywheelvulkan.FlywheelVulkanClient;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import net.minecraft.world.level.LevelAccessor;

/**
 * Flywheel's EngineImpl with its two GL entry points replaced. EngineImpl.render wraps the draw
 * manager in GlStateTracker (GL calls, which VulkanMod has no context for); here the same steps
 * run without it: Flywheel fills its uniform blocks and embedding matrices CPU side, then
 * {@link VkDrawManager} copies them into Vulkan buffers and records the draws.
 *
 * <p>Visuals, instancers, light tracking and the render origin stay Flywheel's own code. Any
 * exception switches this backend off for the session and lets Flywheel choose again, the same
 * fallback Flywheel's own backends use.
 */
public final class VkEngine extends EngineImpl {
	/** EngineImpl's max render-origin distance, as Flywheel's own backends pass it (Backends, 1.0.6). */
	private static final int MAX_ORIGIN_DISTANCE = 256;

	private final VkDrawManager vk;

	public VkEngine(LevelAccessor level) {
		this(level, new VkDrawManager());
	}

	private VkEngine(LevelAccessor level, VkDrawManager drawManager) {
		super(level, drawManager, MAX_ORIGIN_DISTANCE);
		this.vk = drawManager;
	}

	@Override
	public void render(RenderContext context) {
		try {
			Uniforms.update(context);
			environmentStorage().flush();
			vk.renderFrame(context, renderOrigin(), lightStorage(), environmentStorage());
		} catch (Exception | LinkageError e) {
			FlywheelVulkanClient.LOGGER.error("[flywheel-vulkan] origin:vulkan render failed, falling back", e);
			vk.triggerFallback();
		}
	}

	@Override
	public void renderCrumbling(RenderContext context, List<CrumblingBlock> crumblingBlocks) {
		try {
			vk.renderCrumbling(context, crumblingBlocks, lightStorage());
		} catch (Exception | LinkageError e) {
			FlywheelVulkanClient.LOGGER.error("[flywheel-vulkan] origin:vulkan crumbling failed, falling back", e);
			vk.triggerFallback();
		}
	}
}
