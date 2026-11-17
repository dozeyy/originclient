package com.vulkairis.origin.shadow;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.origin.composite.IrisPassSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Origin (step 6): Iris's shadow pass, on VulkanMod's chunk renderer.
 *
 * <p>Iris's own ShadowRenderer never runs under VulkanMod (it drives Sodium or vanilla chunk
 * rendering). Instead, right before VulkanMod draws the solid terrain layer, this ends the main pass,
 * clears the {@link ShadowMap} and draws the opaque layers again with the pack's {@code shadow}
 * program from the sun's point of view (Iris's own shadow matrices), then hands the frame back.
 * {@code WorldRendererShadowMixin} keeps VulkanMod from rebinding the main target inside those
 * nested draws.</p>
 *
 * <p>Limits (first version): casters are the chunk sections VulkanMod already culled for the camera,
 * so terrain behind the camera casts no shadow; no entities, block entities or translucent casters;
 * {@code shadowtex1} equals {@code shadowtex0}.</p>
 */
public final class ShadowPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Shadow");
    private static final TerrainRenderType[] CASTERS = {
            TerrainRenderType.SOLID, TerrainRenderType.CUTOUT_MIPPED, TerrainRenderType.CUTOUT};

    private static boolean active;
    private static boolean disabled;
    private static GraphicsPipeline pipeline;
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean matricesValid;
    private static final double[] MV = new double[16];
    private static final double[] PROJ = new double[16];
    private static final double[] MV_INV = new double[16];
    private static final double[] PROJ_INV = new double[16];
    private static boolean announced;
    private static ProgramSet lastSet;

    private ShadowPass() {
    }

    public static boolean isActive() {
        return active;
    }

    /** The pack's shadow program while the pass draws, else null. */
    public static GraphicsPipeline activePipeline() {
        return active ? pipeline : null;
    }

    public static double[] modelView() {
        return MV;
    }

    public static double[] projection() {
        return PROJ;
    }

    /**
     * Iris's shadow matrix uniforms, computed here because Iris's ShadowRenderer (their usual source)
     * never runs: null for any other name, or before the first pass.
     */
    public static double[] uniform(String name) {
        if (!matricesValid) {
            return null;
        }
        return switch (name) {
            case "shadowModelView" -> MV;
            case "shadowProjection" -> PROJ;
            case "shadowModelViewInverse" -> MV_INV;
            case "shadowProjectionInverse" -> PROJ_INV;
            default -> null;
        };
    }

    /** Called at the head of every {@code WorldRenderer.renderSectionLayer}; runs once per frame (SOLID). */
    public static void beforeLayer(WorldRenderer wr, TerrainRenderType type, double camX, double camY, double camZ) {
        if (active || type != TerrainRenderType.SOLID) {
            return;
        }
        ProgramSet set = IrisPassSource.currentSet();
        if (set == null) {
            return;
        }
        if (set != lastSet) {
            // A new pack (or a reload) gets a fresh try and a fresh log line.
            lastSet = set;
            disabled = false;
            announced = false;
        }
        if (disabled) {
            return;
        }
        Renderer renderer = Renderer.getInstance();
        if (renderer.getBoundRenderPass() == null) {
            return;
        }
        try {
            PackDirectives directives = set.getPackDirectives();
            PackShadowDirectives shadow = directives.getShadowDirectives();
            computeMatrices(directives, shadow);
            int id = VulkanModProgramBridge.terrainLinkedId("shadow");
            pipeline = id > 0 ? VulkanModProgramBridge.getLinkedPipeline(id) : null;

            VkCommandBuffer cmd = Renderer.getCommandBuffer();
            MainPass mainPass = renderer.getMainPass();
            renderer.endRenderPass(cmd);
            ShadowMap.ensure(shadow.getResolution());
            ShadowMap.beginAndClear(cmd, renderer);
            active = true;
            try {
                if (pipeline != null) {
                    Matrix4f mv = new Matrix4f(MODEL_VIEW);
                    Matrix4f proj = new Matrix4f(PROJECTION);
                    for (TerrainRenderType caster : CASTERS) {
                        wr.renderSectionLayer(caster, camX, camY, camZ, mv, proj);
                    }
                }
                // Origin (step 9): geometry other mods draw themselves (Flywheel instances) casts too.
                com.vulkairis.origin.api.ForeignPackPrograms.drawShadowCasters();
            } finally {
                active = false;
            }
            ShadowMap.end(cmd, renderer);
            mainPass.rebindMainTarget();
            Renderer.resetViewport();
            Renderer.resetScissor();
            if (!announced) {
                announced = true;
                LOGGER.info("[Vulkairis/Shadow] shadow pass on: {}x{} map, distance {}, program {}",
                        ShadowMap.size(), ShadowMap.size(), shadow.getDistance(),
                        pipeline != null ? pipeline.name : "none (map cleared only)");
            }
        } catch (Throwable t) {
            active = false;
            disabled = true;
            LOGGER.error("[Vulkairis/Shadow] shadow pass failed; shadows off for this session: {}", t.toString(), t);
            try {
                Renderer.getInstance().getMainPass().rebindMainTarget();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Pack change: forget the program and the map (it is recreated at the new pack's resolution). */
    public static void reset() {
        pipeline = null;
        lastSet = null;
        matricesValid = false;
        disabled = false;
        announced = false;
        ShadowMap.release();
    }

    private static void computeMatrices(PackDirectives directives, PackShadowDirectives shadow) {
        float near = shadow.getNearPlane();
        float far = shadow.getFarPlane();
        MODEL_VIEW.set(ShadowRenderer.createShadowModelView(
                directives.getSunPathRotation(), shadow.getIntervalSize(), near, far).last().pose());
        Float fov = shadow.getFov();
        if (fov != null) {
            PROJECTION.set(ShadowMatrices.createPerspectiveMatrix(fov));
        } else {
            // Iris's own defaults: -1 near/far plane means "render distance either side".
            float reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
            PROJECTION.set(ShadowMatrices.createOrthoMatrix(shadow.getDistance(),
                    near == -1f ? -reach : near, far == -1f ? reach : far));
        }
        copy(MODEL_VIEW, MV);
        copy(PROJECTION, PROJ);
        copy(new Matrix4f(MODEL_VIEW).invert(), MV_INV);
        copy(new Matrix4f(PROJECTION).invert(), PROJ_INV);
        matricesValid = true;
    }

    private static void copy(Matrix4f m, double[] out) {
        float[] f = m.get(new float[16]);
        for (int i = 0; i < 16; i++) {
            out[i] = f[i];
        }
    }
}
