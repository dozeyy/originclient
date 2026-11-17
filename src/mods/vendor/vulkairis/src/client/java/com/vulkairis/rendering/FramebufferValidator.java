package com.vulkairis.rendering;

import com.vulkairis.rendering.state.ResourceBindings;

import java.util.Map;

/**
 * Real framebuffer-status validation (Phase 8).
 *
 * <p>Replaces the unconditional {@code GL_FRAMEBUFFER_COMPLETE} answer with attachment checks
 * against the tracked binding state: a framebuffer with no attachments is missing attachments,
 * and an attachment point bound to texture id 0 is a broken attachment. Both report the GL
 * incomplete codes instead of fabricated success. Dimension/format/usage cross-checks against
 * allocated targets ride on the target descriptors once the texture-id-to-target association
 * exists (pass routing); until then they are validated at the registry level, where specs
 * already reject non-positive sizes and kind/format mismatches at construction.
 */
public final class FramebufferValidator {

    /** GL_FRAMEBUFFER_COMPLETE. */
    public static final int COMPLETE = 36053;
    /** GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT. */
    public static final int MISSING_ATTACHMENT = 36055;
    /** GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT. */
    public static final int BAD_ATTACHMENT = 36057;

    private FramebufferValidator() {
    }

    /** Validation outcome with the GL status code attached. */
    public record Verdict(boolean complete, int glStatus, String reason) {
        public static Verdict ok() {
            return new Verdict(true, COMPLETE, "framebuffer attachments valid");
        }

        public static Verdict problem(int glStatus, String reason) {
            return new Verdict(false, glStatus, reason);
        }
    }

    /**
     * Validates one framebuffer against tracked attachments.
     *
     * @param framebuffer the framebuffer id
     * @param attachments tracked attachment-point map (may be empty)
     */
    public static Verdict validate(
            int framebuffer, Map<Integer, ResourceBindings.FramebufferAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Verdict.problem(MISSING_ATTACHMENT,
                    "framebuffer " + framebuffer + " has no attachments");
        }
        for (Map.Entry<Integer, ResourceBindings.FramebufferAttachment> entry
                : attachments.entrySet()) {
            ResourceBindings.FramebufferAttachment attachment = entry.getValue();
            if (attachment == null || attachment.textureId() == 0) {
                return Verdict.problem(BAD_ATTACHMENT,
                        "framebuffer " + framebuffer + " attachment point " + entry.getKey()
                                + " is bound to texture 0");
            }
        }
        return Verdict.ok();
    }
}
