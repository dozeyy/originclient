package com.vulkairis.rendering.backend.vulkanmod.v1211;

/**
 * Phase 2: Ordered stages of the Minecraft 1.21.1 + VulkanMod render lifecycle.
 */
public enum VulkanModRenderStage {
    FRAME_BEGIN(0, "Frame begin; acquisition of swapchain image"),
    WORLD_RENDER_HEAD(1, "World render head; camera, view matrices setup"),
    SHADOW_PASS(2, "Iris shadow pass execution"),
    CHUNK_SUBMISSION(3, "VulkanMod section upload and indirect buffer submission"),
    OPAQUE_TERRAIN(4, "Opaque (Solid) terrain rasterization"),
    CUTOUT_TERRAIN(5, "Cutout-mipped and cutout terrain rasterization"),
    ENTITIES(6, "Living entities and block entities"),
    SKY_CLOUDS_WEATHER(7, "Sky dome, stars, sun/moon, clouds, weather"),
    PARTICLES(8, "World particles"),
    TRANSLUCENT_TERRAIN(9, "Translucent terrain, water, and tripwire"),
    FIRST_PERSON_HAND(10, "First-person arm, held items"),
    COMPOSITE_POST(11, "Deferred and composite post-processing chain"),
    FINAL_PASS(12, "Final presentation shader pass"),
    GUI(13, "In-game HUD, menus, and text overlays"),
    FRAME_END_PRESENT(14, "Render pass close, command buffer submission, present");

    private final int orderIndex;
    private final String description;

    VulkanModRenderStage(int orderIndex, String description) {
        this.orderIndex = orderIndex;
        this.description = description;
    }

    public int orderIndex() {
        return orderIndex;
    }

    public String description() {
        return description;
    }
}
