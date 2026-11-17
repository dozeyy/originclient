package com.vulkairis.rendering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 7: G-buffer layout specification.
 *
 * <p>Documents each attachment's channel format and precision so shader programs and
 * debug views agree on what every buffer contains.</p>
 */
public final class GBufferLayout {

    public record Attachment(String name, String format, int channels, String precision, String description) {
    }

    private static final List<Attachment> ATTACHMENTS = new ArrayList<>();

    static {
        add("colortex0", "RGBA8", 4, "8-bit", "Scene color");
        add("colortex1", "RGBA8", 4, "8-bit", "Specular/roughness");
        add("colortex2", "RGBA8", 4, "8-bit", "Normal (encoded)");
        add("depthtex0", "D32F", 1, "32-bit float", "Scene depth");
        add("depthtex1", "D32F", 1, "32-bit float", "Shadow depth");
        add("colortex3", "RGBA8", 4, "8-bit", "Lightmap/atlas");
    }

    private GBufferLayout() {
    }

    private static void add(String name, String format, int channels, String precision, String desc) {
        ATTACHMENTS.add(new Attachment(name, format, channels, precision, desc));
    }

    public static List<Attachment> all() {
        return Collections.unmodifiableList(new ArrayList<>(ATTACHMENTS));
    }

    public static Attachment find(String name) {
        for (Attachment a : ATTACHMENTS) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }
}