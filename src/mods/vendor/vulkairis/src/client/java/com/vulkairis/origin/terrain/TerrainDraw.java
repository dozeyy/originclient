package com.vulkairis.origin.terrain;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.origin.composite.IrisPassSource;
import com.vulkairis.origin.composite.PackTextures;
import com.vulkairis.origin.shadow.ShadowMap;
import com.vulkairis.origin.shadow.ShadowPass;
import com.vulkairis.origin.uniforms.IrisUniformValues;
import com.vulkairis.translation.LegacyGlsl;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Origin (step 5): what a pack terrain pipeline needs while VulkanMod draws chunks with it.
 *
 * <p>VulkanMod's chunk loop ({@code WorldRenderer.renderSectionLayer}) binds the pipeline its shader
 * getter returns, binds textures by image index ({@code VTextureSelector.bindShaderTextures}), then
 * uploads UBOs and pushes {@code ModelOffset} per chunk area. A pack pipeline has its own samplers and
 * Vulkairis's uniform block, so: {@link #prepare} fills the block with Iris's values and the terrain
 * matrices once per bind, and {@link #bindTextures} (called right after VulkanMod's own texture bind)
 * gives every sampler the image it means by NAME. The pipeline draws into VulkanMod's main colour
 * target, which the composite chain takes as the scene buffer (its location 0 is the gbuffers'
 * first draw buffer).</p>
 */
public final class TerrainDraw {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Terrain");
    private static final int ATLAS_UNIT = 0;
    private static final int LIGHTMAP_UNIT = 2;
    private static final double LIGHTMAP_SCALE = 1.0 / 256.0;
    private static final double LIGHTMAP_OFFSET = 8.0 / 256.0;

    private static final Set<Pipeline> PACK_PIPELINES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, double[]> OVERRIDES = new HashMap<>();
    private static final Set<String> ANNOUNCED = new java.util.HashSet<>();

    private TerrainDraw() {
    }

    /** Called with the pack pipeline the terrain getter is about to hand VulkanMod. */
    public static void prepare(GraphicsPipeline gp) {
        PACK_PIPELINES.add(gp);
        Map<String, double[]> values = overrides();
        VulkanModProgramBridge.uploadUniforms(gp, values);
        if (ANNOUNCED.add(gp.name)) {
            LOGGER.info("[Vulkairis/Terrain] pack terrain pipeline in use: {} (uniforms nothing supplies, read as 0: {})",
                    gp.name, VulkanModProgramBridge.unsuppliedUniforms(gp, values));
        }
    }

    public static boolean isPackPipeline(Pipeline pipeline) {
        return PACK_PIPELINES.contains(pipeline);
    }

    /** Forgets pipelines on a pack reload (they are destroyed with the pack). */
    public static void reset() {
        PACK_PIPELINES.clear();
        ANNOUNCED.clear();
    }

    /** Rebinds every sampler of a pack terrain pipeline by name (after VulkanMod bound them by index). */
    public static void bindTextures(Pipeline pipeline) {
        ProgramSet set = IrisPassSource.currentSet();
        VulkanImage white = VTextureSelector.getWhiteTexture();
        for (ImageDescriptor d : pipeline.getImageDescriptors()) {
            int idx = d.getImageIdx();
            if (idx < 0 || idx >= VTextureSelector.SIZE) {
                continue;
            }
            // Origin (step 6): the shadow map Vulkairis draws (compare wrapper for sampler2DShadow).
            VulkanImage shadow = ShadowMap.forSampler(d.name, VulkanModProgramBridge.isCompareSampler(pipeline, d.name));
            if (shadow != null) {
                VTextureSelector.bindTexture(idx, shadow);
                continue;
            }
            VulkanImage img = switch (d.name) {
                case "gtexture", "texture", "tex" -> VRenderSystem.getShaderTexture(ATLAS_UNIT);
                case "lightmap" -> VRenderSystem.getShaderTexture(LIGHTMAP_UNIT);
                default -> set == null ? null : PackTextures.cached(set, d.name);
            };
            VTextureSelector.bindTexture(idx, img != null ? img : white);
        }
    }

    /**
     * The fixed-function matrices for a chunk draw. VulkanMod's chunk vertices are camera-relative
     * (TerrainVertexAdapter adds ModelOffset), so the model-view is Iris's gbufferModelView (the camera
     * rotation) and the projection Iris's OpenGL-depth gbufferProjection. Texture unit 1 is Iris's
     * lightmap matrix (coordinate / 256 + 8 / 256).
     */
    private static Map<String, double[]> overrides() {
        // Inside the shadow pass the fixed-function matrices are the sun's (Iris does the same).
        boolean shadowPass = ShadowPass.isActive();
        double[] mv = shadowPass ? ShadowPass.modelView() : IrisUniformValues.lookup("gbufferModelView");
        double[] proj = shadowPass ? ShadowPass.projection() : IrisUniformValues.lookup("gbufferProjection");
        OVERRIDES.clear();
        if (mv == null || mv.length < 16 || proj == null || proj.length < 16) {
            return OVERRIDES;
        }
        double[] mvp = new double[16];
        new Matrix4d().set(proj).mul(new Matrix4d().set(mv)).get(mvp);
        double[] normal = new double[16];
        new Matrix4d().set(mv).invert().transpose().get(normal);

        int units = LegacyGlsl.TEXTURE_MATRIX_UNITS;
        double[] textures = new double[16 * units];
        for (int u = 0; u < units; u++) {
            textures[u * 16] = textures[u * 16 + 5] = textures[u * 16 + 10] = textures[u * 16 + 15] = 1.0;
        }
        if (units > 1) {
            int b = 16;
            textures[b] = textures[b + 5] = textures[b + 10] = LIGHTMAP_SCALE;
            textures[b + 12] = textures[b + 13] = textures[b + 14] = LIGHTMAP_OFFSET;
        }
        OVERRIDES.put("vulkairis_ModelViewMatrix", mv);
        OVERRIDES.put("vulkairis_ProjectionMatrix", proj);
        OVERRIDES.put("vulkairis_ModelViewProjectionMatrix", mvp);
        OVERRIDES.put("vulkairis_NormalMatrix", normal);
        OVERRIDES.put("vulkairis_TextureMatrix", textures);
        OVERRIDES.put("iris_ModelViewMat", mv);
        OVERRIDES.put("iris_ProjMat", proj);
        OVERRIDES.put("iris_NormalMat", normal);
        return OVERRIDES;
    }
}
