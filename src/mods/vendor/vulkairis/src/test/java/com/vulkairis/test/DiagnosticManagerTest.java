package com.vulkairis.test;

import com.vulkairis.diagnostics.DiagnosticManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 1: the diagnostic record carries the full failure identity (stable ID, category, phase,
 * shader/stage, source location, source hash, next action) and identical failures are logged
 * once instead of every frame.
 */
public class DiagnosticManagerTest {

    @BeforeEach
    public void clearHistory() {
        DiagnosticManager.clearHistory();
    }

    @Test
    public void testRecordCarriesFullIdentity() {
        DiagnosticManager.DiagnosticRecord record = DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "SPIR-V compilation failed", "syntax error", null,
                "gbuffers_terrain", "FRAGMENT", "gbuffers_terrain.fsh", 12,
                "abc123", "Fix the reported GLSL error");

        Assertions.assertNotNull(record.id(), "record must carry a stable ID");
        Assertions.assertTrue(record.id().startsWith("VULK-"), "ID shape: " + record.id());
        Assertions.assertEquals(DiagnosticManager.Category.SHADER, record.category());
        Assertions.assertEquals(DiagnosticManager.Phase.COMPILE, record.phase());
        Assertions.assertEquals("gbuffers_terrain", record.shaderName());
        Assertions.assertEquals("FRAGMENT", record.stage());
        Assertions.assertEquals("gbuffers_terrain.fsh", record.sourceFile());
        Assertions.assertEquals(12, record.sourceLine());
        Assertions.assertEquals("abc123", record.sourceHash());
        Assertions.assertEquals("Fix the reported GLSL error", record.nextAction());

        String text = record.toString();
        Assertions.assertTrue(text.contains("VULK-"), "toString carries the ID: " + text);
        Assertions.assertTrue(text.contains("gbuffers_terrain"), "toString names the shader: " + text);
        Assertions.assertTrue(text.contains("COMPILE"), "toString names the phase: " + text);
    }

    @Test
    public void testRecordIdsAreUnique() {
        DiagnosticManager.DiagnosticRecord first = DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "first failure", null, null, "a", null, null, -1, "h1", null);
        DiagnosticManager.DiagnosticRecord second = DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "second failure", null, null, "b", null, null, -1, "h2", null);
        Assertions.assertNotEquals(first.id(), second.id(), "every record needs a distinct ID");
    }

    @Test
    public void testIdenticalFailuresAreLoggedOnce() {
        DiagnosticManager.DiagnosticRecord first = DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "SPIR-V compilation failed", "boom", null,
                "broken", "FRAGMENT", null, -1, "same-hash", "fix it");
        DiagnosticManager.DiagnosticRecord second = DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "SPIR-V compilation failed", "boom", null,
                "broken", "FRAGMENT", null, -1, "same-hash", "fix it");
        DiagnosticManager.DiagnosticRecord third = DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "SPIR-V compilation failed", "boom", null,
                "broken", "FRAGMENT", null, -1, "same-hash", "fix it");

        Assertions.assertSame(first, second, "repeat failures return the original record");
        Assertions.assertSame(first, third, "repeat failures return the original record");
        Assertions.assertEquals(1, DiagnosticManager.getRecentRecords(10).size(),
                "history must not grow per frame for an identical failure");
        Assertions.assertEquals(2, DiagnosticManager.getSuppressedDuplicateCount(),
                "two repeats must be counted as suppressed");
    }

    @Test
    public void testChangedSourceLogsAgain() {
        DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "SPIR-V compilation failed", "boom", null,
                "broken", "FRAGMENT", null, -1, "hash-one", null);
        DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "SPIR-V compilation failed", "boom", null,
                "broken", "FRAGMENT", null, -1, "hash-two", null);
        Assertions.assertEquals(2, DiagnosticManager.getRecentRecords(10).size(),
                "a changed source hash is a new failure and must be logged");
    }

    @Test
    public void testCompileAndLinkErrorsAreTracked() {
        Assertions.assertNull(DiagnosticManager.getLastCompileError());
        Assertions.assertNull(DiagnosticManager.getLastLinkError());

        DiagnosticManager.logStructured(
                DiagnosticManager.Category.SHADER, DiagnosticManager.Phase.COMPILE,
                "compile boom", null, null, "a", null, null, -1, null, null);
        Assertions.assertNotNull(DiagnosticManager.getLastCompileError());
        Assertions.assertNull(DiagnosticManager.getLastLinkError(),
                "a compile error must not appear as a link error");

        DiagnosticManager.logStructured(
                DiagnosticManager.Category.PIPELINE, DiagnosticManager.Phase.LINK,
                "link boom", null, null, "prog", null, null, -1, null, null);
        Assertions.assertNotNull(DiagnosticManager.getLastLinkError());
    }

    @Test
    public void testLegacyLogStillRecords() {
        DiagnosticManager.log(DiagnosticManager.Category.VULKAN, "hello", "world");
        Assertions.assertEquals(1, DiagnosticManager.getRecentRecords(10).size());
        DiagnosticManager.DiagnosticRecord record =
                DiagnosticManager.getRecentRecords(10).get(0);
        Assertions.assertEquals(DiagnosticManager.Phase.LIFECYCLE, record.phase());
        Assertions.assertNotNull(record.id());
    }

    @Test
    public void testHashSourceIsStable() {
        String first = DiagnosticManager.hashSource("void main() {}");
        String second = DiagnosticManager.hashSource("void main() {}");
        Assertions.assertNotNull(first);
        Assertions.assertEquals(first, second, "same source must hash identically");
        Assertions.assertNotEquals(first, DiagnosticManager.hashSource("void main() { }"),
                "changed source must hash differently");
        Assertions.assertNull(DiagnosticManager.hashSource(null));
        Assertions.assertNull(DiagnosticManager.hashSource("   "));
    }
}
