package com.vulkairis.rendering;

import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.ByteBuffer;

/**
 * Encapsulates a recorded draw call with vertex data, draw mode, format, and descriptor set.
 */
public record DrawCommand(
        ByteBuffer vertexData,
        VertexFormat.Mode mode,
        VertexFormat format,
        int vertexCount,
        long descriptorSet
) {
}
