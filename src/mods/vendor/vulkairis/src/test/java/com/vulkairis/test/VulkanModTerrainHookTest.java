package com.vulkairis.test;

import com.vulkairis.bridge.VulkanModTerrainHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VulkanModTerrainHookTest {
    @AfterEach
    public void cleanup() {
        VulkanModTerrainHook.unbind();
    }

    @Test
    public void testProgramNameForSolid() {
        Assertions.assertEquals("gbuffers_terrain", VulkanModTerrainHook.programNameFor(null));
    }

    @Test
    public void testBindDoesNotThrow() {
        Assertions.assertDoesNotThrow(() -> VulkanModTerrainHook.bind());
    }

    @Test
    public void testUnbindResetsBound() {
        VulkanModTerrainHook.bind();
        VulkanModTerrainHook.unbind();
        Assertions.assertFalse(VulkanModTerrainHook.isBound());
    }
}