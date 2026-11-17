package com.vulkairis.vulkan.pipeline;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating native Vulkan VkPipeline objects directly from
 * compiled VkShaderModule handles. NEVER accepts GLSL source.
 *
 * <p>Phase 6 ownership decision (verified against the VulkanMod 0.6.7 bytecode):
 * VulkanMod builds traditional {@code VkRenderPass}-compatible pipelines through its
 * {@code Pipeline.Builder}/{@code GraphicsPipeline} abstractions (per-{@code PipelineState}
 * caching, VulkanMod-owned descriptor layouts), with no dynamic-rendering fork. The
 * <strong>shader-pack path therefore stays on route A</strong> —
 * {@code VulkanModProgramBridge} builds every real program through those abstractions and this
 * factory is <strong>debug-only</strong>: it serves the explicitly toggled controlled draws
 * ({@code StandaloneVulkanTest}), which create pipelines against the currently bound pass id,
 * bind through the active recording command buffer inside VulkanMod's pass, use their own
 * layout/descriptor sets, and restore dynamic viewport/scissor afterwards. Raw pipelines from
 * here must never back a shader-pack program.</p>
 */
public class VulkanPipelineFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/PipelineFactory");

    /**
     * Shader objects keyed by their native VkShaderModule handle. {@link PipelineParams} deliberately
     * keeps its original record shape (it is part of the public/tested API and is constructed
     * positionally), so the stage-interface compatibility check resolves the shaders through this
     * registry instead. The program-based factory populates it via {@link #registerShaderPair}.
     */
    private static final Map<Long, VulkairisShader> SHADERS_BY_MODULE = new ConcurrentHashMap<>();

    /** Default rasterization/compatibility state used when a caller does not override it. */
    public static final int DEFAULT_TOPOLOGY = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    public static final int DEFAULT_DEPTH_COMPARE_OP = VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
    /**
     * Phase 9: mirrored for the compile-time Y flip (see the transform ledger). The compiler
     * inverts Y, which reverses triangle winding; a culling pipeline that kept GL's CCW face
     * would cull exactly the wrong triangles. The controlled draws run cull-free, so this only
     * takes effect for future culling pipelines.
     */
    public static final int DEFAULT_FRONT_FACE = VK10.VK_FRONT_FACE_CLOCKWISE;
    public static final int DEFAULT_SAMPLE_COUNT = VK10.VK_SAMPLE_COUNT_1_BIT;
    public static final int BACK_FACE_CULL_MODE = VK10.VK_CULL_MODE_BACK_BIT;
    public static final int NO_CULL_MODE = VK10.VK_CULL_MODE_NONE;

    /**
     * Minecraft's translucent pass keeps RGB as {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}
     * but expects the destination alpha to accumulate (alpha = ONE, ONE_MINUS_SRC_ALPHA).
     * Using {@code ONE, ZERO} corrupts destination alpha and produces bright halos.
     */
    public static final int TRANSLUCENT_SRC_COLOR_FACTOR = VK10.VK_BLEND_FACTOR_SRC_ALPHA;
    public static final int TRANSLUCENT_DST_COLOR_FACTOR = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    public static final int TRANSLUCENT_SRC_ALPHA_FACTOR = VK10.VK_BLEND_FACTOR_ONE;
    public static final int TRANSLUCENT_DST_ALPHA_FACTOR = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    public static final int TRANSLUCENT_COLOR_BLEND_OP = VK10.VK_BLEND_OP_ADD;
    public static final int TRANSLUCENT_ALPHA_BLEND_OP = VK10.VK_BLEND_OP_ADD;
    public static final int DEFAULT_COLOR_WRITE_MASK =
            VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                    | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT;

    public record PipelineParams(
            long vertShaderModule,
            long fragShaderModule,
            long pipelineLayout,
            long renderPass,
            VertexFormat vertexFormat,
            boolean depthTest,
            boolean depthWrite,
            boolean cull,
            boolean blend,
            long pipelineCache,
            int topology,
            int depthCompareOp,
            int frontFace,
            int rasterizationSamples,
            boolean depthBiasEnable,
            float depthBiasConstantFactor,
            float depthBiasSlopeFactor,
            float depthBiasClamp
    ) {
        /**
         * Backwards-compatible constructor preserving the original parameter list.
         * Depth compare/ front face / topology / sample count fall back to the
         * canonical defaults and depth bias stays disabled.
         */
        public PipelineParams(
                long vertShaderModule,
                long fragShaderModule,
                long pipelineLayout,
                long renderPass,
                VertexFormat vertexFormat,
                boolean depthTest,
                boolean depthWrite,
                boolean cull,
                boolean blend,
                long pipelineCache
        ) {
            this(
                    vertShaderModule,
                    fragShaderModule,
                    pipelineLayout,
                    renderPass,
                    vertexFormat,
                    depthTest,
                    depthWrite,
                    cull,
                    blend,
                    pipelineCache,
                    DEFAULT_TOPOLOGY,
                    DEFAULT_DEPTH_COMPARE_OP,
                    DEFAULT_FRONT_FACE,
                    DEFAULT_SAMPLE_COUNT,
                    false,
                    0.0f,
                    0.0f,
                    0.0f
            );
        }
    }

    /**
     * Registers the Java shader objects backing a module pair so that pipeline creation can validate
     * the vertex/fragment interface. Called by the program-based factory path; safe to call
     * repeatedly and harmless when the module handles are not yet allocated.
     */
    public static void registerShaderPair(VulkairisShader vert, VulkairisShader frag) {
        if (vert != null && vert.getVkShaderModule() != 0L) {
            SHADERS_BY_MODULE.put(vert.getVkShaderModule(), vert);
        }
        if (frag != null && frag.getVkShaderModule() != 0L) {
            SHADERS_BY_MODULE.put(frag.getVkShaderModule(), frag);
        }
    }

    /**
     * Runs the pure stage-interface compatibility check when both shaders are known. Returns an
     * empty list (and therefore skips the check) when only native module handles are available.
     */
    private static List<String> checkStageCompatibility(PipelineParams params) {
        VulkairisShader vert = SHADERS_BY_MODULE.get(params.vertShaderModule());
        VulkairisShader frag = SHADERS_BY_MODULE.get(params.fragShaderModule());
        if (vert == null || frag == null) {
            return List.of();
        }
        return PipelineCompatibilityValidator.validate(vert.getReflection(), frag.getReflection(), 1);
    }

    /**
     * Creates a native VkPipeline directly from shader modules and render parameters.
     */
    public static long createGraphicsPipeline(VkDevice device, PipelineParams params) {
        // Parameter guards fire before device checks so misconfigured callers get the precise
        // error even when the device is also unavailable; nothing native is touched either way.
        if (params.vertShaderModule() == 0L || params.fragShaderModule() == 0L) {
            throw new IllegalArgumentException(String.format(
                    "Invalid shader module handles: vert=0x%s, frag=0x%s",
                    Long.toHexString(params.vertShaderModule()),
                    Long.toHexString(params.fragShaderModule())
            ));
        }
        if (params.pipelineLayout() == 0L) {
            throw new IllegalArgumentException("Cannot create VkPipeline: pipelineLayout is 0");
        }
        if (params.renderPass() == 0L) {
            throw new IllegalArgumentException("Cannot create VkPipeline: renderPass is 0");
        }
        if (!VulkanBackendAdapter.isLive(device)) {
            throw new IllegalStateException("Cannot create VkPipeline: VkDevice is not live");
        }

        // Reject incompatible vertex/fragment interfaces before touching the driver so a bad
        // pipeline can never be bound and drawn with.
        List<String> compatibilityProblems = checkStageCompatibility(params);
        if (!compatibilityProblems.isEmpty()) {
            for (String problem : compatibilityProblems) {
                LOGGER.warn("[Vulkairis/Pipeline] compatibility: {}", problem);
            }
            StageDiagnostics.fail("Pipeline compatibility",
                    String.join("; ", compatibilityProblems), null);
            return 0L;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Shader stages
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            ByteBuffer mainEntry = stack.UTF8("main");

            stages.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_VERTEX_BIT)
                    .module(params.vertShaderModule())
                    .pName(mainEntry);

            stages.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(params.fragShaderModule())
                    .pName(mainEntry);

            // 2. Vertex input state
            VkPipelineVertexInputStateCreateInfo vertexInputInfo =
                    VertexInputDescription.createVertexInputState(stack, params.vertexFormat(), 0);

            // 3. Input assembly state
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(params.topology())
                    .primitiveRestartEnable(false);

            // 4. Viewport & scissor state (dynamic)
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            // 5. Rasterization state
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(params.cull() ? BACK_FACE_CULL_MODE : NO_CULL_MODE)
                    .frontFace(params.frontFace())
                    .depthBiasEnable(params.depthBiasEnable())
                    .depthBiasConstantFactor(params.depthBiasConstantFactor())
                    .depthBiasClamp(params.depthBiasClamp())
                    .depthBiasSlopeFactor(params.depthBiasSlopeFactor());

            // 6. Multisample state
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(params.rasterizationSamples());

            // 7. Depth stencil state
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(params.depthTest())
                    .depthWriteEnable(params.depthWrite())
                    .depthCompareOp(params.depthCompareOp())
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            // 8. Color blend state
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(DEFAULT_COLOR_WRITE_MASK);
            if (params.blend()) {
                colorBlendAttachment.blendEnable(true);
                colorBlendAttachment.srcColorBlendFactor(TRANSLUCENT_SRC_COLOR_FACTOR);
                colorBlendAttachment.dstColorBlendFactor(TRANSLUCENT_DST_COLOR_FACTOR);
                colorBlendAttachment.colorBlendOp(TRANSLUCENT_COLOR_BLEND_OP);
                colorBlendAttachment.srcAlphaBlendFactor(TRANSLUCENT_SRC_ALPHA_FACTOR);
                colorBlendAttachment.dstAlphaBlendFactor(TRANSLUCENT_DST_ALPHA_FACTOR);
                colorBlendAttachment.alphaBlendOp(TRANSLUCENT_ALPHA_BLEND_OP);
            } else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            // 9. Dynamic states: Viewport and Scissor
            IntBuffer dynamicStates = stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates);

            // 10. Pipeline create info
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(params.pipelineLayout())
                    .renderPass(params.renderPass())
                    .subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            long cacheHandle = params.pipelineCache();
            int res = VK10.vkCreateGraphicsPipelines(device, cacheHandle, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateGraphicsPipelines failed with error code: " + res);
            }

            long pipeline = pPipeline.get(0);
            LOGGER.info("[Vulkairis] Created native VkPipeline 0x{} from VkShaderModules [vert: 0x{}, frag: 0x{}]",
                    Long.toHexString(pipeline),
                    Long.toHexString(params.vertShaderModule()),
                    Long.toHexString(params.fragShaderModule()));
            return pipeline;
        }
    }

    /**
     * Builds the in-memory cache key for a set of pipeline parameters. Kept pure
     * (no native Vulkan calls) so it can be unit-tested headlessly.
     */
    public static PipelineKey buildPipelineKey(PipelineParams params) {
        boolean blend = params.blend();
        return new PipelineKey(
                params.vertShaderModule(),
                params.fragShaderModule(),
                params.vertexFormat() != null ? params.vertexFormat().toString() : "none",
                params.renderPass(),
                params.topology(),
                params.depthTest(),
                params.depthWrite(),
                params.depthCompareOp(),
                params.frontFace(),
                params.cull() ? BACK_FACE_CULL_MODE : NO_CULL_MODE,
                blend ? 1 : -1,
                blend ? TRANSLUCENT_SRC_COLOR_FACTOR : 0,
                blend ? TRANSLUCENT_DST_COLOR_FACTOR : 0,
                blend ? TRANSLUCENT_COLOR_BLEND_OP : 0,
                blend ? TRANSLUCENT_SRC_ALPHA_FACTOR : 0,
                blend ? TRANSLUCENT_DST_ALPHA_FACTOR : 0,
                blend ? TRANSLUCENT_ALPHA_BLEND_OP : 0,
                DEFAULT_COLOR_WRITE_MASK,
                params.rasterizationSamples(),
                params.pipelineLayout(),
                stageInterfaceFingerprint(params.vertShaderModule(), params.fragShaderModule()),
                featureFlags(params)
        );
    }

    /**
     * Feature-flag bits folded into the cache key: bit0 depth test, bit1 depth write,
     * bit2 culling, bit3 blending, bit4 depth bias.
     */
    public static int featureFlags(PipelineParams params) {
        int flags = 0;
        if (params.depthTest()) flags |= 1;
        if (params.depthWrite()) flags |= 2;
        if (params.cull()) flags |= 4;
        if (params.blend()) flags |= 8;
        if (params.depthBiasEnable()) flags |= 16;
        return flags;
    }

    /**
     * Fingerprints the reflected vertex-output/fragment-input interface for a module pair:
     * {@code v[loc:type,...]|f[loc:type,...]} sorted by location, or "" when either shader is
     * unknown. Two pipelines alias only when their stages agree on the interface, so a
     * reflection change can never silently reuse a stale pipeline.
     */
    public static String stageInterfaceFingerprint(long vertModule, long fragModule) {
        return stageInterfaceFingerprint(
                SHADERS_BY_MODULE.get(vertModule), SHADERS_BY_MODULE.get(fragModule));
    }

    /** Direct form of {@link #stageInterfaceFingerprint(long, long)} for known shader objects. */
    public static String stageInterfaceFingerprint(VulkairisShader vert, VulkairisShader frag) {
        if (vert == null || frag == null
                || !vert.getReflection().isComplete() || !frag.getReflection().isComplete()) {
            return "";
        }
        StringBuilder fingerprint = new StringBuilder("v[");
        vert.getReflection().getStageOutputs().stream()
                .sorted((a, b) -> Integer.compare(a.location(), b.location()))
                .forEach(o -> fingerprint.append(o.location()).append(':').append(o.type()).append(','));
        fingerprint.append("]|f[");
        frag.getReflection().getStageInputs().stream()
                .sorted((a, b) -> Integer.compare(a.location(), b.location()))
                .forEach(i -> fingerprint.append(i.location()).append(':').append(i.type()).append(','));
        fingerprint.append(']');
        return fingerprint.toString();
    }

    /**
     * Convenience method using the active logical device and pipeline cache.
     */
    public static long createGraphicsPipeline(PipelineParams params) {
        return createGraphicsPipeline(VulkanBackendAdapter.getDevice(), params);
    }

    /**
     * Convenience method creating a pipeline for a VulkairisProgram.
     */
    public static long createGraphicsPipeline(VulkairisProgram program, long renderPass,
                                             VertexFormat vertexFormat, boolean depthTest,
                                             boolean depthWrite, boolean cull, boolean blend) {
        VulkairisShader vert = program.getVertexShader();
        VulkairisShader frag = program.getFragmentShader();
        if (vert == null || frag == null) {
            throw new IllegalArgumentException("Program '" + program.getName() + "' is missing vertex or fragment shader");
        }

        // Make the shaders resolvable from the module-handle-only PipelineParams so the
        // interface validation below runs for program-driven pipeline creation.
        registerShaderPair(vert, frag);

        VkDevice device = VulkanBackendAdapter.getDevice();
        long cache = PipelineManager.getOrCreatePipelineCache();

        PipelineParams params = new PipelineParams(
                vert.getVkShaderModule(),
                frag.getVkShaderModule(),
                program.getPipelineLayout(),
                renderPass,
                vertexFormat,
                depthTest,
                depthWrite,
                cull,
                blend,
                cache
        );

        long pipeline = createGraphicsPipeline(device, params);
        PipelineKey key = buildPipelineKey(params);
        program.cachePipeline(key, pipeline);
        return pipeline;
    }

    public static void destroyPipeline(VkDevice device, long pipeline) {
        if (pipeline != 0L && VulkanBackendAdapter.isLive(device)) {
            try {
                VK10.vkDestroyPipeline(device, pipeline, null);
                LOGGER.info("[Vulkairis] Destroyed VkPipeline 0x{}", Long.toHexString(pipeline));
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis] Error destroying VkPipeline 0x{}: {}", Long.toHexString(pipeline), t.getMessage());
            }
        }
    }
}
