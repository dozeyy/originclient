package com.vulkairis.translation.passes;

/** Stage context shared by every translation pass. */
public record PassContext(boolean isVertex, String sourceName) {

    public PassContext {
        sourceName = sourceName == null ? "" : sourceName;
    }
}
