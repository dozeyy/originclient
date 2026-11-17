package com.vulkairis.vulkan.pipeline;

/**
 * Unique cache key identifying a Vulkan Graphics Pipeline.
 *
 * <p>Every field here materially affects pipeline compatibility or the rendered
 * result. If a state is missing from this key, two different pipelines can alias
 * onto the same cache entry, which manifests as the wrong depth/blend/raster
 * state being reused across render passes. In particular the shader modules,
 * reflected stage interface, descriptor layout, render pass handle, depth compare
 * op, front face, topology, sample count, blend factors/mode and color write mask
 * must be part of the key. Shader <em>module handles</em> (not source text)
 * identify the code: a pipeline is only ever cached for live modules, and any
 * destroy/recreate cycle must invalidate the key's owner first (see
 * {@code StandaloneVulkanTest.invalidatePipelines}).</p>
 */
public record PipelineKey(
        long vertShaderModule,
        long fragShaderModule,
        String vertexFormatName,
        long renderPass,
        int topology,
        boolean depthTest,
        boolean depthWrite,
        int depthCompareOp,
        int frontFace,
        int cullMode,
        int blendModeOrdinal,
        int srcColorBlendFactor,
        int dstColorBlendFactor,
        int colorBlendOp,
        int srcAlphaBlendFactor,
        int dstAlphaBlendFactor,
        int alphaBlendOp,
        int colorWriteMask,
        int rasterizationSamples,
        long pipelineLayout,
        String stageInterface,
        int featureFlags
) {}
