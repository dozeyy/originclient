package com.vulkairis.rendering.backend.vulkanmod.v1211;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: Verified hook table for Minecraft 1.21.1 + VulkanMod 0.6.7 + Iris 1.8.14.
 * Defines exact owners, methods, descriptors, and expected timeline order.
 */
public final class RenderLifecycleTable {

    private static final Map<VulkanModRenderStage, RenderHookDescriptor> HOOKS = new EnumMap<>(VulkanModRenderStage.class);
    private static final List<RenderHookDescriptor> ORDERED_HOOKS = new ArrayList<>();

    static {
        register(new RenderHookDescriptor(
                VulkanModRenderStage.FRAME_BEGIN,
                "net.vulkanmod.vulkan.Renderer",
                "beginFrame",
                "()V",
                "HEAD",
                "None",
                "SwapChain",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.WORLD_RENDER_HEAD,
                "net.minecraft.client.renderer.LevelRenderer",
                "renderLevel",
                "(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                "HEAD",
                "MainPass",
                "MainTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.SHADOW_PASS,
                "net.irisshaders.iris.pipeline.IrisRenderingPipeline",
                "renderShadows",
                "(Lnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/Camera;)V",
                "HEAD",
                "ShadowPass",
                "ShadowTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z (clear 1.0)",
                false
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.CHUNK_SUBMISSION,
                "net.vulkanmod.render.chunk.WorldRenderer",
                "uploadSections",
                "()V",
                "HEAD",
                "MainPass",
                "MainTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.OPAQUE_TERRAIN,
                "net.vulkanmod.render.chunk.WorldRenderer",
                "renderSectionLayer",
                "(Lnet/vulkanmod/render/vertex/TerrainRenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                "HEAD",
                "MainPass",
                "MainTarget [SOLID]",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z (depth test/write LESS)",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.CUTOUT_TERRAIN,
                "net.vulkanmod.render.chunk.WorldRenderer",
                "renderSectionLayer",
                "(Lnet/vulkanmod/render/vertex/TerrainRenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                "HEAD",
                "MainPass",
                "MainTarget [CUTOUT_MIPPED, CUTOUT]",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z (depth test/write LESS)",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.ENTITIES,
                "net.vulkanmod.render.chunk.WorldRenderer",
                "renderBlockEntities",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;DDDLit/unimi/dsi/fastutil/longs/Long2ObjectMap;F)V",
                "HEAD",
                "MainPass",
                "MainTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.SKY_CLOUDS_WEATHER,
                "net.vulkanmod.render.sky.CloudRenderer",
                "renderClouds",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
                "HEAD",
                "MainPass",
                "MainTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                false
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.PARTICLES,
                "net.minecraft.client.particle.ParticleEngine",
                "render",
                "(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
                "HEAD",
                "MainPass",
                "MainTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                false
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.TRANSLUCENT_TERRAIN,
                "net.vulkanmod.render.chunk.WorldRenderer",
                "renderSectionLayer",
                "(Lnet/vulkanmod/render/vertex/TerrainRenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                "HEAD",
                "MainPass",
                "MainTarget [TRANSLUCENT]",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z (blend SRC_ALPHA, ONE_MINUS_SRC_ALPHA)",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.FIRST_PERSON_HAND,
                "net.minecraft.client.renderer.ItemInHandRenderer",
                "renderHandsWithItems",
                "(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
                "HEAD",
                "MainPass",
                "MainTarget",
                "Renderer.getCommandBuffer()",
                "Vulkan [0, 1] forward-Z",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.COMPOSITE_POST,
                "net.vulkanmod.vulkan.Renderer",
                "endRenderPass",
                "(Lorg/lwjgl/vulkan/VkCommandBuffer;)V",
                "HEAD",
                "PostPass",
                "ColorTargets / colortexN",
                "Renderer.getCommandBuffer()",
                "Fullscreen triangle / quad",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.FINAL_PASS,
                "net.vulkanmod.vulkan.pass.MainPass",
                "end",
                "(Lorg/lwjgl/vulkan/VkCommandBuffer;)V",
                "HEAD",
                "FinalPass",
                "SwapChain / MainTarget",
                "Renderer.getCommandBuffer()",
                "Fullscreen triangle / quad",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.GUI,
                "net.minecraft.client.gui.Gui",
                "render",
                "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                "HEAD",
                "GuiPass",
                "SwapChain",
                "Renderer.getCommandBuffer()",
                "Ortho projection, depth disabled",
                true
        ));

        register(new RenderHookDescriptor(
                VulkanModRenderStage.FRAME_END_PRESENT,
                "net.vulkanmod.vulkan.Renderer",
                "endFrame",
                "()V",
                "TAIL",
                "None",
                "SwapChain",
                "Renderer.getCommandBuffer()",
                "None",
                true
        ));
    }

    private static void register(RenderHookDescriptor descriptor) {
        HOOKS.put(descriptor.stage(), descriptor);
        ORDERED_HOOKS.add(descriptor);
    }

    private RenderLifecycleTable() {
    }

    public static RenderHookDescriptor get(VulkanModRenderStage stage) {
        return HOOKS.get(stage);
    }

    public static List<RenderHookDescriptor> getAllOrdered() {
        return Collections.unmodifiableList(ORDERED_HOOKS);
    }

    public static Map<VulkanModRenderStage, RenderHookDescriptor> getHookMap() {
        return Collections.unmodifiableMap(HOOKS);
    }
}
