package com.vulkairis.compatibility;

import com.vulkairis.iris.ShaderPackProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Distant Horizons presence gate and graceful-degrade policy (DH degrade pass).
 *
 * <p>Complementary-style packs ship {@code dh_terrain}/{@code dh_water} programs whose sources
 * reference Iris DH uniforms and samplers ({@code dhProjection},
 * {@code dhProjectionInverse}, {@code dhMaterialId}, {@code dhDepthTex},
 * {@code dhDepthTex1}, ...). Without the Distant Horizons mod those identifiers are never
 * declared, so every compilation attempt fails and each failure is logged as a full shaderc
 * error dump plus an analyzer warning (the {@code dh_terrain/dh_water} vert+frag spam in the
 * runClient log).</p>
 *
 * <p>Behavior mirrors the Vitrail guideline (observed, not copied): Vitrail only takes over
 * the distant path when its DH bridge reports usable — i.e. the DH mod is reflectively
 * present — and otherwise leaves far terrain alone instead of compiling {@code dh_*}.
 * Vulkairis does the same: {@link #isAvailable()} is true only when a DH mod is loaded, and
 * when it is false every {@code dh_*} program is skipped with one labeled reason instead of
 * being compiled. Full DH execution stays Phase G work; this class only degrades cleanly.</p>
 *
 * <p>No stub declarations are emitted for the DH identifiers: inventing projection matrices
 * or depth samplers would silently corrupt depth and lighting, the same reason the inverse
 * matrices stay compiler-reported in {@code LegacyGlsl}. Absent-mod programs are rejected
 * with a reason, never faked.</p>
 */
public final class DistantHorizonsSupport {

    /**
     * DH identifiers observed in {@code ComplementaryReimagined_r5.9.3} {@code dh_terrain} /
     * {@code dh_water} sources (plus the {@code dhDepthTexN} sampler family). A source
     * referencing any of them requires the DH mod at compile time.
     */
    private static final Pattern DH_IDENTIFIER = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:dhProjectionInverse|dhProjection|dhModelViewInverse|dhModelView"
                    + "|dhMaterialId|dhDepthTex\\d*)(?![A-Za-z0-9_])");

    /** Fabric mod ids that provide Distant Horizons (canonical id plus hyphenated spelling). */
    private static final String[] DH_MOD_IDS = {"distanthorizons", "distant_horizons"};

    /** Short machine-stable reason head used by every DH-skip diagnostic. */
    public static final String SKIP_HEAD = "Distant Horizons program skipped (DH mod absent)";

    private static volatile Boolean availableOverrideForTests = null;

    private DistantHorizonsSupport() {
    }

    /**
     * Whether the Distant Horizons mod is loaded and its far-terrain path could execute.
     * Headless-safe: any loader failure (tests, menus, early startup) reads as absent.
     *
     * @return {@code true} only when a DH mod id is present (or the test override says so)
     */
    public static boolean isAvailable() {
        Boolean override = availableOverrideForTests;
        if (override != null) {
            return override;
        }
        try {
            net.fabricmc.loader.api.FabricLoader loader =
                    net.fabricmc.loader.api.FabricLoader.getInstance();
            for (String id : DH_MOD_IDS) {
                if (loader.isModLoaded(id)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Forces {@link #isAvailable()} for tests; {@code null} restores live detection. */
    public static void setAvailableForTests(Boolean available) {
        availableOverrideForTests = available;
    }

    /**
     * Whether {@code programName} is a distant-horizons program by Iris naming convention
     * ({@code dh_terrain}, {@code dh_water}, ...; case-insensitive).
     */
    public static boolean isDistantProgramName(String programName) {
        return programName != null
                && programName.toLowerCase(java.util.Locale.ROOT).startsWith("dh_");
    }

    /**
     * Whether {@code program} is a distant-horizons program: Iris {@code Dh} group membership
     * or the {@code dh_} naming convention.
     */
    public static boolean isDistantProgram(ShaderPackProgram program) {
        if (program == null) {
            return false;
        }
        try {
            if (program.group() == net.irisshaders.iris.shaderpack.loading.ProgramGroup.Dh) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return isDistantProgramName(program.name());
    }

    /**
     * Whether {@code source} references DH-only identifiers and therefore requires the DH mod
     * to compile. Null/blank-safe; token-boundary matched so identifiers merely containing a
     * DH name (for example {@code mydhProjection}) never match.
     */
    public static boolean requiresDistantHorizons(String source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        return DH_IDENTIFIER.matcher(source).find();
    }

    /**
     * Whether the named shader stage should be skipped: DH mod absent and the stage is either
     * named like a DH program or its source requires DH identifiers.
     */
    public static boolean shouldSkipShader(String shaderName, String source) {
        if (isAvailable()) {
            return false;
        }
        return isDistantProgramName(shaderName) || requiresDistantHorizons(source);
    }

    /**
     * Whether the whole program should be skipped: DH mod absent and either the program name
     * is distant or every stage source requires DH identifiers. Mixed programs (never
     * observed) keep the existing per-stage behavior instead of being blanket-skipped.
     */
    public static boolean shouldSkipProgram(String programName,
                                            List<ProgramTranslatorStage> stages) {
        if (isAvailable() || stages == null || stages.isEmpty()) {
            return false;
        }
        if (isDistantProgramName(programName)) {
            return true;
        }
        for (ProgramTranslatorStage stage : stages) {
            if (stage == null || !requiresDistantHorizons(stage.source())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Minimal stage view so callers in any package can ask for a whole-program skip without
     * depending on {@code ProgramTranslator} internals.
     *
     * @param source the raw stage source; may be {@code null}
     */
    public record ProgramTranslatorStage(String source) {
    }

    /**
     * Canonical skip reason for one program or shader stage. Names the program, the cause
     * (DH mod absent), the degrade (vanilla far terrain keeps rendering) and the follow-up
     * (full DH execution is Phase G work).
     */
    public static String skipReason(String programName) {
        String name = (programName == null || programName.isBlank()) ? "unknown-program" : programName;
        return SKIP_HEAD + ": '" + name + "' requires the Distant Horizons mod, which is not loaded; "
                + "far terrain degrades to vanilla rendering and all other programs are unaffected "
                + "(full DH execution is Phase G work)";
    }

    /**
     * Single catalog-level note for the capability gate: how many DH programs were skipped
     * and that graphics families are unaffected (same shape as the compute partial note).
     */
    public static String gateNote(List<ShaderPackProgram> distantPrograms) {
        List<String> names = new ArrayList<>();
        if (distantPrograms != null) {
            for (ShaderPackProgram program : distantPrograms) {
                if (program != null) {
                    names.add(program.name());
                }
            }
        }
        String listed = names.isEmpty() ? "dh_*" : String.join(", ", names);
        return names.size() + " distant-horizons program(s) skipped (Distant Horizons mod absent: "
                + listed + " degrade to vanilla far terrain); graphics families are unaffected";
    }
}
