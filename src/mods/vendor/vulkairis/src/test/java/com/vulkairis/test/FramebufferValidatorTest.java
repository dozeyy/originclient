package com.vulkairis.test;

import com.vulkairis.rendering.FramebufferValidator;
import com.vulkairis.rendering.state.ResourceBindings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Phase 8: framebuffer status reflects real attachment validity instead of fabricated
 * completeness.
 */
public class FramebufferValidatorTest {

    @Test
    public void testEmptyFramebufferIsMissingAttachments() {
        FramebufferValidator.Verdict verdict =
                FramebufferValidator.validate(1, Map.of());
        Assertions.assertFalse(verdict.complete());
        Assertions.assertEquals(FramebufferValidator.MISSING_ATTACHMENT, verdict.glStatus());
        Assertions.assertEquals(36055, verdict.glStatus());
        Assertions.assertFalse(verdict.reason().isBlank());
    }

    @Test
    public void testNullAttachmentsAreMissing() {
        FramebufferValidator.Verdict verdict = FramebufferValidator.validate(1, null);
        Assertions.assertFalse(verdict.complete());
        Assertions.assertEquals(FramebufferValidator.MISSING_ATTACHMENT, verdict.glStatus());
    }

    @Test
    public void testZeroTextureAttachmentIsBroken() {
        Map<Integer, ResourceBindings.FramebufferAttachment> attachments = Map.of(
                36064, new ResourceBindings.FramebufferAttachment(36064, 0, 0));
        FramebufferValidator.Verdict verdict = FramebufferValidator.validate(1, attachments);
        Assertions.assertFalse(verdict.complete());
        Assertions.assertEquals(FramebufferValidator.BAD_ATTACHMENT, verdict.glStatus());
        Assertions.assertEquals(36057, verdict.glStatus());
        Assertions.assertTrue(verdict.reason().contains("36064"),
                "the broken attachment point must be named: " + verdict.reason());
    }

    @Test
    public void testBoundAttachmentsAreComplete() {
        Map<Integer, ResourceBindings.FramebufferAttachment> attachments = Map.of(
                36064, new ResourceBindings.FramebufferAttachment(36064, 7, 0),
                36065, new ResourceBindings.FramebufferAttachment(36065, 9, 0));
        FramebufferValidator.Verdict verdict = FramebufferValidator.validate(1, attachments);
        Assertions.assertTrue(verdict.complete());
        Assertions.assertEquals(FramebufferValidator.COMPLETE, verdict.glStatus());
        Assertions.assertEquals(36053, verdict.glStatus());
    }
}
