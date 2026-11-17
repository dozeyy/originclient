package com.vulkairis.translation;

import org.lwjgl.util.shaderc.Shaderc;

/**
 * The tested shaderc compilation profile for VulkanMod 0.6.7 on Minecraft 1.21.1 (Phase 4).
 *
 * <p>Target: Vulkan 1.1 (SPIR-V 1.3), the widest device baseline for the 1.21-era hardware
 * VulkanMod supports. A newer target would buy newer SPIR-V features at the cost of minimum-spec
 * devices; that trade is deferred until a device-capability gate exists.
 *
 * <p>Options, reviewed individually:
 * <ul>
 *   <li>{@code invert_y}: true. The single Y-flip in the chain (see the Phase 9 transform
 *       ledger); the translator and matrices must not flip again.</li>
 *   <li>{@code auto_map_locations}: true. The Shaderc-path translator does not inject
 *       locations itself; shaderc fills unlocated interface variables. Cross-stage agreement is
 *       <em>not</em> assumed: {@code PipelineCompatibilityValidator} verifies reflected
 *       locations/types before any pipeline is created.</li>
 *   <li>{@code auto_bind_uniforms}: true, same contract: shaderc assigns bindings for bare
 *       descriptors on the Shaderc path, and reflection plus link validation reconcile them.
 *       (The VulkanMod path instead injects explicit dense bindings up front.)</li>
 *   <li>{@code auto_combined_image_sampler}: true. Kept deliberately: the descriptor model
 *       treats sampled images as combined image samplers end to end.</li>
 * </ul>
 *
 * <p>One compiler/options pair is created per compilation (correctness over reuse): LWJGL
 * shaderc options objects are not documented as safe to share across threads, so no instance
 * or options caching is attempted until profiling demands it.
 */
public record ShadercProfile(
        int targetEnv,
        int targetVersion,
        boolean invertY,
        boolean autoMapLocations,
        boolean autoBindUniforms,
        boolean autoCombinedImageSampler) {

    /** The single tested profile. */
    public static final ShadercProfile DEFAULT = new ShadercProfile(
            Shaderc.shaderc_target_env_vulkan,
            Shaderc.shaderc_env_version_vulkan_1_1,
            true, true, true, true);

    /** @return a short stable digest identifying the exact profile that produced output */
    public String digest() {
        return "vulkan1.1-SPIRV1.3"
                + (invertY ? "+invY" : "+noInvY")
                + (autoMapLocations ? "+autoLoc" : "+noAutoLoc")
                + (autoBindUniforms ? "+autoBind" : "+noAutoBind")
                + (autoCombinedImageSampler ? "+autoSampler" : "+noAutoSampler");
    }

    /**
     * Applies this profile to freshly initialized shaderc options.
     *
     * @param options options handle from {@code shaderc_compile_options_initialize()}
     */
    public void configure(long options) {
        Shaderc.shaderc_compile_options_set_target_env(options, targetEnv, targetVersion);
        Shaderc.shaderc_compile_options_set_invert_y(options, invertY);
        Shaderc.shaderc_compile_options_set_auto_map_locations(options, autoMapLocations);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, autoBindUniforms);
        Shaderc.shaderc_compile_options_set_auto_combined_image_sampler(
                options, autoCombinedImageSampler);
    }
}
