package com.vulkairis.translation.passes;

import com.vulkairis.translation.lexer.GlslLexer;
import com.vulkairis.translation.lexer.GlslToken;
import com.vulkairis.translation.lexer.SourceMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-based replacement for the legacy {@code attribute}/{@code varying} keyword rewrite
 * (Phase 3).
 *
 * <p>Only standalone identifier tokens in code regions are converted: {@code attribute} becomes
 * {@code in}, and {@code varying} becomes {@code out} in the vertex stage or {@code in} in the
 * fragment stage. Surrounding qualifier tokens ({@code flat}, {@code centroid}, ...) are
 * preserved untouched. Nothing inside comments, string literals or preprocessor directives
 * (macro bodies, version/extension lines) is ever renamed, and the swap adds no lines, so the
 * source map stays the identity.
 */
public final class LegacyKeywordPass implements TranslationPass {

    public static final String PASS_NAME = "legacy-keywords";

    @Override
    public String name() {
        return PASS_NAME;
    }

    @Override
    public String description() {
        return "Converts legacy attribute/varying keywords to in/out (stage-aware, code tokens only)";
    }

    @Override
    public TranslationResult apply(List<GlslToken> tokens, PassContext ctx) {
        String direction = ctx.isVertex() ? "out" : "in";
        List<GlslToken> rewritten = new ArrayList<>(tokens.size());
        for (GlslToken token : tokens) {
            if (token.isCodeIdentifier("attribute")) {
                rewritten.add(new GlslToken(GlslToken.Kind.IDENTIFIER, "in",
                        token.line(), token.column(), token.offset()));
            } else if (token.isCodeIdentifier("varying")) {
                rewritten.add(new GlslToken(GlslToken.Kind.IDENTIFIER, direction,
                        token.line(), token.column(), token.offset()));
            } else {
                rewritten.add(token);
            }
        }
        String output = GlslLexer.emit(rewritten);
        return new TranslationResult(output, SourceMap.identity(countLines(output)));
    }

    /**
     * Convenience form used by the translator pipeline.
     *
     * @param source   GLSL source; may be {@code null}
     * @param isVertex stage selector for {@code varying}
     * @return the rewritten source, or the input when null
     */
    public static String rewrite(String source, boolean isVertex) {
        if (source == null) {
            return null;
        }
        return new LegacyKeywordPass()
                .apply(GlslLexer.tokenize(source), new PassContext(isVertex, ""))
                .output();
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
