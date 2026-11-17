package com.vulkairis.resource;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic target viewer (Phase 8).
 *
 * <p>Renders the registry as inspectable text: dimensions, formats, layouts, backing and
 * clear state, producer/consumer wiring and byte budgets. Per-pixel color/depth <em>values</em>
 * need GPU readback on a live device and are outside this viewer; dimensions, formats and
 * clear state — the data behind stale-frame and menu-square triage — are always available.
 * Reach it in-game via {@code /vulkairis targets}; pixel-value views land with live-device
 * readback.</p>
 */
public final class TargetViewer {

    private TargetViewer() {
    }

    /** @return one line per target plus a summary line */
    public static List<String> describe(RenderTargetRegistry registry) {
        List<String> lines = new ArrayList<>();
        if (registry == null || registry.size() == 0) {
            lines.add("no render targets registered");
            return lines;
        }
        long totalBytes = 0L;
        for (TextureResource target : registry.all()) {
            totalBytes += target.getSpec().sizeInBytes();
            lines.add(target.getSpec().resource().name()
                    + " " + target.getSpec().width() + "x" + target.getSpec().height()
                    + " " + target.getFormat()
                    + " layout=" + layoutName(target.getCurrentLayout())
                    + " backing=" + (target.isBackingCreated() ? "yes" : "no")
                    + " cleared=" + (target.isCleared() ? "yes" : "no")
                    + (target.needsClear() ? " NEEDS-CLEAR" : "")
                    + " producer=" + (target.getProducerPass() != null
                            ? target.getProducerPass() : "-")
                    + " consumers=" + target.getConsumerPasses());
        }
        lines.add(registry.size() + " target(s), ~" + totalBytes + " bytes");
        return lines;
    }

    /** @return a one-line summary of the registry */
    public static String summary(RenderTargetRegistry registry) {
        if (registry == null || registry.size() == 0) {
            return "targets: none";
        }
        long pending = registry.all().stream().filter(TextureResource::needsClear).count();
        return "targets: " + registry.size() + ", " + registry.getWidth()
                + "x" + registry.getHeight() + ", " + pending + " needing clear";
    }

    private static String layoutName(int layout) {
        return switch (layout) {
            case 0 -> "UNDEFINED";
            case 1 -> "GENERAL";
            case 2 -> "COLOR_ATTACHMENT_OPTIMAL";
            case 3 -> "DEPTH_STENCIL_ATTACHMENT_OPTIMAL";
            case 5 -> "SHADER_READ_ONLY_OPTIMAL";
            case 6 -> "TRANSFER_SRC_OPTIMAL";
            case 7 -> "TRANSFER_DST_OPTIMAL";
            default -> "0x" + Integer.toHexString(layout);
        };
    }
}
