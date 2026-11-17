package com.vulkairis.translation.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Translated-line to source-line mapping retained for compiler diagnostics (Phase 3).
 *
 * <p>Passes that only swap tokens on a line keep the identity mapping. Inserted/generated lines
 * map to {@code -1} so a shaderc error on such a line is reported as generated text rather than
 * attributed to the wrong source line. The table grows as passes migrate to the token pipeline.
 */
public final class SourceMap {

    /** Marker for generated lines with no source counterpart. */
    public static final int GENERATED = -1;

    private final List<Integer> translatedToSource;

    private SourceMap(List<Integer> translatedToSource) {
        this.translatedToSource = List.copyOf(translatedToSource);
    }

    /**
     * @param translatedLine 1-based line in the translated output
     * @return the 1-based source line, or {@link #GENERATED} when unknown/generated
     */
    public int sourceLine(int translatedLine) {
        int index = translatedLine - 1;
        if (index < 0 || index >= translatedToSource.size()) {
            return GENERATED;
        }
        return translatedToSource.get(index);
    }

    /** @return number of translated lines covered */
    public int size() {
        return translatedToSource.size();
    }

    /** @return an identity map over {@code lines} lines */
    public static SourceMap identity(int lines) {
        Builder builder = builder();
        for (int line = 1; line <= Math.max(0, lines); line++) {
            builder.mapLine(line);
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Integer> rows = new ArrayList<>();

        /** Maps the next translated line to {@code sourceLine} (1-based). */
        public Builder mapLine(int sourceLine) {
            rows.add(sourceLine);
            return this;
        }

        /** Maps the next translated line as generated text ({@link #GENERATED}). */
        public Builder mapGenerated() {
            rows.add(GENERATED);
            return this;
        }

        public SourceMap build() {
            return new SourceMap(rows);
        }
    }
}
