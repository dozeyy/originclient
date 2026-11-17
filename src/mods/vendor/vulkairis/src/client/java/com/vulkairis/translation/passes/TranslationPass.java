package com.vulkairis.translation.passes;

import com.vulkairis.translation.lexer.GlslToken;

import java.util.List;

/**
 * One named, ordered GLSL translation step (Phase 3).
 *
 * <p>Passes receive lexed tokens and return replacement tokens; trivia preservation in the
 * token list keeps every untouched region byte-identical. A pass must be deterministic and must
 * leave tokens it does not own (comments, directives, unrelated declarations) alone.
 */
public interface TranslationPass {

    /** @return the stable pass name used for diagnostics and enable/disable toggles */
    String name();

    /** @return human-readable description of what the pass rewrites */
    default String description() {
        return "";
    }

    /**
     * @param tokens tokens to transform (owned by the caller; must not be mutated)
     * @param ctx    stage context
     * @return the translation result: replacement output plus its source map
     */
    TranslationResult apply(List<GlslToken> tokens, PassContext ctx);
}
