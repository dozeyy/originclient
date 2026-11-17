package com.vulkairis.rendering.exec;

import com.vulkairis.rendergraph.PassNode;

/**
 * The concrete GPU handles and draw parameters for one graph pass, resolved by the higher-level
 * pack-binding layer (pipeline + descriptor set + viewport + geometry or dispatch size).
 *
 * <p>Keeping this separate from the graph means {@link RenderGraphExecutor} can record passes without
 * knowing how a given shader pack maps its programs to pipelines.</p>
 */
public record PassBinding(
        String passName,
        PassNode.Kind kind,
        long pipeline,
        long pipelineLayout,
        long descriptorSet,
        int viewportX,
        int viewportY,
        int viewportWidth,
        int viewportHeight,
        int vertexCount,
        int instanceCount,
        int dispatchX,
        int dispatchY,
        int dispatchZ) {

    /** @return a graphics draw binding for a fullscreen/viewport pass */
    public static PassBinding graphics(String passName, long pipeline, long pipelineLayout,
                                       long descriptorSet, int width, int height, int vertexCount) {
        return new PassBinding(passName, PassNode.Kind.GRAPHICS, pipeline, pipelineLayout, descriptorSet,
                0, 0, width, height, vertexCount, 1, 0, 0, 0);
    }

    /** @return a compute dispatch binding */
    public static PassBinding compute(String passName, long pipeline, long pipelineLayout,
                                      long descriptorSet, int groupsX, int groupsY, int groupsZ) {
        return new PassBinding(passName, PassNode.Kind.COMPUTE, pipeline, pipelineLayout, descriptorSet,
                0, 0, 0, 0, 0, 0, groupsX, groupsY, groupsZ);
    }

    @Override
    public long pipeline() {
        if (this.pipeline != 0L) {
            return this.pipeline;
        }
        try {
            com.vulkairis.translation.VulkairisProgram program =
                    com.vulkairis.translation.VulkanShaderRegistry.findProgramByName(this.passName);
            if (program != null) {
                net.vulkanmod.vulkan.shader.GraphicsPipeline gp =
                        com.vulkairis.bridge.VulkanModProgramBridge.getLinkedPipeline(program.getVirtualProgramId());
                if (gp != null) {
                    net.vulkanmod.vulkan.Renderer renderer = net.vulkanmod.vulkan.Renderer.getInstance();
                    if (renderer != null && renderer.getBoundRenderPass() != null) {
                        return gp.getHandle(net.vulkanmod.vulkan.shader.PipelineState.getCurrentPipelineState(renderer.getBoundRenderPass()));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
