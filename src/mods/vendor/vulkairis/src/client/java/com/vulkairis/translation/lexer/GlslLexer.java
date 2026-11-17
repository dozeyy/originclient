package com.vulkairis.translation.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Source-aware GLSL lexer (Phase 3).
 *
 * <p>Distinguishes identifiers, numbers, strings, comments, whitespace/newlines, preprocessor
 * logical lines and symbols, preserving every token's exact text plus line/column/offset
 * metadata. Re-emitting a token list via {@link #emit(List)} reproduces the input byte-for-byte,
 * so passes only alter what they explicitly replace.
 *
 * <p>Conservative by design: unknown characters become single-character symbols (the downstream
 * compiler reports them); only unterminated block comments and strings fail, because emitting
 * partial output there would hide the real error. A token-count guard rejects pathological
 * input instead of exhausting memory.
 */
public final class GlslLexer {

    /** Upper bound on tokens per translation unit; malformed input fails instead of OOMing. */
    public static final int MAX_TOKENS = 500_000;

    private static final Set<String> THREE_CHAR_SYMBOLS = Set.of("<<=", ">>=");
    private static final Set<String> TWO_CHAR_SYMBOLS = Set.of(
            "++", "--", "<<", ">>", "<=", ">=", "==", "!=",
            "&&", "||", "^^", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "->");

    private GlslLexer() {
    }

    /**
     * @param source GLSL source; must not be {@code null}
     * @return the token list, including trivia; never {@code null}
     * @throws GlslLexException on null input, unterminated comments/strings or token overflow
     */
    public static List<GlslToken> tokenize(String source) {
        if (source == null) {
            throw new GlslLexException("GLSL source must not be null", 1, 1);
        }
        List<GlslToken> tokens = new ArrayList<>();
        int length = source.length();
        int offset = 0;
        int line = 1;
        int column = 1;
        boolean atLineStart = true;

        while (offset < length) {
            if (tokens.size() >= MAX_TOKENS) {
                throw new GlslLexException(
                        "Token limit exceeded (" + MAX_TOKENS + ")", line, column);
            }
            char c = source.charAt(offset);

            // Newlines (CRLF, CR, LF all become one NEWLINE token).
            if (c == '\r' || c == '\n') {
                int end = offset;
                if (c == '\r' && end + 1 < length && source.charAt(end + 1) == '\n') {
                    end += 2;
                } else {
                    end += 1;
                }
                tokens.add(new GlslToken(GlslToken.Kind.NEWLINE,
                        source.substring(offset, end), line, column, offset));
                offset = end;
                line++;
                column = 1;
                atLineStart = true;
                continue;
            }

            // Horizontal whitespace.
            if (c == ' ' || c == '\t' || c == '\f' || c == '\u000B') {
                int end = offset;
                while (end < length) {
                    char w = source.charAt(end);
                    if (w != ' ' && w != '\t' && w != '\f' && w != '\u000B') {
                        break;
                    }
                    end++;
                }
                tokens.add(new GlslToken(GlslToken.Kind.WHITESPACE,
                        source.substring(offset, end), line, column, offset));
                column += end - offset;
                offset = end;
                continue;
            }

            // A '#' that opens a logical line is a preprocessor directive: consume the whole
            // logical line (backslash continuations included) as one token so macro bodies,
            // version and extension lines are never rewritten piecemeal.
            if (atLineStart && c == '#') {
                int end = offset;
                while (true) {
                    while (end < length && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                        end++;
                    }
                    // Backslash-newline continues the logical line.
                    if (end > offset && source.charAt(end - 1) == '\\'
                            && end < length && (source.charAt(end) == '\n' || source.charAt(end) == '\r')) {
                        if (source.charAt(end) == '\r' && end + 1 < length && source.charAt(end + 1) == '\n') {
                            end += 2;
                        } else {
                            end += 1;
                        }
                        continue;
                    }
                    break;
                }
                tokens.add(new GlslToken(GlslToken.Kind.DIRECTIVE,
                        source.substring(offset, end), line, column, offset));
                int consumedLines = countNewlines(source, offset, end);
                line += consumedLines;
                column = consumedLines > 0 ? 1 : column + (end - offset);
                offset = end;
                atLineStart = consumedLines > 0;
                continue;
            }

            // Comments (only reachable outside directives, since directives consume their line).
            if (c == '/' && offset + 1 < length) {
                char next = source.charAt(offset + 1);
                if (next == '/') {
                    int end = offset + 2;
                    while (end < length && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                        end++;
                    }
                    tokens.add(new GlslToken(GlslToken.Kind.COMMENT_LINE,
                            source.substring(offset, end), line, column, offset));
                    column += end - offset;
                    offset = end;
                    atLineStart = false;
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", offset + 2);
                    if (end < 0) {
                        throw new GlslLexException("Unterminated block comment", line, column);
                    }
                    end += 2;
                    tokens.add(new GlslToken(GlslToken.Kind.COMMENT_BLOCK,
                            source.substring(offset, end), line, column, offset));
                    int consumedLines = countNewlines(source, offset, end);
                    line += consumedLines;
                    column = consumedLines > 0
                            ? end - (source.lastIndexOf('\n', end - 1) + 1) + 1
                            : column + (end - offset);
                    offset = end;
                    atLineStart = consumedLines > 0;
                    continue;
                }
            }

            // Strings (double- or single-quoted, with escapes).
            if (c == '"' || c == '\'') {
                int end = offset + 1;
                boolean closed = false;
                while (end < length) {
                    char s = source.charAt(end);
                    if (s == '\\' && end + 1 < length) {
                        end += 2;
                        continue;
                    }
                    if (s == c) {
                        closed = true;
                        end++;
                        break;
                    }
                    if (s == '\n' || s == '\r') {
                        break;
                    }
                    end++;
                }
                if (!closed) {
                    throw new GlslLexException("Unterminated string literal", line, column);
                }
                tokens.add(new GlslToken(GlslToken.Kind.STRING,
                        source.substring(offset, end), line, column, offset));
                column += end - offset;
                offset = end;
                atLineStart = false;
                continue;
            }

            // Numbers: leading digit, or '.' followed by a digit.
            if (isDigit(c) || (c == '.' && offset + 1 < length && isDigit(source.charAt(offset + 1)))) {
                int end = scanNumber(source, offset);
                tokens.add(new GlslToken(GlslToken.Kind.NUMBER,
                        source.substring(offset, end), line, column, offset));
                column += end - offset;
                offset = end;
                atLineStart = false;
                continue;
            }

            // Identifiers.
            if (isIdentifierStart(c)) {
                int end = offset + 1;
                while (end < length && isIdentifierPart(source.charAt(end))) {
                    end++;
                }
                tokens.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                        source.substring(offset, end), line, column, offset));
                column += end - offset;
                offset = end;
                atLineStart = false;
                continue;
            }

            // Symbols: longest match wins; anything else is one lenient character.
            String symbol = matchSymbol(source, offset);
            tokens.add(new GlslToken(GlslToken.Kind.SYMBOL,
                    symbol, line, column, offset));
            column += symbol.length();
            offset += symbol.length();
            atLineStart = false;
        }
        return tokens;
    }

    /** @return the source reproduced byte-for-byte from its tokens */
    public static String emit(List<GlslToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (GlslToken token : tokens) {
            out.append(token.text());
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || isDigit(c);
    }

    private static int scanNumber(String source, int offset) {
        int length = source.length();
        int end = offset;
        while (end < length) {
            char c = source.charAt(end);
            if (isIdentifierPart(c) || c == '.') {
                end++;
                continue;
            }
            // Exponent sign: only directly after 'e'/'E'.
            if ((c == '+' || c == '-') && end > offset
                    && (source.charAt(end - 1) == 'e' || source.charAt(end - 1) == 'E')
                    && end + 1 < length && isDigit(source.charAt(end + 1))) {
                end++;
                continue;
            }
            break;
        }
        return end;
    }

    private static String matchSymbol(String source, int offset) {
        int remaining = source.length() - offset;
        if (remaining >= 3) {
            String three = source.substring(offset, offset + 3);
            if (THREE_CHAR_SYMBOLS.contains(three)) {
                return three;
            }
        }
        if (remaining >= 2) {
            String two = source.substring(offset, offset + 2);
            if (TWO_CHAR_SYMBOLS.contains(two)) {
                return two;
            }
        }
        return source.substring(offset, offset + 1);
    }

    private static int countNewlines(String source, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                count++;
            } else if (c == '\r') {
                count++;
                if (i + 1 < to && source.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return count;
    }
}
