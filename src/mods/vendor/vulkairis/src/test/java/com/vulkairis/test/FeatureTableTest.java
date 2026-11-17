package com.vulkairis.test;

import com.vulkairis.compatibility.FeatureTable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 13: the published feature table is complete, pinned and honest — every plan family is
 * represented, versions are locked, and no blanket compatibility is claimed.
 */
public class FeatureTableTest {

    @Test
    public void testVersionsPinned() {
        Assertions.assertEquals("1.21.1", FeatureTable.MINECRAFT_VERSION);
        Assertions.assertEquals("1.8.14-beta.1+mc1.21.1", FeatureTable.IRIS_VERSION);
        Assertions.assertEquals("0.6.7+1.21.1", FeatureTable.VULKANMOD_VERSION);
    }

    @Test
    public void testEveryFamilyRepresented() {
        String joined = FeatureTable.rows().stream()
                .map(row -> row.feature().toLowerCase())
                .reduce("", (a, b) -> a + "\n" + b);
        for (String family : new String[]{
                "interception", "translation", "compilation", "validation", "reflection",
                "link", "status", "pipeline integration", "test draws", "render targets",
                "uniform", "scheduling", "terrain", "shadow", "translucency",
                "tessellation", "hud"}) {
            Assertions.assertTrue(joined.contains(family),
                    "feature table must cover: " + family);
        }
    }

    @Test
    public void testNoBlanketCompatibilityClaim() {
        for (FeatureTable.FeatureRow row : FeatureTable.rows()) {
            Assertions.assertFalse(row.feature().toLowerCase().contains("all shader"),
                    "no blanket compatibility claims: " + row.feature());
            Assertions.assertFalse(row.note().isBlank(), "every row needs evidence or a reason");
        }
        Assertions.assertFalse(FeatureTable.atMaturity(FeatureTable.Maturity.UNSUPPORTED).isEmpty(),
                "unsupported work must be named, not hidden");
        Assertions.assertFalse(FeatureTable.atMaturity(FeatureTable.Maturity.TESTED).isEmpty());
    }

    @Test
    public void testGameRunEvidenceTracked() {
        long experimental = FeatureTable.atMaturity(FeatureTable.Maturity.EXPERIMENTAL).size();
        Assertions.assertTrue(experimental > 0,
                "game-run-pending work must stay visible as experimental");
        for (FeatureTable.FeatureRow row :
                FeatureTable.atMaturity(FeatureTable.Maturity.EXPERIMENTAL)) {
            Assertions.assertTrue(row.note().toLowerCase().contains("game-run"),
                    "experimental rows must name the missing proof: " + row.feature());
        }
    }
}
