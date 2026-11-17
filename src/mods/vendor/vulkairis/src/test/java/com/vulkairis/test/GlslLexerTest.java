package com.vulkairis.test;

import com.vulkairis.testpack.ShaderStageFixture;
import com.vulkairis.testpack.ShaderTestPack;
import com.vulkairis.testpack.ShaderTestPacks;
import com.vulkairis.translation.lexer.GlslLexException;
import com.vulkairis.translation.lexer.GlslLexer;
import com.vulkairis.translation.lexer.GlslToken;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 3: the lexer distinguishes identifiers, numbers, strings, comments, whitespace,
 * directives and symbols while preserving trivia and line/column metadata.
 */
public class GlslLexerTest {

    @Test
    public void testTokenKinds() {
        List<GlslToken> tokens = GlslLexer.tokenize("vec3 p; // trailing\n#version 150\n");
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.IDENTIFIER
                && t.text().equals("vec3")), "identifier token: " + tokens);
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.COMMENT_LINE),
                "line comment token: " + tokens);
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.DIRECTIVE),
                "directive token: " + tokens);
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.NEWLINE),
                "newline token: " + tokens);
    }

    @Test
    public void testNumbersStringsAndSymbols() {
        List<GlslToken> tokens = GlslLexer.tokenize("float x = 1.5e-3;\nvec2 v = vec2(\"s\", 2u);\na <<= 2;\n");
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.NUMBER
                && t.text().equals("1.5e-3")), "exponent number: " + tokens);
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.STRING
                && t.text().equals("\"s\"")), "string literal: " + tokens);
        Assertions.assertTrue(tokens.stream().anyMatch(t -> t.kind() == GlslToken.Kind.SYMBOL
                && t.text().equals("<<=")), "multi-char operator: " + tokens);
    }

    @Test
    public void testBlockCommentIsOneToken() {
        List<GlslToken> tokens = GlslLexer.tokenize("/* a\nb */\nint x;");
        long blocks = tokens.stream()
                .filter(t -> t.kind() == GlslToken.Kind.COMMENT_BLOCK).count();
        Assertions.assertEquals(1, blocks, "one block comment token: " + tokens);
        GlslToken code = tokens.stream().filter(GlslToken::isCode).findFirst().orElseThrow();
        Assertions.assertEquals(3, code.line(), "code after comment keeps its line: " + code);
    }

    @Test
    public void testDirectiveWithContinuationIsOneToken() {
        List<GlslToken> tokens = GlslLexer.tokenize("#define M(a) a \\\n  + 1\nint x;");
        long directives = tokens.stream()
                .filter(t -> t.kind() == GlslToken.Kind.DIRECTIVE).count();
        Assertions.assertEquals(1, directives, "continued macro is one token: " + tokens);
        GlslToken directive = tokens.stream()
                .filter(t -> t.kind() == GlslToken.Kind.DIRECTIVE).findFirst().orElseThrow();
        Assertions.assertTrue(directive.text().contains("\\"), "continuation preserved");
        GlslToken code = tokens.stream().filter(GlslToken::isCode).findFirst().orElseThrow();
        Assertions.assertEquals(3, code.line(), "code after macro keeps its line: " + code);
    }

    @Test
    public void testLineAndColumnMetadata() {
        List<GlslToken> tokens = GlslLexer.tokenize("vec3 p;\n  in vec2 uv;");
        GlslToken first = tokens.get(0);
        Assertions.assertEquals(1, first.line());
        Assertions.assertEquals(1, first.column());
        Assertions.assertEquals(0, first.offset());
        GlslToken inToken = tokens.stream()
                .filter(t -> t.text().equals("in")).findFirst().orElseThrow();
        Assertions.assertEquals(2, inToken.line(), "second-line token: " + inToken);
        Assertions.assertEquals(3, inToken.column(), "indented column: " + inToken);
    }

    @Test
    public void testEmitRoundTripsAllFixtures() {
        for (ShaderTestPack pack : ShaderTestPacks.all()) {
            for (ShaderStageFixture stage : pack.stages()) {
                Assertions.assertEquals(stage.glsl(), GlslLexer.emit(GlslLexer.tokenize(stage.glsl())),
                        "round-trip must be byte-identical for " + stage.name());
            }
        }
    }

    @Test
    public void testUnterminatedBlockCommentFails() {
        Assertions.assertThrows(GlslLexException.class,
                () -> GlslLexer.tokenize("int x;\n/* unterminated"),
                "unterminated block comment must fail loudly, not emit partial tokens");
    }

    @Test
    public void testUnterminatedStringFails() {
        Assertions.assertThrows(GlslLexException.class,
                () -> GlslLexer.tokenize("const char* s = \"oops;"),
                "unterminated string must fail loudly");
    }

    @Test
    public void testNullSourceFails() {
        Assertions.assertThrows(GlslLexException.class, () -> GlslLexer.tokenize(null));
    }
}
