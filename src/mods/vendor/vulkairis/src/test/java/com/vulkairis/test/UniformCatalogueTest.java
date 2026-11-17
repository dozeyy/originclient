package com.vulkairis.test;

import com.vulkairis.rendering.state.UniformCatalogue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UniformCatalogueTest {

    @Test
    public void catalogueIsNonEmptyAndUnique() {
        Assertions.assertFalse(UniformCatalogue.all().isEmpty());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (UniformCatalogue.Entry e : UniformCatalogue.all()) {
            Assertions.assertTrue(names.add(e.name()), "duplicate uniform: " + e.name());
            Assertions.assertNotNull(e.glslType());
            Assertions.assertNotNull(e.cadence());
        }
    }

    @Test
    public void supportedLookupWorks() {
        Assertions.assertTrue(UniformCatalogue.isSupported("projectionMatrix"));
        Assertions.assertFalse(UniformCatalogue.isSupported("definitelyNotAUniform"));
        Assertions.assertNotNull(UniformCatalogue.find("cameraPosition"));
    }
}