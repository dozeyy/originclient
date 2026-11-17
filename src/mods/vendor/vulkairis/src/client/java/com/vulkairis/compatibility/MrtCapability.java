package com.vulkairis.compatibility;

/**
 * Phase 7: multi-render-target capability decision record.
 *
 * <p>Documents whether the active backend can satisfy a pack's DRAWBUFFERS/MRT
 * requirements, and, when it cannot, the tested multipass alternative that is used
 * instead. Unsupported outputs are reported rather than silently dropped.</p>
 */
public final class MrtCapability {

    public enum Strategy { NATIVE_MRT, MULTIPASS_FALLBACK, UNSUPPORTED }

    private final int supportedColorAttachments;
    private final Strategy strategy;
    private final String note;

    public MrtCapability(int supportedColorAttachments, Strategy strategy, String note) {
        this.supportedColorAttachments = Math.max(0, supportedColorAttachments);
        this.strategy = strategy == null ? Strategy.UNSUPPORTED : strategy;
        this.note = note == null ? "" : note;
    }

    public int supportedColorAttachments() {
        return supportedColorAttachments;
    }

    public Strategy strategy() {
        return strategy;
    }

    public String note() {
        return note;
    }

    public boolean canSatisfy(int required) {
        return required <= supportedColorAttachments
                || strategy == Strategy.MULTIPASS_FALLBACK;
    }
}