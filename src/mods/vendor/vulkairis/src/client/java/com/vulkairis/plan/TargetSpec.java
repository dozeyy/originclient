package com.vulkairis.plan;

import java.util.Objects;

/**
 * Phase 3: Immutable specification of a render target (format, dimensions/scale, ping-pong, lifetime).
 */
public record TargetSpec(
        String name,
        int index,
        String format,
        float scaleX,
        float scaleY,
        int explicitWidth,
        int explicitHeight,
        boolean fixedResolution,
        boolean pingPong,
        boolean clearEachFrame
) {
    public TargetSpec {
        Objects.requireNonNull(name, "name");
        format = (format != null && !format.isBlank()) ? format : "RGBA8";
        if (scaleX <= 0.0f) scaleX = 1.0f;
        if (scaleY <= 0.0f) scaleY = 1.0f;
    }

    public static TargetSpec windowScaled(String name, int index, String format, float scaleX, float scaleY, boolean pingPong) {
        return new TargetSpec(name, index, format, scaleX, scaleY, 0, 0, false, pingPong, true);
    }

    public static TargetSpec fixed(String name, int index, String format, int width, int height, boolean clearEachFrame) {
        return new TargetSpec(name, index, format, 1.0f, 1.0f, Math.max(1, width), Math.max(1, height), true, false, clearEachFrame);
    }

    public int effectiveWidth(int windowWidth) {
        if (fixedResolution) {
            return explicitWidth;
        }
        return Math.max(1, Math.round(windowWidth * scaleX));
    }

    public int effectiveHeight(int windowHeight) {
        if (fixedResolution) {
            return explicitHeight;
        }
        return Math.max(1, Math.round(windowHeight * scaleY));
    }
}
