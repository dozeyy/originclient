package com.vulkairis.translation.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded navigation over a token list (Phase 3).
 *
 * <p>Exposes a code-token view (trivia skipped) with safe helpers for matching
 * parentheses/brackets/braces, statement scanning and declaration lookup. Every potentially
 * unbounded walk carries a maximum-depth guard and every malformed structure fails with a
 * {@link GlslParseException} instead of hanging or returning a partial answer.
 */
public final class TokenStream {

    /** Maximum nesting depth accepted while matching delimiters. */
    public static final int MAX_DEPTH = 512;

    private final List<GlslToken> tokens;
    private final List<Integer> codePositions;

    public TokenStream(List<GlslToken> tokens) {
        this.tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        List<Integer> code = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).isCode()) {
                code.add(i);
            }
        }
        this.codePositions = List.copyOf(code);
    }

    /** @return all tokens, including trivia */
    public List<GlslToken> tokens() {
        return tokens;
    }

    /** @return number of code (non-trivia, non-directive) tokens */
    public int codeSize() {
        return codePositions.size();
    }

    /** @return the code token at code index {@code ci} */
    public GlslToken codeAt(int ci) {
        return tokens.get(codePositions.get(ci));
    }

    /** @return the raw token index of code index {@code ci} */
    public int rawIndexOf(int ci) {
        return codePositions.get(ci);
    }

    /** @return the next code index at/after raw position, or -1 */
    public int nextCodeFrom(int rawIndex) {
        for (int ci = 0; ci < codePositions.size(); ci++) {
            if (codePositions.get(ci) >= rawIndex) {
                return ci;
            }
        }
        return -1;
    }

    /**
     * Finds the delimiter matching the opener at code index {@code openCi}.
     *
     * @param openCi code index of the opening delimiter token
     * @param open   opening text, e.g. {@code "("}
     * @param close  closing text, e.g. {@code ")"}
     * @return the code index of the matching closer
     * @throws GlslParseException when the opener is absent, nesting is too deep, or no closer exists
     */
    public int findMatching(int openCi, String open, String close) {
        if (openCi < 0 || openCi >= codeSize()) {
            throw new GlslParseException("Delimiter search starts outside the token stream");
        }
        if (!codeAt(openCi).text().equals(open)) {
            GlslToken found = codeAt(openCi);
            throw new GlslParseException("Expected '" + open + "' but found '" + found.text()
                    + "' at line " + found.line());
        }
        int depth = 0;
        for (int ci = openCi; ci < codeSize(); ci++) {
            String text = codeAt(ci).text();
            if (text.equals(open)) {
                depth++;
                if (depth > MAX_DEPTH) {
                    throw new GlslParseException("Nesting depth exceeded near line "
                            + codeAt(ci).line());
                }
            } else if (text.equals(close)) {
                depth--;
                if (depth == 0) {
                    return ci;
                }
            }
        }
        GlslToken opener = codeAt(openCi);
        throw new GlslParseException("Unbalanced '" + open + "' opened at line " + opener.line());
    }

    /** @return code index of the ')' matching the '(' at {@code openCi} */
    public int findMatchingParen(int openCi) {
        return findMatching(openCi, "(", ")");
    }

    /** @return code index of the ']' matching the '[' at {@code openCi} */
    public int findMatchingBracket(int openCi) {
        return findMatching(openCi, "[", "]");
    }

    /** @return code index of the '}' matching the '{' at {@code openCi} */
    public int findMatchingBrace(int openCi) {
        return findMatching(openCi, "{", "}");
    }

    /**
     * Finds the end of the statement starting at code index {@code from}: the first top-level
     * semicolon, or the matching close brace when the statement opens a block.
     *
     * @return the code index of the terminator, or -1 when the statement never terminates
     */
    public int findStatementEnd(int from) {
        if (from < 0 || from >= codeSize()) {
            return -1;
        }
        int depth = 0;
        for (int ci = from; ci < codeSize(); ci++) {
            String text = codeAt(ci).text();
            if (text.equals("(") || text.equals("[") || text.equals("{")) {
                depth++;
                if (depth > MAX_DEPTH) {
                    throw new GlslParseException("Nesting depth exceeded near line "
                            + codeAt(ci).line());
                }
            } else if (text.equals(")") || text.equals("]") || text.equals("}")) {
                depth = Math.max(0, depth - 1);
                if (depth == 0 && text.equals("}")) {
                    return ci;
                }
            } else if (text.equals(";") && depth == 0) {
                return ci;
            }
        }
        return -1;
    }

    /**
     * Finds the next code token with the given text at/after code index {@code from}.
     *
     * @return the code index, or -1 when absent
     */
    public int findNext(int from, String text) {
        for (int ci = Math.max(0, from); ci < codeSize(); ci++) {
            if (codeAt(ci).text().equals(text)) {
                return ci;
            }
        }
        return -1;
    }
}
