package com.vulkairis.backend.vulkanmod;

import java.nio.ByteBuffer;

/**
 * ponytail: interface for a shader program. 
 * Only one impl for now, but separates the 'what' (Iris program) from the 'how' (Vulkan pipeline).
 */
public interface ShaderProgramContract {
    ByteBuffer getSpirv();
    int getVertexStage();
    int getFragmentStage();
    // Reflection data is stored in the implementation
}
