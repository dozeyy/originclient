package com.vulkairis.test;

import com.vulkairis.translation.lexer.GlslLexer;
import com.vulkairis.translation.lexer.SourceMap;
import com.vulkairis.translation.passes.LegacyKeywordPass;
import com.vulkairis.translation.passes.PassContext;
import com.vulkairis.translation.passes.PassRegistry;
import com.vulkairis.translation.passes.TranslationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 3 golden tests for the token-based legacy-keyword pass: every rewrite ships
 * before/after source and expected semantics. Comments, whitespace, macros, multi-line
 * declarations, nested expressions, arrays, layout qualifiers, structs, uniform blocks and
 * repeated identifiers are all covered.
 */
public class LegacyKeywordPassTest {

    @Test
    public void testAttributeBecomesIn() {
        Assertions.assertEquals("in vec3 Position;",
                LegacyKeywordPass.rewrite("attribute vec3 Position;", true));
    }

    @Test
    public void testVaryingDirectionIsStageSpecific() {
        Assertions.assertEquals("out vec2 vUv;",
                LegacyKeywordPass.rewrite("varying vec2 vUv;", true));
        Assertions.assertEquals("in vec2 vUv;",
                LegacyKeywordPass.rewrite("varying vec2 vUv;", false));
    }

    @Test
    public void testQualifiersAndWhitespacePreserved() {
        Assertions.assertEquals("flat out vec3 n;",
                LegacyKeywordPass.rewrite("flat varying vec3 n;", true));
        Assertions.assertEquals("  in   vec3 Position;",
                LegacyKeywordPass.rewrite("  attribute   vec3 Position;", true));
        Assertions.assertEquals("centroid in vec2 uv;",
                LegacyKeywordPass.rewrite("centroid varying vec2 uv;", false));
    }

    @Test
    public void testBlockCommentIsNeverRewritten() {
        String source = "/*\nattribute vec3 pos;\nvarying vec2 uv;\n*/\nattribute vec3 real;";
        String rewritten = LegacyKeywordPass.rewrite(source, true);
        Assertions.assertTrue(rewritten.contains("attribute vec3 pos;"),
                "commented attribute must survive: " + rewritten);
        Assertions.assertTrue(rewritten.contains("varying vec2 uv;"),
                "commented varying must survive: " + rewritten);
        Assertions.assertTrue(rewritten.contains("in vec3 real;"),
                "code after the comment still converts: " + rewritten);
    }

    @Test
    public void testLineCommentIsNeverRewritten() {
        String rewritten = LegacyKeywordPass.rewrite("// varying foo\nvarying vec2 vUv;", false);
        Assertions.assertTrue(rewritten.startsWith("// varying foo\n"),
                "line comment must survive: " + rewritten);
        Assertions.assertTrue(rewritten.contains("in vec2 vUv;"));
    }

    @Test
    public void testDirectivesAndMacroBodiesAreNeverRewritten() {
        String source = "#define varying 1\n#define FOO attribute\n#version 150\nvarying vec2 vUv;";
        String rewritten = LegacyKeywordPass.rewrite(source, false);
        Assertions.assertTrue(rewritten.contains("#define varying 1"), rewritten);
        Assertions.assertTrue(rewritten.contains("#define FOO attribute"), rewritten);
        Assertions.assertTrue(rewritten.contains("#version 150"), rewritten);
        Assertions.assertTrue(rewritten.contains("in vec2 vUv;"), rewritten);
    }

    @Test
    public void testMacroContinuationIsNeverRewritten() {
        String source = "#define M(a) a \\\n  varying\nvarying vec2 vUv;";
        String rewritten = LegacyKeywordPass.rewrite(source, true);
        Assertions.assertTrue(rewritten.contains("#define M(a) a \\\n  varying"),
                "continued macro body must survive verbatim: " + rewritten);
        Assertions.assertTrue(rewritten.contains("out vec2 vUv;"), rewritten);
    }

    @Test
    public void testUnrelatedConstructsByteIdentical() {
        String source = """
                struct Light { vec3 dir; float intensity; };
                layout(location = 0) in vec3 Position;
                uniform vec4 tintColor[2];
                uniform sampler2D gtexture;
                layout(std140) uniform Block { mat4 m; };
                float shade(vec2 uv, float f) {
                    return texture(gtexture, uv).r * (tintColor[0].a + uv.x);
                }
                """;
        Assertions.assertEquals(source, LegacyKeywordPass.rewrite(source, true),
                "structs, layouts, arrays, blocks, calls and nesting must pass through untouched");
    }

    @Test
    public void testMultiLineDeclarationConverts() {
        String source = "attribute\n    vec3\n    Position;";
        Assertions.assertEquals("in\n    vec3\n    Position;",
                LegacyKeywordPass.rewrite(source, true));
    }

    @Test
    public void testRepeatedIdentifiersAllConvert() {
        Assertions.assertEquals("out vec2 a;\nout vec2 b;\n",
                LegacyKeywordPass.rewrite("varying vec2 a;\nvarying vec2 b;\n", true));
    }

    @Test
    public void testSimilarIdentifiersUntouched() {
        String source = "float myvarying = 1.0;\nfloat attributeValue = 2.0;\n";
        Assertions.assertEquals(source, LegacyKeywordPass.rewrite(source, true),
                "identifiers merely containing the keywords must survive");
    }

    @Test
    public void testNullPassthrough() {
        Assertions.assertNull(LegacyKeywordPass.rewrite(null, true));
    }

    @Test
    public void testPassIsDeterministicAndIdempotent() {
        String source = "attribute vec3 Position;\nflat varying vec3 n;\n/* varying */\n";
        String once = LegacyKeywordPass.rewrite(source, true);
        Assertions.assertEquals(once, LegacyKeywordPass.rewrite(once, true),
                "re-running the pass must be stable");
        Assertions.assertEquals(once, LegacyKeywordPass.rewrite(source, true),
                "the pass must be deterministic");
    }

    @Test
    public void testSourceMapIsIdentityForKeywordSwap() {
        TranslationResult result = new LegacyKeywordPass().apply(
                GlslLexer.tokenize("varying vec2 a;\nvarying vec2 b;\n"),
                new PassContext(true, "probe"));
        Assertions.assertEquals("out vec2 a;\nout vec2 b;\n", result.output());
        Assertions.assertEquals(1, result.sourceMap().sourceLine(1));
        Assertions.assertEquals(2, result.sourceMap().sourceLine(2));
        Assertions.assertEquals(SourceMap.GENERATED, result.sourceMap().sourceLine(99));
    }

    @Test
    public void testRegistryRunsPassesInOrderAndToggles() {
        PassRegistry registry = new PassRegistry().register(new LegacyKeywordPass());
        Assertions.assertEquals(List.of(LegacyKeywordPass.PASS_NAME), registry.names());
        Assertions.assertTrue(registry.isEnabled(LegacyKeywordPass.PASS_NAME));

        PassContext ctx = new PassContext(false, "probe");
        Assertions.assertEquals("in vec2 vUv;",
                registry.run("varying vec2 vUv;", ctx).output());

        registry.setEnabled(LegacyKeywordPass.PASS_NAME, false);
        Assertions.assertFalse(registry.isEnabled(LegacyKeywordPass.PASS_NAME));
        Assertions.assertEquals("varying vec2 vUv;", registry.run("varying vec2 vUv;", ctx).output());

        registry.setEnabled("no-such-pass", true);
        Assertions.assertFalse(registry.isEnabled("no-such-pass"),
                "unknown pass names must not enable anything");
    }
}
