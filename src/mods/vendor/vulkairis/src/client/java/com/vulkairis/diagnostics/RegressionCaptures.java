package com.vulkairis.diagnostics;

import java.util.List;
import java.util.Map;

/**
 * Named visual regressions with fixed capture parameters (Phase 1).
 *
 * <p>Both failure cases reported against the prototype are tracked here so a future capture run
 * reproduces them deterministically: fixed world seed and camera, fixed resolution and GUI scale,
 * fixed time/weather and graphics settings, plus an Iris+OpenGL reference capture of the same
 * scene. Evidence status stays {@link EvidenceStatus#PENDING} until real screenshots and logs
 * from both renderers are recorded.
 */
public final class RegressionCaptures {

    private RegressionCaptures() {
    }

    public enum EvidenceStatus {
        PENDING,
        CAPTURED
    }

    public record CaptureSpec(
            String id,
            String description,
            Map<String, String> fixedParameters,
            EvidenceStatus evidence) {
    }

    /** Square/panel artifact in a menu before (or without) an active shader pack. */
    public static final CaptureSpec MENU_SQUARE_NO_PACK = new CaptureSpec(
            "menu-square-no-pack",
            "Unexplained square/panel in a menu with no shader pack active",
            Map.of(
                    "worldSeed", "vulkairis-regression-1",
                    "camera", "menu screen, no world loaded",
                    "resolution", "1920x1080",
                    "guiScale", "2",
                    "time", "n/a (menu)",
                    "graphics", "Fancy, renderDistance=8",
                    "shaderPack", "none selected",
                    "reference", "same menu on Iris+OpenGL with Vulkairis disabled"),
            EvidenceStatus.PENDING);

    /** Bright/glowing outline around blocks after pack activation. */
    public static final CaptureSpec BRIGHT_BLOCK_OUTLINES = new CaptureSpec(
            "bright-block-outlines",
            "Bright outline around blocks after shader-pack activation",
            Map.of(
                    "worldSeed", "vulkairis-regression-1",
                    "camera", "pos=(0.5, 70.0, 0.5) yaw=0.0 pitch=0.0",
                    "resolution", "1920x1080",
                    "guiScale", "2",
                    "time", "6000 (noon)",
                    "weather", "clear",
                    "graphics", "Fancy, renderDistance=8",
                    "reference", "same scene on Iris+OpenGL"),
            EvidenceStatus.PENDING);

    /** @return every tracked regression, in triage order */
    public static List<CaptureSpec> all() {
        return List.of(MENU_SQUARE_NO_PACK, BRIGHT_BLOCK_OUTLINES);
    }
}
