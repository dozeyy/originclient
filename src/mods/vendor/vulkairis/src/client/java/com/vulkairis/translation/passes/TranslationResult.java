package com.vulkairis.translation.passes;

import com.vulkairis.translation.lexer.SourceMap;

import java.util.Objects;

/**
 * The output of one translation pass (or a whole pass run): the rewritten source plus the
 * translated-line to source-line map for compiler diagnostics.
 */
public record TranslationResult(String output, SourceMap sourceMap) {

    public TranslationResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sourceMap, "sourceMap");
    }
}
