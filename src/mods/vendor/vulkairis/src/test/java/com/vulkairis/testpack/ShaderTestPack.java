package com.vulkairis.testpack;

import java.util.List;

/**
 * A synthetic, known-good shader pack used to verify the Vulkairis translation pipeline and (once a
 * GPU is present) pass execution. Each pack is a named set of stage fixtures; the harness drives
 * every stage through translate → compile → validate → reflect.
 *
 * @param name        pack identity
 * @param description what this pack isolates
 * @param stages      the stage fixtures to run
 */
public record ShaderTestPack(String name, String description, List<ShaderStageFixture> stages) {

    public ShaderTestPack {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pack name must be non-blank");
        }
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("pack '" + name + "' has no stages");
        }
        description = description == null ? "" : description;
        stages = List.copyOf(stages);
    }
}
