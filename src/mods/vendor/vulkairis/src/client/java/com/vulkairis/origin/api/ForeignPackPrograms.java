package com.vulkairis.origin.api;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.bridge.VulkanModProgramBridge.ForeignProgram;
import com.vulkairis.origin.composite.IrisPassSource;
import com.vulkairis.origin.shadow.ShadowPass;
import com.vulkairis.origin.terrain.TerrainDraw;
import com.vulkairis.origin.terrain.TerrainVertexAdapter;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Origin (step 9): the shader pack's programs for geometry another mod draws itself under VulkanMod
 * (Flywheel's instanced meshes in {@code flywheel-vulkan}: Create and Trainworks cogs, shafts, belts).
 *
 * <p>The seam is deliberately small and typed only with JDK and VulkanMod classes, so the caller can
 * reach it by reflection and neither mod needs the other: the caller asks {@link #prepare} for a
 * role ({@link #ROLE_BLOCK}: colour and lighting, {@link #ROLE_SHADOW}: the shadow map) and gets the
 * pack's final GLSL with every descriptor in the set it asked for. Its own vertex shader must define
 * {@link TerrainVertexAdapter#FEED_SIGNATURE} (camera-relative position, uv, lightmap 0..255, colour,
 * normal); then it builds the pipeline, fills the uniform buffers with {@link #writeUniforms} and binds
 * {@link #image} per sampler. Shadow casters register once with {@link #addShadowCaster} and are called
 * inside Vulkairis's shadow pass, with the shadow map's render pass bound.</p>
 *
 * <p>Anything that can't be prepared returns {@code null} (logged once per pack): the caller keeps its
 * own unshaded drawing. Nothing here counts toward the bridge's link-failure breaker.</p>
 */
public final class ForeignPackPrograms {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Foreign");

    /** Bumped when the methods below change shape; callers refuse other versions. */
    public static final int API_VERSION = 1;
    public static final String ROLE_BLOCK = "block";
    public static final String ROLE_SHADOW = "shadow";
    /** {@link #samplerKind}: a pack image ({@link #image}), the block atlas, the lightmap. */
    public static final int SAMPLER_PACK = 0;
    public static final int SAMPLER_ATLAS = 1;
    public static final int SAMPLER_LIGHTMAP = 2;

    /** Pack programs that are registered for foreign draws only (never linked for VulkanMod's own draws). */
    private static final List<String> FOREIGN_ONLY = List.of("gbuffers_block");
    private static final Map<String, Integer> PROGRAM_IDS = new ConcurrentHashMap<>();
    /** role + "#" + set -> ForeignProgram, or FAILED. */
    private static final Map<String, Object> PREPARED = new ConcurrentHashMap<>();
    private static final Object FAILED = new Object();
    private static final List<Runnable> SHADOW_CASTERS = new CopyOnWriteArrayList<>();
    private static volatile int generation;

    private ForeignPackPrograms() {
    }

    /** True for a pack program Vulkairis registers only so a foreign draw can use it. */
    public static boolean wants(String programName) {
        return FOREIGN_ONLY.contains(programName);
    }

    /** IrisBridge: the registered (not necessarily linked) virtual program for a pack program name. */
    public static void noteProgram(String programName, int virtualProgramId) {
        if (programName != null && virtualProgramId > 0) {
            PROGRAM_IDS.put(programName, virtualProgramId);
        }
    }

    /** Pack change or reload: every prepared program is stale; callers compare {@link #generation}. */
    public static void reset() {
        PROGRAM_IDS.clear();
        PREPARED.clear();
        generation++;
    }

    /** Changes whenever prepared programs go stale; a caller rebuilds its pipelines when it moves. */
    public static int generation() {
        return generation;
    }

    /** True while a shader pack is loaded and Vulkairis is driving it. */
    public static boolean packActive() {
        return IrisPassSource.currentSet() != null;
    }

    /**
     * The pack program for {@code role} with every descriptor in set {@code set}; {@code null} when no
     * pack is active or it can't be prepared. Cached per pack generation, failures included.
     */
    public static Object prepare(String role, int set) {
        if (!packActive() || PROGRAM_IDS.isEmpty()) {
            // No pack, or its programs aren't registered yet: ask again later, nothing is cached.
            return null;
        }
        String key = role + "#" + set;
        Object cached = PREPARED.get(key);
        if (cached != null) {
            return cached == FAILED ? null : cached;
        }
        String[] candidates = ROLE_SHADOW.equals(role)
                ? new String[] {"shadow"}
                : new String[] {"gbuffers_block", "gbuffers_terrain"};
        StringBuilder reasons = new StringBuilder();
        for (String name : candidates) {
            Integer id = PROGRAM_IDS.get(name);
            if (id == null) {
                reasons.append(name).append(": not in pack; ");
                continue;
            }
            try {
                ForeignProgram p = VulkanModProgramBridge.prepareForeign(name, id, set);
                PREPARED.put(key, p);
                LOGGER.info("[Vulkairis/Foreign] {} role uses pack program {} ({} uniform blocks, {} samplers)",
                        role, name, p.uboBindings().length, p.samplerBindings().length);
                return p;
            } catch (Throwable t) {
                reasons.append(name).append(": ").append(t.getMessage()).append("; ");
            }
        }
        PREPARED.put(key, FAILED);
        LOGGER.warn("[Vulkairis/Foreign] no pack program for the {} role; the caller draws unshaded ({})",
                role, reasons);
        return null;
    }

    /** Reports a caller-side failure (its compile or pipeline build) so the role stays off for this pack. */
    public static void markFailed(String role, int set, String reason) {
        PREPARED.put(role + "#" + set, FAILED);
        LOGGER.warn("[Vulkairis/Foreign] {} role disabled for this pack: {}", role, reason);
    }

    public static String vertexSource(Object program) {
        return ((ForeignProgram) program).vertex();
    }

    public static String fragmentSource(Object program) {
        return ((ForeignProgram) program).fragment();
    }

    public static String programName(Object program) {
        return ((ForeignProgram) program).name();
    }

    /** Uniform buffer bindings, parallel to {@link #uboSizes} (bytes, std140). */
    public static int[] uboBindings(Object program) {
        return ((ForeignProgram) program).uboBindings();
    }

    public static int[] uboSizes(Object program) {
        return ((ForeignProgram) program).uboSizes();
    }

    /** Combined image sampler bindings, parallel to {@link #samplerKind} and {@link #image}'s index. */
    public static int[] samplerBindings(Object program) {
        return ((ForeignProgram) program).samplerBindings();
    }

    public static int samplerKind(Object program, int index) {
        return switch (((ForeignProgram) program).samplerNames()[index]) {
            case "gtexture", "texture", "tex" -> SAMPLER_ATLAS;
            case "lightmap" -> SAMPLER_LIGHTMAP;
            default -> SAMPLER_PACK;
        };
    }

    /** True when sampler {@code index} is a depth-compare sampler ({@code sampler2DShadow}). */
    public static boolean samplerCompare(Object program, int index) {
        return ((ForeignProgram) program).samplerCompare()[index];
    }

    /**
     * The image a {@link #SAMPLER_PACK} sampler means right now (shadow map, colortex, custom
     * texture); {@code null} for anything Vulkairis has no image for (bind white).
     */
    public static VulkanImage image(Object program, int index) {
        ForeignProgram p = (ForeignProgram) program;
        return TerrainDraw.packImage(p.samplerNames()[index], p.samplerCompare()[index]);
    }

    /**
     * Fills uniform buffer {@code binding} for a draw now (main view, or the sun's view inside the
     * shadow pass). {@code buf} is the caller's mapped memory, {@code size} bytes from position 0.
     */
    public static void writeUniforms(Object program, int binding, ByteBuffer buf, int size) {
        VulkanModProgramBridge.writeForeignUniforms((ForeignProgram) program, binding, buf, size,
                TerrainDraw.matrixOverrides());
    }

    /** True while Vulkairis's shadow pass is drawing (the shadow map's render pass is bound). */
    public static boolean shadowPassActive() {
        return ShadowPass.isActive();
    }

    public static void addShadowCaster(Runnable caster) {
        if (caster != null && !SHADOW_CASTERS.contains(caster)) {
            SHADOW_CASTERS.add(caster);
        }
    }

    public static void removeShadowCaster(Runnable caster) {
        SHADOW_CASTERS.remove(caster);
    }

    /** ShadowPass: runs every caster inside the shadow pass; a failing caster is dropped, never fatal. */
    public static void drawShadowCasters() {
        for (Runnable caster : SHADOW_CASTERS) {
            try {
                caster.run();
            } catch (Throwable t) {
                SHADOW_CASTERS.remove(caster);
                LOGGER.error("[Vulkairis/Foreign] shadow caster failed and was removed: {}", t.toString(), t);
            }
        }
    }
}
