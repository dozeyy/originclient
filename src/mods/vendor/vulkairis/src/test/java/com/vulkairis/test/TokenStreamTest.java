package com.vulkairis.test;

import com.vulkairis.translation.lexer.GlslLexer;
import com.vulkairis.translation.lexer.GlslParseException;
import com.vulkairis.translation.lexer.TokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: the bounded token stream matches delimiters, finds statement ends and fails loudly
 * on malformed input.
 */
public class TokenStreamTest {

    @Test
    public void testMatchingParensWithNesting() {
        TokenStream stream = new TokenStream(
                GlslLexer.tokenize("float f(vec2 a, mat3 m[2]) { return a.x; }"));
        int open = stream.findNext(0, "(");
        int close = stream.findMatchingParen(open);
        Assertions.assertEquals(")", stream.codeAt(close).text());
        int brace = stream.findNext(0, "{");
        Assertions.assertEquals("}", stream.codeAt(stream.findMatchingBrace(brace)).text());
    }

    @Test
    public void testMatchingBracket() {
        TokenStream stream = new TokenStream(GlslLexer.tokenize("float a[1 + 2];"));
        int open = stream.findNext(0, "[");
        Assertions.assertEquals("]", stream.codeAt(stream.findMatchingBracket(open)).text());
    }

    @Test
    public void testUnbalancedDelimiterFails() {
        TokenStream stream = new TokenStream(GlslLexer.tokenize("float f(vec2 a;"));
        int open = stream.findNext(0, "(");
        Assertions.assertThrows(GlslParseException.class, () -> stream.findMatchingParen(open),
                "unbalanced input must fail, not return a partial match");
    }

    @Test
    public void testWrongOpenerFails() {
        TokenStream stream = new TokenStream(GlslLexer.tokenize("int x;"));
        Assertions.assertThrows(GlslParseException.class, () -> stream.findMatchingParen(0),
                "matching must verify the opener token");
    }

    @Test
    public void testStatementEnd() {
        TokenStream simple = new TokenStream(GlslLexer.tokenize("int x;"));
        int end = simple.findStatementEnd(0);
        Assertions.assertTrue(end >= 0 && simple.codeAt(end).text().equals(";"));

        TokenStream nested = new TokenStream(
                GlslLexer.tokenize("vec4 c = texture(t, uv * vec2(1.0, 2.0));"));
        int nestedEnd = nested.findStatementEnd(0);
        Assertions.assertTrue(nestedEnd >= 0 && nested.codeAt(nestedEnd).text().equals(";"),
                "nested expressions must not end the statement early");

        TokenStream unterminated = new TokenStream(GlslLexer.tokenize("int x"));
        Assertions.assertEquals(-1, unterminated.findStatementEnd(0));
    }

    @Test
    public void testCommentsAndDirectivesAreNotCode() {
        TokenStream stream = new TokenStream(GlslLexer.tokenize(
                "/* ( */\n#define P (1)\nint x; // )\n"));
        // Only the declaration's semicolon is a code token; comment/directive parens are hidden.
        Assertions.assertEquals(-1, stream.findNext(0, "("),
                "no code-level paren may leak from comments or directives");
        Assertions.assertTrue(stream.codeSize() > 0);
    }
}
