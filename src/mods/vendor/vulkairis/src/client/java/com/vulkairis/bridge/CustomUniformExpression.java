package com.vulkairis.bridge;

import java.util.Map;

/**
 * Phase D custom-uniform expressions: a small stareval-equivalent for
 * {@code uniform.*}/{@code variable.*} expressions only.
 *
 * <p>Semantics reimplemented (not copied): pack custom uniforms may derive from engine
 * values with scalar arithmetic over named references. This evaluator supports exactly:</p>
 *
 * <ul>
 *   <li>float literals (e.g. {@code 0.5}, {@code -2}, {@code 1e3});</li>
 *   <li>references {@code uniform.NAME} and {@code variable.NAME} (first float component of
 *       the snapshot entry; a bare {@code NAME} also resolves via the snapshot);</li>
 *   <li>binary {@code + - * /}, parentheses, and unary minus;</li>
 *   <li>single-line whitespace.</li>
 * </ul>
 *
 * <p>There is deliberately no general expression language: no function calls, no ternary,
 * no comparisons, no vectors, no assignment. Anything else is a clean evaluation error,
 * never an exception to the caller.</p>
 */
public final class CustomUniformExpression {

    /** Evaluation outcome: a value, or a human-readable error. */
    public record Result(boolean ok, float value, String error) {
        public static Result ok(float value) {
            return new Result(true, value, "");
        }

        public static Result error(String error) {
            return new Result(false, Float.NaN,
                    error == null || error.isBlank() ? "invalid expression" : error);
        }
    }

    private CustomUniformExpression() {
    }

    /**
     * Evaluates {@code expression} against a snapshot (uniform name to float payload).
     *
     * @param expression the custom-uniform expression (may be {@code null})
     * @param snapshot engine snapshot; values resolve to their first float component
     * @return the scalar result, or an error describing the first problem
     */
    public static Result eval(String expression, Map<String, float[]> snapshot) {
        if (expression == null || expression.isBlank()) {
            return Result.error("empty custom-uniform expression");
        }
        Parser parser = new Parser(expression, snapshot == null ? Map.of() : snapshot);
        try {
            float value = parser.parseExpression();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                return Result.error("unexpected trailing input at offset " + parser.pos()
                        + " in '" + expression + "'");
            }
            if (!Float.isFinite(value)) {
                return Result.error("expression '" + expression + "' is not finite");
            }
            return Result.ok(value);
        } catch (ParseFailure failure) {
            return Result.error(failure.getMessage());
        }
    }

    /** @return whether the expression references only supported forms (quick pre-check) */
    public static boolean isSupportedForm(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        // Reject the general-language constructs up front: calls, ternary, comparisons.
        if (expression.indexOf('(') >= 0 && expression.matches(".*[A-Za-z_][A-Za-z0-9_.]*\\s*\\(.*")) {
            // Parentheses alone are fine; only name-followed-by-paren (a call) is rejected.
            // Disambiguate by checking for a call-shaped token below in the parser too.
            if (expression.matches(".*\\b(sin|cos|tan|pow|mix|clamp|min|max|floor|ceil|fract|abs)\\s*\\(.*")) {
                return false;
            }
        }
        return expression.indexOf('?') < 0 && expression.indexOf(':') < 0
                && !expression.contains("==") && !expression.contains("!=")
                && !expression.contains("<") && !expression.contains(">")
                && !expression.contains("&&") && !expression.contains("||")
                && !expression.contains(";");
    }

    private static final class ParseFailure extends Exception {
        ParseFailure(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String input;
        private final Map<String, float[]> snapshot;
        private int pos;

        Parser(String input, Map<String, float[]> snapshot) {
            this.input = input;
            this.snapshot = snapshot;
        }

        int pos() {
            return pos;
        }

        boolean atEnd() {
            return pos >= input.length();
        }

        float parseExpression() throws ParseFailure {
            float value = parseTerm();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    value += parseTerm();
                } else if (match('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private float parseTerm() throws ParseFailure {
            float value = parseFactor();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    value *= parseFactor();
                } else if (match('/')) {
                    float divisor = parseFactor();
                    if (divisor == 0.0f) {
                        throw new ParseFailure("division by zero in '" + input + "'");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private float parseFactor() throws ParseFailure {
            skipWhitespace();
            if (match('-')) {
                return -parseFactor();
            }
            if (match('+')) {
                return parseFactor();
            }
            if (match('(')) {
                float value = parseExpression();
                skipWhitespace();
                if (!match(')')) {
                    throw new ParseFailure("missing ')' in '" + input + "'");
                }
                return value;
            }
            char c = peek();
            if (c == 0) {
                throw new ParseFailure("unexpected end of expression '" + input + "'");
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                return parseReference();
            }
            throw new ParseFailure("unexpected character '" + c + "' at offset " + pos
                    + " in '" + input + "'");
        }

        private float parseNumber() throws ParseFailure {
            int start = pos;
            while (!atEnd()) {
                char c = input.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+'
                        || c == '-') {
                    // A sign is only part of the literal inside an exponent; the loop below
                    // validates the whole token via Float.parseFloat.
                    pos++;
                } else {
                    break;
                }
            }
            String token = input.substring(start, pos);
            try {
                return Float.parseFloat(token);
            } catch (NumberFormatException e) {
                throw new ParseFailure("bad number '" + token + "' in '" + input + "'");
            }
        }

        private float parseReference() throws ParseFailure {
            int start = pos;
            while (!atEnd()) {
                char c = input.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = input.substring(start, pos);
            skipWhitespace();
            if (!atEnd() && input.charAt(pos) == '(') {
                throw new ParseFailure("function calls are not supported: '" + token + "'");
            }
            String key = token;
            if (token.startsWith("uniform.")) {
                key = token.substring("uniform.".length());
            } else if (token.startsWith("variable.")) {
                key = token.substring("variable.".length());
            }
            if (key.isEmpty() || key.endsWith(".")) {
                throw new ParseFailure("bad reference '" + token + "' in '" + input + "'");
            }
            float[] payload = snapshot.get(key);
            if (payload == null) {
                payload = snapshot.get(token);
            }
            if (payload == null || payload.length == 0) {
                throw new ParseFailure("unknown uniform/variable '" + token + "'");
            }
            return payload[0];
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return atEnd() ? 0 : input.charAt(pos);
        }

        private boolean match(char expected) {
            skipWhitespace();
            if (!atEnd() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
