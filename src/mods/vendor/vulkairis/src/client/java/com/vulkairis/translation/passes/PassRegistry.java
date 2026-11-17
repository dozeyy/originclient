package com.vulkairis.translation.passes;

import com.vulkairis.translation.lexer.GlslLexer;
import com.vulkairis.translation.lexer.GlslToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered, named translation passes (Phase 3).
 *
 * <p>Each pass can be enabled or disabled individually during diagnostics, and every run
 * records which passes executed. Passes run in registration order over the token list.
 */
public final class PassRegistry {

    private final Map<String, TranslationPass> passes = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();

    /** @return this registry, for chaining */
    public PassRegistry register(TranslationPass pass) {
        passes.put(pass.name(), pass);
        enabled.putIfAbsent(pass.name(), true);
        return this;
    }

    /** Enables or disables a pass by name (unknown names are ignored). */
    public void setEnabled(String name, boolean value) {
        if (passes.containsKey(name)) {
            enabled.put(name, value);
        }
    }

    /** @return whether the named pass runs (unknown names report false) */
    public boolean isEnabled(String name) {
        return enabled.getOrDefault(name, false);
    }

    /** @return pass names in execution order */
    public List<String> names() {
        return List.copyOf(passes.keySet());
    }

    /**
     * Lexes {@code source} and runs every enabled pass in order.
     *
     * @return the final output and the last pass's source map
     */
    public TranslationResult run(String source, PassContext ctx) {
        List<GlslToken> tokens = GlslLexer.tokenize(source);
        TranslationResult result = null;
        for (TranslationPass pass : passes.values()) {
            if (!isEnabled(pass.name())) {
                continue;
            }
            result = pass.apply(tokens, ctx);
            tokens = relex(result.output());
        }
        if (result == null) {
            return new TranslationResult(source,
                    com.vulkairis.translation.lexer.SourceMap.identity(countLines(source)));
        }
        return result;
    }

    private static List<GlslToken> relex(String output) {
        return new ArrayList<>(GlslLexer.tokenize(output));
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
