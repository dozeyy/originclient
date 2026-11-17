package com.vulkairis.translation.lexer;

import java.util.Objects;

/**
 * One lexical unit of GLSL source (Phase 3).
 *
 * <p>Trivia (whitespace, newlines, comments) is preserved as tokens with exact text, so a pass
 * that replaces only selected tokens reproduces the input byte-for-byte everywhere else.
 * Directives ({@code #version}, {@code #extension}, {@code #define}, ...) are single tokens:
 * their bodies are never rewritten, which keeps macro names, macro text and comments safe from
 * substring replacement.
 *
 * @param kind   token classification
 * @param text   exact source text
 * @param line   1-based source line where the token starts
 * @param column 1-based source column where the token starts
 * @param offset 0-based character offset where the token starts
 */
public record GlslToken(Kind kind, String text, int line, int column, int offset) {

    public enum Kind {
        IDENTIFIER,
        NUMBER,
        STRING,
        COMMENT_LINE,
        COMMENT_BLOCK,
        WHITESPACE,
        NEWLINE,
        DIRECTIVE,
        SYMBOL
    }

    public GlslToken {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }

    /** @return whether this token is formatting or a comment rather than GLSL code */
    public boolean isTrivia() {
        return kind == Kind.WHITESPACE || kind == Kind.NEWLINE
                || kind == Kind.COMMENT_LINE || kind == Kind.COMMENT_BLOCK;
    }

    /**
     * @return whether this token is rewritable code. Directives are excluded: an identifier
     * inside {@code #define} text or a version/extension line must never be renamed.
     */
    public boolean isCode() {
        return !isTrivia() && kind != Kind.DIRECTIVE;
    }

    /** @return whether this token is the identifier {@code name} in a code region */
    public boolean isCodeIdentifier(String name) {
        return kind == Kind.IDENTIFIER && isCode() && text.equals(name);
    }

    @Override
    public String toString() {
        return kind + "('" + text + "')@" + line + ":" + column;
    }
}
