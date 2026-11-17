package com.vulkairis.translation.lexer;

/** Clear, located failure from {@link TokenStream} navigation (unbalanced or over-deep input). */
public class GlslParseException extends RuntimeException {

    public GlslParseException(String message) {
        super(message);
    }
}
