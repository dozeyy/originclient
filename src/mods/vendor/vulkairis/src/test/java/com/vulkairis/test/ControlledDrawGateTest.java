package com.vulkairis.test;

import com.vulkairis.core.DebugRenderMode;
import com.vulkairis.vulkan.test.StandaloneVulkanTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure world-only gate that keeps the controlled debug quad off the menu
 * (implementation plan phase 1) and for the phase 6/9 diagnostic visualizer modes.
 */
public class ControlledDrawGateTest {

    @Test
    public void testNonControlledModeIsRejected() {
        // Mode OFF with a fully valid world frame must still draw nothing.
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.OFF, true, true, false, true, true),
                "OFF must never draw the controlled quad");
    }

    @Test
    public void testNormalModeIsRejectedEvenInWorld() {
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.NORMAL, true, true, false, true, true),
                "NORMAL is the integration path, not a controlled draw");
    }

    @Test
    public void testControlledModeAllowedInWorldMainPass() {
        Assertions.assertTrue(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.TRIANGLE, true, true, false, true, true),
                "TRIANGLE in a real world main pass must draw");
    }

    @Test
    public void testOpenScreenRejected() {
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.TRIANGLE, true, true, true, true, true),
                "An open GUI screen must suppress the controlled quad");
    }

    @Test
    public void testNonMainPassRejected() {
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.TRIANGLE, true, true, false, false, true),
                "A non-main framebuffer must suppress the controlled quad");
    }

    @Test
    public void testZeroPassIdRejected() {
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.TRIANGLE, true, true, false, true, false),
                "A zero render-pass id must suppress the controlled quad");
    }

    @Test
    public void testNoLevelRejected() {
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.TRIANGLE, false, true, false, true, true),
                "No loaded level must suppress the controlled quad");
    }

    @Test
    public void testNoPlayerRejected() {
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.TRIANGLE, true, false, false, true, true),
                "No player must suppress the controlled quad");
    }

    @Test
    public void testVisualizerModesParseAndAreControlled() {
        Assertions.assertEquals(DebugRenderMode.FLAT, DebugRenderMode.fromString("flat"));
        Assertions.assertEquals(DebugRenderMode.UV, DebugRenderMode.fromString("uv"));
        Assertions.assertEquals(DebugRenderMode.LIGHTMAP, DebugRenderMode.fromString("lightmap"));
        Assertions.assertEquals(DebugRenderMode.DEPTH, DebugRenderMode.fromString("depth"));
        Assertions.assertEquals(DebugRenderMode.NO_POST, DebugRenderMode.fromString("no-post"));
        Assertions.assertEquals(DebugRenderMode.NO_POST, DebugRenderMode.fromString("nopost"));
        Assertions.assertEquals(DebugRenderMode.NO_POST, DebugRenderMode.fromString("NO_POST"));

        Assertions.assertTrue(DebugRenderMode.FLAT.isControlled());
        Assertions.assertTrue(DebugRenderMode.UV.isControlled());
        Assertions.assertTrue(DebugRenderMode.LIGHTMAP.isControlled());
        Assertions.assertTrue(DebugRenderMode.DEPTH.isControlled());
        Assertions.assertTrue(DebugRenderMode.NO_POST.isControlled());
    }

    @Test
    public void testVisualizerModesShareWorldGateAndUnknownFailsSafe() {
        Assertions.assertTrue(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.FLAT, true, true, false, true, true),
                "Visualizer modes go through the same world-only gate");
        Assertions.assertFalse(StandaloneVulkanTest.shouldDrawControlled(
                        DebugRenderMode.DEPTH, true, true, true, true, true),
                "Visualizer modes must also be suppressed while a screen is open");

        Assertions.assertEquals(DebugRenderMode.OFF, DebugRenderMode.fromString("definitely-not-a-mode"),
                "Unknown mode strings must fail safe to OFF");
        Assertions.assertEquals(DebugRenderMode.OFF, DebugRenderMode.fromString(null),
                "A null mode string must fail safe to OFF");
    }
}
