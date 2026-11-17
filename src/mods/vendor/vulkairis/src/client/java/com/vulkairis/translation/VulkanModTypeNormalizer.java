package com.vulkairis.translation;

import java.util.Set;

/**
 * Normalizes GLSL member types that VulkanMod's shader converter rejects.
 *
 * <p>VulkanMod's {@code Uniform.createUniformInfo} throws
 * {@code RuntimeException("not admitted type: bool")}, which aborts every Iris shadow/gbuffer
 * program during pipeline creation. Iris's standard macros inject boolean uniform members, so the
 * type has to be admitted before the converter sees it.</p>
 *
 * <p>This is the single source of truth for that mapping, shared by the source-level translator
 * ({@link ShaderTranslator#normalizeTypesForVulkanMod}) and VulkanMod's token-level parser (via
 * {@code GLSLParserMixin}), so the two paths cannot drift.</p>
 */
public final class VulkanModTypeNormalizer {

    /**
     * A boolean is expressed as a 32-bit int because VulkanMod has no boolean uniform type;
     * non-zero is true. Other GLSL types map to themselves and are not rewritten.
     */
    private static final java.util.Map<String, String> NORMALIZED_TYPES = java.util.Map.of(
            "bool", "int",
            "uint", "int",
            "bvec2", "ivec2",
            "bvec3", "ivec3",
            "bvec4", "ivec4",
            "uvec2", "ivec2",
            "uvec3", "ivec3",
            "uvec4", "ivec4"
    );

    private VulkanModTypeNormalizer() {
    }

    /**
     * @param declaredType a declared GLSL type token, e.g. {@code "bool"} or {@code "vec4"}
     * @return the VulkanMod-admitted equivalent ({@code "int"} for {@code "bool"}), or the input
     *         unchanged when it needs no rewriting (including {@code null})
     */
    public static String normalizeType(String declaredType) {
        if (declaredType != null && NORMALIZED_TYPES.containsKey(declaredType)) {
            return NORMALIZED_TYPES.get(declaredType);
        }
        return declaredType;
    }

    /**
     * @param declaredType a declared GLSL type token
     * @return whether VulkanMod's converter would reject this type
     */
    public static boolean isRejectedType(String declaredType) {
        return declaredType != null && NORMALIZED_TYPES.containsKey(declaredType);
    }
}
