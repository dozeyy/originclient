package com.vulkairis.test;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.iris.SourceProvenance;
import com.vulkairis.iris.SourceUnit;
import com.vulkairis.testpack.ShaderStageFixture;
import com.vulkairis.testpack.ShaderTestPack;
import com.vulkairis.testpack.ShaderTestPacks;
import com.vulkairis.translation.ShaderTranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 2: the Iris source boundary. Capture happens exactly once per pipeline generation,
 * every translation output traces to its input hash/stage/generation, reloads invalidate
 * deterministically, and re-translation is stable rather than doubly applied.
 */
public class SourceProvenanceTest {

    private static final String SRC = """
            #version 150 core
            #define FOO 1
            uniform float t;
            void main() {
            }
            """;

    @Test
    public void testCaptureRecordsFullProvenance() {
        SourceProvenance.clearForTests();
        SourceUnit unit = SourceProvenance.capture("pack-a", "gbuffers_terrain", "VERTEX", SRC);

        Assertions.assertEquals("pack-a", unit.packId());
        Assertions.assertEquals("gbuffers_terrain", unit.irisProgramName());
        Assertions.assertEquals("VERTEX", unit.stage());
        Assertions.assertEquals(DiagnosticManager.hashSource(SRC), unit.originalHash());
        Assertions.assertNull(unit.translatedHash(), "no translation recorded yet");
        Assertions.assertEquals("iris-transformed", unit.origin());
        Assertions.assertEquals(SourceProvenance.currentGeneration(), unit.pipelineGeneration());
        Assertions.assertTrue(unit.observedMacros().contains("FOO"),
                "observed macros must list Iris-resolved defines, got: " + unit.observedMacros());
    }

    @Test
    public void testStageKeyNormalization() {
        SourceProvenance.clearForTests();
        SourceUnit byName = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        SourceUnit byId = SourceProvenance.capture("pack-a", "prog", "35633", SRC);
        Assertions.assertEquals(byName.id(), byId.id(),
                "VERTEX and 35633 must address the same unit");
        Assertions.assertEquals("FRAGMENT",
                SourceProvenance.normalizeStage("35632"));
    }

    @Test
    public void testRepeatCaptureOfIdenticalInputIsStable() {
        SourceProvenance.clearForTests();
        SourceUnit first = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        SourceUnit second = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        Assertions.assertEquals(first, second, "identical input must not create a second unit");
        Assertions.assertEquals(1, SourceProvenance.liveUnits().size());
    }

    @Test
    public void testChangedInputReplacesUnit() {
        SourceProvenance.clearForTests();
        SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        SourceUnit updated = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC + "\n");
        Assertions.assertEquals(DiagnosticManager.hashSource(SRC + "\n"), updated.originalHash());
        Assertions.assertEquals(1, SourceProvenance.liveUnits().size(),
                "a changed source replaces the unit for the same key");
    }

    @Test
    public void testTranslationLinksToInputHashStageAndGeneration() {
        SourceProvenance.clearForTests();
        SourceUnit captured = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        SourceUnit translated = SourceProvenance.recordTranslation(
                "pack-a", "prog", "35633", "translated-output");

        Assertions.assertEquals(captured.id(), translated.id(),
                "translation must link to the captured unit across stage-key forms");
        Assertions.assertEquals(DiagnosticManager.hashSource("translated-output"),
                translated.translatedHash());
        Assertions.assertEquals(captured.originalHash(), translated.originalHash());
        Assertions.assertEquals(SourceProvenance.currentGeneration(),
                translated.pipelineGeneration());
    }

    @Test
    public void testDirectCompilerUseStaysTraceable() {
        SourceProvenance.clearForTests();
        SourceUnit unit = SourceProvenance.recordTranslation(
                "pack-a", "harness-prog", "FRAGMENT", "translated-output");
        Assertions.assertEquals("unintercepted", unit.origin());
        Assertions.assertEquals(DiagnosticManager.hashSource("translated-output"),
                unit.translatedHash());
    }

    @Test
    public void testGenerationBumpInvalidatesOldUnits() {
        SourceProvenance.clearForTests();
        SourceUnit old = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        Assertions.assertFalse(SourceProvenance.isStale(old));

        SourceProvenance.nextGeneration("pack change");
        Assertions.assertTrue(SourceProvenance.isStale(old),
                "units from an older generation must never be reused");
        Assertions.assertTrue(SourceProvenance.liveUnits().isEmpty(),
                "no live units immediately after the bump");

        SourceUnit fresh = SourceProvenance.capture("pack-a", "prog", "VERTEX", SRC);
        Assertions.assertFalse(SourceProvenance.isStale(fresh));
        Assertions.assertTrue(fresh.pipelineGeneration() > old.pipelineGeneration());
        Assertions.assertNotEquals(old.id(), fresh.id());
    }

    @Test
    public void testRetranslationIsStableAcrossAllFixtures() {
        for (ShaderTestPack pack : ShaderTestPacks.all()) {
            for (ShaderStageFixture stage : pack.stages()) {
                String once = ShaderTranslator.preprocessGlsl(stage.glsl(), stage.vertex());
                String twice = ShaderTranslator.preprocessGlsl(once, stage.vertex());
                Assertions.assertEquals(once, twice,
                        "re-translating " + stage.name() + " must be stable, not doubly applied");
                Assertions.assertTrue(countOccurrences(once, ShaderTranslator.UNIFORM_BLOCK_NAME) <= 1,
                        "no duplicate uniform block in " + stage.name());
            }
        }
    }

    @Test
    public void testTranslatedSourceIsDetectable() {
        SourceProvenance.clearForTests();
        Assertions.assertFalse(ShaderTranslator.isTranslatedSource(SRC));
        String translated = ShaderTranslator.preprocessGlsl(SRC, true);
        if (translated.contains(ShaderTranslator.UNIFORM_BLOCK_NAME)) {
            Assertions.assertTrue(ShaderTranslator.isTranslatedSource(translated));
        }
        Assertions.assertFalse(ShaderTranslator.isTranslatedSource(null));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
