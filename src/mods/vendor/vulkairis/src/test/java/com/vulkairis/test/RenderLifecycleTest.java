package com.vulkairis.test;

import com.vulkairis.rendering.backend.vulkanmod.v1211.RenderHookDescriptor;
import com.vulkairis.rendering.backend.vulkanmod.v1211.RenderLifecycleTable;
import com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter;
import com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 2: Unit tests for render lifecycle stage mapping, hook table, and ordering validator.
 */
public class RenderLifecycleTest {

    private VulkanModLifecycleAdapter adapter;

    @BeforeEach
    public void setUp() {
        adapter = VulkanModLifecycleAdapter.getInstance();
        adapter.clearViolations();
        adapter.setTraceOnlyMode(true);
    }

    @Test
    public void testStageOrderValuesAndIndices() {
        VulkanModRenderStage[] stages = VulkanModRenderStage.values();
        Assertions.assertEquals(15, stages.length, "Must define exactly 15 lifecycle stages for MC 1.21.1");

        for (int i = 0; i < stages.length; i++) {
            Assertions.assertEquals(i, stages[i].orderIndex(), "Stage " + stages[i] + " order index must match enum ordinal");
            Assertions.assertNotNull(stages[i].description());
            Assertions.assertFalse(stages[i].description().isEmpty());
        }
    }

    @Test
    public void testRenderLifecycleTableRegistration() {
        List<RenderHookDescriptor> ordered = RenderLifecycleTable.getAllOrdered();
        Assertions.assertEquals(15, ordered.size(), "Table must register all 15 stages");

        for (VulkanModRenderStage stage : VulkanModRenderStage.values()) {
            RenderHookDescriptor desc = RenderLifecycleTable.get(stage);
            Assertions.assertNotNull(desc, "Stage " + stage + " must have a registered hook descriptor");
            Assertions.assertEquals(stage, desc.stage());
            Assertions.assertFalse(desc.targetOwner().isEmpty(), "Owner cannot be empty for " + stage);
            Assertions.assertFalse(desc.methodName().isEmpty(), "Method cannot be empty for " + stage);
            Assertions.assertFalse(desc.methodDescriptor().isEmpty(), "Descriptor cannot be empty for " + stage);
        }

        // Verify key anchors
        RenderHookDescriptor frameBegin = RenderLifecycleTable.get(VulkanModRenderStage.FRAME_BEGIN);
        Assertions.assertEquals("net.vulkanmod.vulkan.Renderer", frameBegin.targetOwner());
        Assertions.assertEquals("beginFrame", frameBegin.methodName());
        Assertions.assertTrue(frameBegin.critical());

        RenderHookDescriptor levelRender = RenderLifecycleTable.get(VulkanModRenderStage.WORLD_RENDER_HEAD);
        Assertions.assertEquals("net.minecraft.client.renderer.LevelRenderer", levelRender.targetOwner());
        Assertions.assertEquals("renderLevel", levelRender.methodName());
        Assertions.assertTrue(levelRender.critical());

        RenderHookDescriptor opaqueTerrain = RenderLifecycleTable.get(VulkanModRenderStage.OPAQUE_TERRAIN);
        Assertions.assertEquals("net.vulkanmod.render.chunk.WorldRenderer", opaqueTerrain.targetOwner());
        Assertions.assertEquals("renderSectionLayer", opaqueTerrain.methodName());

        RenderHookDescriptor composite = RenderLifecycleTable.get(VulkanModRenderStage.COMPOSITE_POST);
        Assertions.assertEquals("net.vulkanmod.vulkan.Renderer", composite.targetOwner());
        Assertions.assertEquals("endRenderPass", composite.methodName());

        RenderHookDescriptor frameEnd = RenderLifecycleTable.get(VulkanModRenderStage.FRAME_END_PRESENT);
        Assertions.assertEquals("net.vulkanmod.vulkan.Renderer", frameEnd.targetOwner());
        Assertions.assertEquals("endFrame", frameEnd.methodName());
        Assertions.assertEquals("TAIL", frameEnd.injectionPoint());
    }

    @Test
    public void testVulkanModLifecycleAdapterValidOrder() {
        adapter.onFrameBegin(100L);
        adapter.onStageObserved(VulkanModRenderStage.WORLD_RENDER_HEAD, "Camera setup");
        adapter.onStageObserved(VulkanModRenderStage.CHUNK_SUBMISSION, "Section upload");
        adapter.onStageObserved(VulkanModRenderStage.OPAQUE_TERRAIN, "Solid pass");
        adapter.onStageObserved(VulkanModRenderStage.COMPOSITE_POST, "Composite pass");
        adapter.onStageObserved(VulkanModRenderStage.GUI, "HUD render");
        adapter.onFrameEnd();

        Assertions.assertTrue(adapter.getOrderingViolations().isEmpty(),
                "Legal non-decreasing order must yield 0 violations: " + adapter.getOrderingViolations());

        List<VulkanModLifecycleAdapter.StageObservation> history = adapter.getTraceHistory(10);
        Assertions.assertTrue(history.size() >= 7);
        // Newest stage is FRAME_END_PRESENT
        Assertions.assertEquals(VulkanModRenderStage.FRAME_END_PRESENT, history.get(0).stage());
        Assertions.assertTrue(history.get(0).orderValid());
    }

    @Test
    public void testVulkanModLifecycleAdapterDetectsViolation() {
        adapter.onFrameBegin(200L);
        adapter.onStageObserved(VulkanModRenderStage.COMPOSITE_POST, "Composite pass");
        // Out of order: OPAQUE_TERRAIN (order 4) after COMPOSITE_POST (order 11)
        adapter.onStageObserved(VulkanModRenderStage.OPAQUE_TERRAIN, "Late terrain attempt");

        List<String> violations = adapter.getOrderingViolations();
        Assertions.assertEquals(1, violations.size(), "Must detect exactly 1 order violation");
        Assertions.assertTrue(violations.get(0).contains("Order violation on frame 200"));
        Assertions.assertTrue(violations.get(0).contains("OPAQUE_TERRAIN"));

        List<VulkanModLifecycleAdapter.StageObservation> history = adapter.getTraceHistory(2);
        Assertions.assertFalse(history.get(0).orderValid(), "Violating stage observation must have orderValid = false");
        Assertions.assertEquals(VulkanModRenderStage.OPAQUE_TERRAIN, history.get(0).stage());
    }

    @Test
    public void testVulkanModLifecycleAdapterTraceHistoryBounds() {
        adapter.onFrameBegin(300L);
        for (int i = 0; i < 150; i++) {
            adapter.onStageObserved(VulkanModRenderStage.ENTITIES, "Entity " + i);
        }
        adapter.onFrameEnd();

        List<VulkanModLifecycleAdapter.StageObservation> history = adapter.getTraceHistory(200);
        Assertions.assertEquals(128, history.size(), "Trace history must be bounded at MAX_TRACE (128)");
    }

    @Test
    public void testTraceOnlyModeDefault() {
        Assertions.assertTrue(adapter.isTraceOnlyMode(), "Adapter must default to trace-only mode");
    }
}
