package com.vulkairis.rendering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 9: registry of the render families Vulkairis can route through a shader pack.
 *
 * <p>Each family declares whether it is currently shader-routed or base-rendered, its
 * shadow participation and the fallback policy when a program is missing or fails. The
 * registry is the single source of truth for the "family went through pack vs. base renderer"
 * trace required by the plan.</p>
 */
public final class RenderFamily {

    public enum ShadowParticipation { NONE, CAST, RECEIVE, CAST_AND_RECEIVE }

    public enum Route { SHADER, BASE }

    public record Family(String name, Route route, ShadowParticipation shadow,
                         String fallbackReason) {
    }

    private static final List<Family> FAMILIES = new ArrayList<>();

    static {
        reg("terrain_opaque", Route.SHADER, ShadowParticipation.CAST_AND_RECEIVE, "");
        reg("terrain_cutout", Route.SHADER, ShadowParticipation.CAST, "");
        reg("terrain_cutout_mipped", Route.SHADER, ShadowParticipation.CAST, "");
        reg("terrain_translucent", Route.SHADER, ShadowParticipation.RECEIVE, "");
        reg("water", Route.SHADER, ShadowParticipation.RECEIVE, "");
        reg("entities", Route.SHADER, ShadowParticipation.CAST, "");
        reg("block_entities", Route.BASE, ShadowParticipation.NONE, "modded renderers fall back to base");
        reg("hand", Route.SHADER, ShadowParticipation.NONE, "");
        reg("particles", Route.BASE, ShadowParticipation.NONE, "not yet shader-routed");
        reg("weather", Route.BASE, ShadowParticipation.NONE, "not yet shader-routed");
        reg("sky", Route.BASE, ShadowParticipation.NONE, "not yet shader-routed");
        reg("clouds", Route.BASE, ShadowParticipation.NONE, "not yet shader-routed");
    }

    private RenderFamily() {
    }

    private static void reg(String name, Route route, ShadowParticipation shadow, String fallback) {
        FAMILIES.add(new Family(name, route, shadow, fallback));
    }

    public static List<Family> all() {
        return Collections.unmodifiableList(FAMILIES);
    }

    public static Family find(String name) {
        for (Family f : FAMILIES) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public static List<Family> shaderRouted() {
        List<Family> out = new ArrayList<>();
        for (Family f : FAMILIES) {
            if (f.route() == Route.SHADER) {
                out.add(f);
            }
        }
        return out;
    }

    public static List<Family> baseRendered() {
        List<Family> out = new ArrayList<>();
        for (Family f : FAMILIES) {
            if (f.route() == Route.BASE) {
                out.add(f);
            }
        }
        return out;
    }
}