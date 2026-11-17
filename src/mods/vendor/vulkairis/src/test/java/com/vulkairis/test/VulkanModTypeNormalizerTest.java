package com.vulkairis.test;

import com.vulkairis.translation.VulkanModTypeNormalizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the VulkanMod shader-converter integration: Iris injects boolean uniform
 * members, and VulkanMod's {@code Uniform.createUniformInfo} throws
 * {@code RuntimeException("not admitted type: bool")} on them, which aborted every shadow/gbuffer
 * program during live pipeline creation. Both the source-level translator and VulkanMod's token
 * parser must map {@code bool} to an admitted {@code int}.
 */
public class VulkanModTypeNormalizerTest {

    @Test
    public void testBoolNormalizesToInt() {
        Assertions.assertEquals("int", VulkanModTypeNormalizer.normalizeType("bool"));
        Assertions.assertTrue(VulkanModTypeNormalizer.isRejectedType("bool"));
    }

    @Test
    public void testAdmittedTypesAreUnchanged() {
        for (String type : new String[] {"vec2", "vec3", "vec4", "mat4", "float", "int", "sampler2D"}) {
            Assertions.assertEquals(type, VulkanModTypeNormalizer.normalizeType(type),
                    type + " must not be rewritten");
            Assertions.assertFalse(VulkanModTypeNormalizer.isRejectedType(type));
        }
    }

    @Test
    public void testNullAndSubstringsAreSafe() {
        Assertions.assertNull(VulkanModTypeNormalizer.normalizeType(null));
        Assertions.assertFalse(VulkanModTypeNormalizer.isRejectedType(null));
        // A longer identifier is not the bool type and must be preserved.
        Assertions.assertEquals("booleanValue", VulkanModTypeNormalizer.normalizeType("booleanValue"));
        Assertions.assertEquals("bools", VulkanModTypeNormalizer.normalizeType("bools"));
        Assertions.assertFalse(VulkanModTypeNormalizer.isRejectedType("bools"));
    }
}
