package com.vulkairis.translation.lexer;

/** Clear, located failure from {@link GlslLexer} (never silent partial output). */
public class GlslLexException extends RuntimeException {

    private final int line;
    private final int column;

    public GlslLexException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
