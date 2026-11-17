package com.vulkairis.backend.vulkanmod;

import java.util.Map;

/**
 * ponytail: minimal contract for a render pass.
 */
public interface RenderPassContract {
    String getName();
    Map<String, Integer> getAttachments();
    void execute();
}
