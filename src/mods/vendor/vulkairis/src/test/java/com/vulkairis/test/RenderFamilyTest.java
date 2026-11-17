package com.vulkairis.test;

import com.vulkairis.rendering.RenderFamily;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RenderFamilyTest {

    @Test
    public void registryHasFamilies() {
        Assertions.assertFalse(RenderFamily.all().isEmpty());
        Assertions.assertNotNull(RenderFamily.find("terrain_opaque"));
        Assertions.assertTrue(RenderFamily.shaderRouted().size() > 0);
        Assertions.assertTrue(RenderFamily.baseRendered().size() > 0);
    }

    @Test
    public void baseRenderedFamiliesHaveFallbackReason() {
        for (RenderFamily.Family f : RenderFamily.baseRendered()) {
            Assertions.assertFalse(f.fallbackReason().isBlank(),
                    "base-rendered family needs a fallback reason: " + f.name());
        }
    }
}