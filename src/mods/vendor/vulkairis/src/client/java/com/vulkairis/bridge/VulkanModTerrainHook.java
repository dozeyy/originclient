package com.vulkairis.bridge;

import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.lang.reflect.Field;
import java.util.function.Function;

/**
 * Hook for VulkanMod's terrain renderer to resolve pack-provided pipelines.
 *
 * <p>Installs a {@link Function} into {@code net.vulkanmod.render.shader.PipelineManager}
 * via {@link #bind()} so that every terrain draw asks Vulkairis for the active pack's
 * pipeline first, falling back to VulkanMod's own default terrain shader when no pack is
 * active or no terrain pipeline is linked.</p>
 */
public final class VulkanModTerrainHook {

    private static volatile boolean bound = false;
    private static volatile GraphicsPipeline defaultTerrainShader = null;

    private VulkanModTerrainHook() {
    }

    /** @return {@code true} once {@link #bind()} has successfully installed the getter. */
    public static boolean isBound() {
        return bound;
    }

    /**
     * Maps a VulkanMod terrain render type to the pack program name that should back it.
     * Mirrors {@code TerrainPipelineDoor.programNameFor} with the cutout/translucent fallback
     * chain: a missing cutout/translucent program falls back to {@code gbuffers_terrain}.
     */
    public static String programNameFor(TerrainRenderType type) {
        if (type == null) {
            return "gbuffers_terrain";
        }
        return switch (type) {
            case SOLID, CUTOUT_MIPPED, TRIPWIRE -> "gbuffers_terrain";
            case CUTOUT -> "gbuffers_terrain_cutout";
            case TRANSLUCENT -> "gbuffers_translucent";
        };
    }

    /**
     * Resolves the pack pipeline for a terrain render type, applying the fallback chain
     * (cutout/translucent -> terrain) when the specific program is not linked.
     */
    public static GraphicsPipeline getPackPipeline(TerrainRenderType type) {
        String program = programNameFor(type);
        int id = VulkanModProgramBridge.terrainLinkedId(program);
        if (id <= 0 && !"gbuffers_terrain".equals(program)) {
            id = VulkanModProgramBridge.terrainLinkedId("gbuffers_terrain");
        }
        return id > 0 ? VulkanModProgramBridge.getLinkedPipeline(id) : null;
    }

    private static final Function<TerrainRenderType, GraphicsPipeline> SHADER_GETTER = type -> {
        try {
            GraphicsPipeline pack = getPackPipeline(type);
            if (pack != null) {
                return pack;
            }
        } catch (Throwable ignored) {
        }
        // Fall back to VulkanMod's own default terrain shader.
        try {
            GraphicsPipeline cached = defaultTerrainShader;
            if (cached == null) {
                cached = readDefaultTerrainShader();
                if (cached != null) {
                    defaultTerrainShader = cached;
                }
            }
            if (cached != null) {
                return cached;
            }
        } catch (Throwable ignored) {
        }
        return null;
    };

    private static GraphicsPipeline readDefaultTerrainShader() {
        try {
            Class<?> pm = Class.forName("net.vulkanmod.render.shader.PipelineManager");
            Field field = pm.getDeclaredField("terrainShader");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof GraphicsPipeline gp) {
                return gp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Installs the pack-aware getter into VulkanMod. Idempotent and never throws.
     *
     * @return {@code true} when the getter was installed
     */
    public static synchronized boolean bind() {
        try {
            Class<?> pm = Class.forName("net.vulkanmod.render.shader.PipelineManager");
            java.lang.reflect.Method setter = pm.getMethod("setShaderGetter", Function.class);
            setter.invoke(null, SHADER_GETTER);
            bound = true;
            return true;
        } catch (Throwable t) {
            bound = false;
            return false;
        }
    }

    /**
     * Restores VulkanMod's default terrain getter. Idempotent and never throws.
     */
    public static synchronized void unbind() {
        try {
            Class<?> pm = Class.forName("net.vulkanmod.render.shader.PipelineManager");
            java.lang.reflect.Method setter = pm.getMethod("setDefaultTerrainShaderGetter");
            setter.invoke(null);
        } catch (Throwable ignored) {
        }
        bound = false;
    }
}