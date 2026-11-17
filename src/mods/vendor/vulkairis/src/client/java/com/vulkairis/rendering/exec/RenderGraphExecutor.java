package com.vulkairis.rendering.exec;

import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.rendergraph.CompiledRenderGraph;
import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendering.state.VulkairisRenderState;
import com.vulkairis.rendering.sync.BarrierGenerator;
import com.vulkairis.rendering.sync.ResourceStateTracker;
import com.vulkairis.vulkan.VulkanSyncPrimitives;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

/**
 * Walks a compiled render graph and records its passes into VulkanMod's active command buffer:
 * apply the image-layout barriers for each pass, then bind and draw (graphics) or dispatch
 * (compute).
 *
 * <p>This is the glue the GPU phases need: the graph decides order, the sync layer decides barriers,
 * the resource layer provides image handles, and the pack-binding layer provides pipelines and
 * descriptor sets. Render-pass begin/end for offscreen targets remains owned by the caller (matching
 * VulkanMod's frame), so this records commands into the currently bound pass.</p>
 */
public final class RenderGraphExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/GraphExecutor");

    /**
     * @param passesRecorded  passes for which commands were recorded
     * @param barriersApplied image-layout barriers applied
     * @param submitted       whether a command buffer was available
     * @param detail          human-readable status
     */
    public record ExecutionReport(int passesRecorded, int barriersApplied, boolean submitted, String detail) {
    }

    private RenderGraphExecutor() {
    }

    /**
     * Records the graph.
     *
     * @param graph       the compiled graph
     * @param bindings    per-pass GPU bindings, keyed by pass name
     * @param imageHandles resource name to {@code VkImage} handle
     * @param tracker     layout tracker to keep in sync
     * @return the execution report
     */
    public static ExecutionReport execute(
            CompiledRenderGraph graph,
            Map<String, PassBinding> bindings,
            Map<String, Long> imageHandles,
            ResourceStateTracker tracker) {
        return execute(graph, bindings, imageHandles, tracker, null, null, null, null);
    }

    /**
     * Records the graph, optionally collecting per-pass outcomes.
     *
     * @param recordedOut filled with recorded pass names, or {@code null}
     * @param skippedOut  filled with skipped pass names (no binding or gated compute), or null
     * @return the execution report
     */
    public static ExecutionReport execute(
            CompiledRenderGraph graph,
            Map<String, PassBinding> bindings,
            Map<String, Long> imageHandles,
            ResourceStateTracker tracker,
            List<String> recordedOut,
            List<String> skippedOut,
            Map<String, net.vulkanmod.vulkan.framebuffer.Framebuffer> offscreenFbs,
            Map<String, net.vulkanmod.vulkan.framebuffer.RenderPass> offscreenRps) {
        if (offscreenFbs == null) offscreenFbs = java.util.Map.of();
        if (offscreenRps == null) offscreenRps = java.util.Map.of();
        if (graph == null) {
            return new ExecutionReport(0, 0, false, "no graph");
        }
        VkCommandBuffer cmd;
        try {
            cmd = Renderer.getCommandBuffer();
        } catch (Throwable t) {
            return new ExecutionReport(0, 0, false, "command buffer unavailable: " + t.getMessage());
        }
        if (cmd == null) {
            return new ExecutionReport(0, 0, false, "no active command buffer");
        }
        // Diagnostic: record the currently bound render pass / framebuffer so offscreen
        // presentation gaps are visible in the log.
        try {
            net.vulkanmod.vulkan.Renderer vr = net.vulkanmod.vulkan.Renderer.getInstance();
            if (vr != null) {
                LOGGER.info("[Vulkairis/GraphExecutor] bound renderPass={} framebuffer={} swapchainImageIdx={}",
                        vr.getBoundRenderPass() != null ? vr.getBoundRenderPass().getId() : -1,
                        vr.getBoundFramebuffer() != null ? vr.getBoundFramebuffer().hashCode() : -1,
                        net.vulkanmod.vulkan.Renderer.getCurrentImage());
            }
        } catch (Throwable ignored) {
        }

        List<BarrierGenerator.PipelineBarrier> barriers = BarrierGenerator.generate(graph);
        Map<String, List<BarrierGenerator.PipelineBarrier>> barriersByPass = groupBarriersByPass(barriers);

        int applied = 0;
        int recorded = 0;
        int skipped = 0;
        for (PassNode pass : graph.getExecutionOrder()) {
            for (BarrierGenerator.PipelineBarrier barrier : barriersByPass.getOrDefault(pass.name(), List.of())) {
                long image = imageHandles.getOrDefault(barrier.resourceName(), 0L);
                if (image != 0L) {
                    VulkanSyncPrimitives.pipelineImageBarrier(cmd, image,
                            barrier.fromLayout(), barrier.toLayout(),
                            barrier.srcAccessMask(), barrier.dstAccessMask(),
                            barrier.srcStageMask(), barrier.dstStageMask(),
                            aspectFor(barrier.toLayout()));
                    if (tracker != null) {
                        tracker.apply(barrier);
                    }
                    applied++;
                }
            }

            PassBinding binding = bindings == null ? null : bindings.get(pass.name());
            if (binding == null) {
                skipped++;
                if (skippedOut != null) {
                    skippedOut.add(pass.name());
                }
                continue;
            }
            if (binding.kind() == PassNode.Kind.COMPUTE && !ComputeGate.isOperational()) {
                skipped++;
                if (skippedOut != null) {
                    skippedOut.add(pass.name());
                }
                StageDiagnostics.warn("compute dispatch",
                        "gated compute pass '" + pass.name() + "' skipped: no live device");
                continue;
            }
            boolean ok = switch (binding.kind()) {
                case COMPUTE -> {
                    boolean dispatched = ComputeDispatcher.dispatch(cmd,
                            binding.pipeline(), binding.pipelineLayout(), binding.descriptorSet(),
                            binding.dispatchX(), binding.dispatchY(), binding.dispatchZ(),
                            VulkairisRenderState.get().synchronization());
                    if (dispatched) {
                        // ponytail: only barrier if the next pass is actually graphics or compute
                        ComputeDispatcher.barrierAfterCompute(cmd);
                    }
                    yield dispatched;
                }
                case GRAPHICS -> recordGraphicsWithRenderPass(cmd, binding, pass.name(),
                        offscreenFbs, offscreenRps);
                case TRANSFER -> false;
            };
            if (ok) {
                recorded++;
                if (recordedOut != null) {
                    recordedOut.add(pass.name());
                }
            }
        }

        LOGGER.debug("[Vulkairis/GraphExecutor] Recorded {} pass(es), applied {} barrier(s)",
                recorded, applied);
        String detail = "recorded " + recorded + " pass(es)";
        if (skipped > 0) {
            detail += "; " + skipped + " skipped (no binding or gated compute)";
        }
        return new ExecutionReport(recorded, applied, true, detail);
    }

    /** Groups barriers by the pass at which they apply, preserving order. */
    public static Map<String, List<BarrierGenerator.PipelineBarrier>> groupBarriersByPass(
            List<BarrierGenerator.PipelineBarrier> barriers) {
        Map<String, List<BarrierGenerator.PipelineBarrier>> grouped = new LinkedHashMap<>();
        if (barriers == null) {
            return grouped;
        }
        for (BarrierGenerator.PipelineBarrier barrier : barriers) {
            grouped.computeIfAbsent(barrier.passName(), ignored -> new ArrayList<>()).add(barrier);
        }
        Map<String, List<BarrierGenerator.PipelineBarrier>> frozen = new LinkedHashMap<>();
        grouped.forEach((key, value) -> frozen.put(key, Collections.unmodifiableList(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static boolean recordGraphicsWithRenderPass(
            VkCommandBuffer cmd, PassBinding binding, String passName,
            Map<String, net.vulkanmod.vulkan.framebuffer.Framebuffer> offscreenFbs,
            Map<String, net.vulkanmod.vulkan.framebuffer.RenderPass> offscreenRps) {
        // Final pass writes into the already-bound swapchain pass.
        boolean isFinal = passName != null && passName.toLowerCase(java.util.Locale.ROOT).contains("final");
        net.vulkanmod.vulkan.framebuffer.Framebuffer fb = null;
        net.vulkanmod.vulkan.framebuffer.RenderPass rp = null;
        if (!isFinal && offscreenFbs != null && !offscreenFbs.isEmpty()) {
            // Pick the first available offscreen framebuffer/render pass.
            java.util.Map.Entry<String, net.vulkanmod.vulkan.framebuffer.Framebuffer> e =
                    offscreenFbs.entrySet().iterator().next();
            fb = e.getValue();
            rp = offscreenRps.get(e.getKey());
        }
        net.vulkanmod.vulkan.Renderer renderer = null;
        try { renderer = net.vulkanmod.vulkan.Renderer.getInstance(); } catch (Throwable ignored) {}
        boolean began = false;
        if (fb != null && rp != null && renderer != null) {
            try {
                began = renderer.beginRenderPass(rp, fb);
            } catch (Throwable t) {
                began = false;
            }
        }
        boolean result;
        try {
            result = recordGraphics(cmd, binding);
        } finally {
            if (began && renderer != null) {
                try { renderer.endRenderPass(); } catch (Throwable ignored) {}
            }
        }
        return result;
    }

    private static boolean recordGraphics(VkCommandBuffer cmd, PassBinding binding) {
        if (binding.pipeline() == 0L) {
            return false;
        }
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, binding.pipeline());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (binding.viewportWidth() > 0 && binding.viewportHeight() > 0) {
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                        .x((float) binding.viewportX())
                        .y((float) binding.viewportY())
                        .width((float) binding.viewportWidth())
                        .height((float) binding.viewportHeight())
                        .minDepth(0.0f)
                        .maxDepth(1.0f);
                VK10.vkCmdSetViewport(cmd, 0, viewport);

                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(binding.viewportX(), binding.viewportY());
                scissor.extent().set(binding.viewportWidth(), binding.viewportHeight());
                VK10.vkCmdSetScissor(cmd, 0, scissor);
            }

            if (binding.descriptorSet() != 0L && binding.pipelineLayout() != 0L) {
                LongBuffer sets = stack.longs(binding.descriptorSet());
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                        binding.pipelineLayout(), 0, sets, null);
            }
        }

        VK10.vkCmdDraw(cmd, binding.vertexCount(), Math.max(1, binding.instanceCount()), 0, 0);
        return true;
    }

    private static int aspectFor(int layout) {
        if (layout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
            return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
        }
        return VK_IMAGE_ASPECT_COLOR_BIT;
    }
}
