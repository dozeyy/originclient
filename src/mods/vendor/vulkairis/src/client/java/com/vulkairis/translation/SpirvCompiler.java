package com.vulkairis.translation;

import com.vulkairis.diagnostics.DiagnosticManager;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.lwjgl.util.shaderc.Shaderc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process GLSL to SPIR-V compiler using LWJGL Shaderc bindings (Phase 4, hardened Phase C).
 *
 * <p>Every compilation produces a {@link ShaderCompileResult}: status, diagnostics, source hash,
 * stage, bytecode (success only), the exact {@link ShadercProfile} that ran, and timing. The
 * result is pure data, separate from Vulkan object creation — a failed compilation never yields
 * bytecode and no valid {@code VkShaderModule} may be registered from it.
 *
 * <p>Only {@code VERTEX} and {@code FRAGMENT} stages compile. Tessellation, geometry and compute
 * stages are rejected with an explicit reason until their pipeline configuration, capability
 * check and render dispatch are implemented; they never produce a valid-looking shader.
 *
 * <p>Phase C hardening (no {@code OutOfMemoryError: Out of stack space} on large packs):
 * <ul>
 *   <li>every {@code shaderc_compile_into_spv} call hands malloc-backed UTF-8 buffers
 *       to the native compiler (never the thread {@code MemoryStack}), so per-compile
 *       native scratch can never exhaust the thread stack no matter how large the
 *       expanded pack stage is;</li>
 *   <li>whole-source handoffs are bounded by {@link #MAX_SOURCE_CHARS}: shaderc requires
 *       the complete translation unit in one call, so an oversized source cannot be
 *       streamed — it is rejected with a structured result naming the limit;</li>
 *   <li>concurrent native compiles are capped by a fair semaphore
 *       ({@link #maxConcurrentCompiles()} slots), so parallel {@code vulkairis-compile-*}
 *       workers and direct callers can never pile up native compiler instances.</li>
 * </ul>
 *
 * <p>Exception discipline: expected compiler failures surface as failed results (never thrown
 * control flow). Truly unrecoverable JVM/natives failures ({@link Error}) take the defined
 * fatal path — structured record, bridge fallback, contained handle — and stay visible instead
 * of being swallowed.
 */
public class SpirvCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Compiler");

    /** GL stage ids accepted for compilation (plus the internal 1/2 vertex/fragment aliases). */
    private static final int GL_VERTEX_SHADER = 35633;
    private static final int GL_FRAGMENT_SHADER = 35632;

    /**
     * Largest GLSL source accepted for one bounded native handoff (512 KiB of characters).
     * Real pack stages are tens of KiB; anything beyond this is rejected with a structured
     * result instead of risking the native stack.
     */
    public static final int MAX_SOURCE_CHARS = 512 * 1024;

    private static final int MAX_CONCURRENT =
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    /** Fair cap on simultaneous native shaderc instances (see class javadoc). */
    private static final Semaphore COMPILE_SLOTS = new Semaphore(MAX_CONCURRENT, true);
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);
    private static final AtomicInteger MAX_OBSERVED_CONCURRENCY = new AtomicInteger(0);

    public static VulkairisShader compileShader(String name, String glslSource, ShaderType shaderType) {
        String rejection = rejectionReason(shaderType);
        if (rejection != null) {
            return rejectedShader(name, shaderType != null ? shaderType.name() : "null", rejection);
        }
        int stage = (shaderType != null) ? shaderType.id : 0;
        int shadercKind = mapShaderTypeToShaderc(shaderType);
        return compileShaderInternal(name, glslSource, stage, shadercKind);
    }

    public static VulkairisShader compileShader(String name, String glslSource, int stage) {
        int shadercKind = mapStageIntToShaderc(stage);
        return compileShaderInternal(name, glslSource, stage, shadercKind);
    }

    public static int compileAndRegister(String name, String glslSource, ShaderType shaderType) {
        return compileShader(name, glslSource, shaderType).getVirtualId();
    }

    public static int compileAndRegister(String name, String glslSource, int stage) {
        return compileShader(name, glslSource, stage).getVirtualId();
    }

    /**
     * Legacy throwing form: returns validated bytecode or throws {@link RuntimeException} with
     * the compiler log. Preserved for existing callers; new code should use
     * {@link #compileGlslToSpirvDetailed(String, String, int)}.
     */
    public static ByteBuffer compileGlslToSpirv(String name, String glslSource, int shadercKind) {
        ShaderCompileResult result = compileGlslToSpirvDetailed(name, glslSource, shadercKind);
        if (!result.succeeded()) {
            throw new RuntimeException("GLSL compilation failed for '" + name + "': "
                    + result.compilerLog());
        }
        return result.spirv();
    }

    /**
     * Compiles GLSL to validated SPIR-V without throwing for expected failures.
     *
     * @return the compile result; bytecode present only on {@code SUCCESS}
     * @throws Error on unrecoverable natives/JVM failures, after recording and fallback
     */
    public static ShaderCompileResult compileGlslToSpirvDetailed(
            String name, String glslSource, int shadercKind) {
        return compileGlslToSpirvDetailed(name, glslSource, shadercKind, null);
    }

    /**
     * Compiles GLSL to validated SPIR-V without throwing for expected failures, with
     * preprocessor defines injected after the {@code #version} line (deterministic order).
     *
     * <p>The compile cache key covers source, stage, defines, profile and dependency
     * versions, so a define change can never reuse a stale module.
     *
     * @param defines optional {@code name -> value} defines (may be {@code null}/empty)
     * @return the compile result; bytecode present only on {@code SUCCESS}
     * @throws Error on unrecoverable natives/JVM failures, after recording and fallback
     */
    public static ShaderCompileResult compileGlslToSpirvDetailed(
            String name, String glslSource, int shadercKind, Map<String, String> defines) {
        String shader = name != null ? name : "shader";
        String effectiveSource = injectDefines(glslSource, defines);
        String sourceHash = DiagnosticManager.hashSource(effectiveSource);
        String profileDigest = ShadercProfile.DEFAULT.digest();
        String definesDigest = ShaderCompileCache.definesDigest(defines);
        String versionStamp = ShaderCompileCache.versionStamp();
        long started = System.nanoTime();

        String kindRejection = rejectionReason(shadercKind);
        if (kindRejection != null) {
            return ShaderCompileResult.failure(ShaderCompileResult.Status.STAGE_REJECTED,
                    shader, 0, shadercKind, sourceHash, kindRejection, profileDigest,
                    elapsedMs(started), false);
        }

        // DH degrade pass: without the Distant Horizons mod, dh_* sources reference
        // identifiers that can never be declared. Reject before the native call so the
        // warmup worker, the unified recompile and direct callers all get a quiet labeled
        // failure instead of a shaderc error dump; non-DH sources are unaffected.
        if (com.vulkairis.compatibility.DistantHorizonsSupport.shouldSkipShader(shader, effectiveSource)) {
            return ShaderCompileResult.failure(ShaderCompileResult.Status.STAGE_REJECTED,
                    shader, 0, shadercKind, sourceHash,
                    com.vulkairis.compatibility.DistantHorizonsSupport.skipReason(shader),
                    profileDigest, elapsedMs(started), false);
        }

        if (effectiveSource != null && effectiveSource.length() > MAX_SOURCE_CHARS) {
            return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                    shader, 0, shadercKind, sourceHash,
                    "Source too large for a single bounded compile: " + effectiveSource.length()
                            + " chars exceeds the limit of " + MAX_SOURCE_CHARS + " chars; "
                            + "split the shader or reduce generated code",
                    profileDigest, elapsedMs(started), false);
        }

        // Phase C: identical compilations share validated bytecode under the full module key
        // (source + stage + defines + profile + versions). Only SUCCESS results are stored,
        // so invalid, oversized or unsupported stages never produce entries.
        String compileKey = ShaderCompileCache.key(
                sourceHash, shadercKind, definesDigest, profileDigest, versionStamp);
        ByteBuffer cachedSpirv = ShaderCompileCache.lookup(compileKey);
        if (cachedSpirv != null) {
            return ShaderCompileResult.success(shader, 0, shadercKind, sourceHash,
                    cachedSpirv, profileDigest, elapsedMs(started));
        }

        boolean slotAcquired = false;
        try {
            try {
                COMPILE_SLOTS.acquire();
                slotAcquired = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                        shader, 0, shadercKind, sourceHash,
                        "Interrupted while waiting for a bounded compile slot",
                        profileDigest, elapsedMs(started), false);
            }
            int inFlight = IN_FLIGHT.incrementAndGet();
            MAX_OBSERVED_CONCURRENCY.accumulateAndGet(inFlight, Math::max);
            try {
                return compileWithSlot(shader, effectiveSource, shadercKind,
                        sourceHash, profileDigest, compileKey, started);
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        } finally {
            if (slotAcquired) {
                COMPILE_SLOTS.release();
            }
        }
    }

    /**
     * Runs one native compilation while holding a compile slot. The caller owns slot
     * accounting; this method owns compiler/options lifetimes.
     */
    private static ShaderCompileResult compileWithSlot(
            String shader, String effectiveSource, int shadercKind,
            String sourceHash, String profileDigest, String compileKey, long started) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                    shader, 0, shadercKind, sourceHash,
                    "Failed to initialize Shaderc compiler", profileDigest,
                    elapsedMs(started), false);
        }

        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                    shader, 0, shadercKind, sourceHash,
                    "Failed to initialize Shaderc compile options", profileDigest,
                    elapsedMs(started), false);
        }

        try {
            ShadercProfile.DEFAULT.configure(options);

            // Heap handoff, never the thread MemoryStack: the String overload of
            // shaderc_compile_into_spv encodes the whole translation unit on the
            // caller's thread-local stack, and pack stages expanded to tens of
            // thousands of lines exhaust it ("Out of stack space"), failing the
            // pack on the render thread. malloc-backed buffers have no such cap.
            //
            // <p>Size discipline matters: the native entry takes an explicit
            // source size, so the source buffer carries the exact bytes with NO
            // terminator counted in (a counted trailing NUL surfaces in glslang
            // as a stray '' token and fails every compilation). The file/entry
            // names are plain NUL-terminated C strings, so they keep theirs.
            long result;
            java.nio.ByteBuffer sourceUtf8 = null;
            java.nio.ByteBuffer nameUtf8 = null;
            java.nio.ByteBuffer entryUtf8 = null;
            try {
                sourceUtf8 = org.lwjgl.system.MemoryUtil.memUTF8(effectiveSource, false);
                nameUtf8 = org.lwjgl.system.MemoryUtil.memUTF8(shader);
                entryUtf8 = org.lwjgl.system.MemoryUtil.memUTF8("main");
                result = Shaderc.shaderc_compile_into_spv(
                        compiler,
                        sourceUtf8,
                        shadercKind,
                        nameUtf8,
                        entryUtf8,
                        options
                );
            } finally {
                if (sourceUtf8 != null) {
                    org.lwjgl.system.MemoryUtil.memFree(sourceUtf8);
                }
                if (nameUtf8 != null) {
                    org.lwjgl.system.MemoryUtil.memFree(nameUtf8);
                }
                if (entryUtf8 != null) {
                    org.lwjgl.system.MemoryUtil.memFree(entryUtf8);
                }
            }

            if (result == 0L) {
                return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                        shader, 0, shadercKind, sourceHash,
                        "Shaderc returned null result pointer", profileDigest,
                        elapsedMs(started), false);
            }

            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                String message = Shaderc.shaderc_result_get_error_message(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    LOGGER.error("[Vulkairis] GLSL -> SPIR-V compilation failed for '{}':\n{}",
                            shader, message);
                    return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                            shader, 0, shadercKind, sourceHash, message, profileDigest,
                            elapsedMs(started), false);
                }

                ByteBuffer spirvBytes = Shaderc.shaderc_result_get_bytes(result);
                if (spirvBytes == null || !spirvBytes.hasRemaining()) {
                    return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                            shader, 0, shadercKind, sourceHash,
                            "Empty SPIR-V bytecode produced", profileDigest,
                            elapsedMs(started), false);
                }

                ByteBuffer directCopy = ByteBuffer.allocateDirect(spirvBytes.remaining());
                directCopy.put(spirvBytes);
                directCopy.flip();

                SpirvValidator.ValidationResult validation = SpirvValidator.validate(directCopy);
                if (!validation.valid()) {
                    return ShaderCompileResult.failure(
                            ShaderCompileResult.Status.VALIDATION_FAILED,
                            shader, 0, shadercKind, sourceHash, validation.detail(),
                            profileDigest, elapsedMs(started), false);
                }
                ShaderCompileCache.store(compileKey, directCopy);
                ShaderCompileResult success = ShaderCompileResult.success(shader, 0, shadercKind,
                        sourceHash, directCopy, profileDigest, elapsedMs(started));
                if (message != null && !message.isBlank()) {
                    LOGGER.info("[Vulkairis] Shaderc warnings for '{}':\n{}", shader, message);
                }
                return success;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } catch (RuntimeException e) {
            return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                    shader, 0, shadercKind, sourceHash,
                    e.getMessage() != null ? e.getMessage() : e.toString(),
                    profileDigest, elapsedMs(started), false);
        } catch (OutOfMemoryError | StackOverflowError stackExhaustion) {
            // Native/JVM stack exhaustion (e.g. MemoryStack "Out of stack space" on a
            // huge pack stage) is a per-shader resource failure, not a bridge-wide
            // fatal: contain it as a compile error so the pack degrades program by
            // program instead of forcing fallback and rejecting everything.
            String detail = "Shader too large for a single native compile (" + stackExhaustion + "); "
                    + "reduce generated code or split the program";
            LOGGER.warn("[Vulkairis] Contained stack exhaustion for '{}': {}", shader, detail);
            return ShaderCompileResult.failure(ShaderCompileResult.Status.COMPILE_ERROR,
                    shader, 0, shadercKind, sourceHash, detail,
                    profileDigest, elapsedMs(started), false);
        } catch (Error fatal) {
            // Defined fatal path: record with the cause preserved, move the bridge to fallback,
            // then let the error propagate — an unrecoverable natives/JVM failure must stay visible.
            DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                    DiagnosticManager.Phase.COMPILE,
                    "Unrecoverable compiler failure", fatal.toString(), fatal,
                    shader, null, null, -1, sourceHash,
                    "Bridge moved to fallback; restart the client and check native libraries");
            com.vulkairis.core.VulkairisController.getInstance()
                    .triggerFallback("Unrecoverable compiler failure", fatal.toString());
            throw fatal;
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static VulkairisShader compileShaderInternal(
            String name, String glslSource, int stage, int shadercKind) {
        LOGGER.info("[Vulkairis] Compiling shader '{}' (stage: {}, kind: {})...", name, stage, shadercKind);
        com.vulkairis.diagnostics.StageDiagnostics.intercepted(name, String.valueOf(stage));

        String stageRejection = isSupportedGlStage(stage) && isSupportedShadercKind(shadercKind)
                ? null
                : rejectionReason("stage " + stage + " / kind " + shadercKind);
        if (stageRejection != null) {
            return rejectedShader(name, String.valueOf(stage), stageRejection);
        }

        // DH degrade pass (runtime intercept path): without the Distant Horizons mod the
        // dh_* sources can never compile, so return the contained rejected handle with the
        // labeled reason instead of running translation plus shaderc and dumping errors.
        if (com.vulkairis.compatibility.DistantHorizonsSupport.shouldSkipShader(name, glslSource)) {
            return rejectedShader(name, String.valueOf(stage),
                    com.vulkairis.compatibility.DistantHorizonsSupport.skipReason(name));
        }

        ByteBuffer spirv = null;
        ShaderReflection reflection = new ShaderReflection();
        String failureLog = null;
        try {
            boolean isVertex = (shadercKind == Shaderc.shaderc_glsl_vertex_shader);
            String preprocessed = ShaderTranslator.preprocessGlsl(glslSource, isVertex, name);
            com.vulkairis.diagnostics.StageDiagnostics.pass("GLSL preprocessing", name);
            com.vulkairis.iris.SourceProvenance.recordTranslation(
                    com.vulkairis.iris.IrisBridge.getCurrentPackName(),
                    name, String.valueOf(stage), preprocessed);
            spirv = compileGlslToSpirv(name, preprocessed, shadercKind);
            com.vulkairis.diagnostics.StageDiagnostics.pass("SPIR-V compilation", name + ", " + spirv.remaining() + " bytes");
            reflection = SpirvReflector.reflect(spirv);
            String reflectionSummary = String.format(
                    "%d samplers, %d uniforms, %d vertex inputs, ubo=%d, push=%d",
                    reflection.getSamplers().size(), reflection.getUniforms().size(),
                    reflection.getVertexAttributes().size(), reflection.getUboBlockSize(),
                    reflection.getPushConstantSize());
            if (!reflection.isComplete()) {
                failureLog = name + ": reflection incomplete (" + reflectionSummary + ")";
                DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                        DiagnosticManager.Phase.REFLECT,
                        "SPIR-V reflection incomplete", reflectionSummary, null,
                        name, String.valueOf(stage), null, -1,
                        DiagnosticManager.hashSource(glslSource),
                        "Reflection cannot be trusted; the shader stays unusable");
                com.vulkairis.diagnostics.StageDiagnostics.fail(
                        "SPIR-V validation + reflection", name + " (incomplete)", null);
                com.vulkairis.core.VulkairisController.getInstance()
                        .setLastCompileError(name + ": reflection incomplete");
            } else {
                com.vulkairis.diagnostics.StageDiagnostics.pass("SPIR-V validation + reflection",
                        reflectionSummary);
            }
            com.vulkairis.diagnostics.DiagnosticDumps.dumpReflection(name, reflectionSummary);
        } catch (RuntimeException e) {
            LOGGER.warn("[Vulkairis] GLSL -> SPIR-V compilation failed for '{}': {}. Falling back to virtual handle.", name, e.getMessage());
            reflection.markIncomplete();
            failureLog = e.getMessage();
            com.vulkairis.diagnostics.StageDiagnostics.fail("SPIR-V compilation", name, e);
            // GUI plan Phase 4: the compile-diagnostics toggle gates the detailed record.
            // Status truth (stage diagnostics, controller error, info log) is unaffected.
            if (diagnosticsEnabled()) {
                DiagnosticManager.logStructured(
                        DiagnosticManager.Category.SHADER,
                        DiagnosticManager.Phase.COMPILE,
                        "SPIR-V compilation failed", e.getMessage(), e,
                        name, String.valueOf(stage), null, -1,
                        DiagnosticManager.hashSource(glslSource),
                        "Fix the reported GLSL error or disable the affected shaderpack program");
            }
            com.vulkairis.core.VulkairisController.getInstance()
                    .setLastCompileError(name + ": " + e.getMessage());
        } catch (Error fatal) {
            // Defined fatal path: record with the cause preserved and move the bridge to
            // fallback, then contain with a fallback handle (same containment as failures).
            failureLog = fatal.toString();
            DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                    DiagnosticManager.Phase.COMPILE,
                    "Unrecoverable compiler failure", fatal.toString(), fatal,
                    name, String.valueOf(stage), null, -1,
                    DiagnosticManager.hashSource(glslSource),
                    "Bridge moved to fallback; restart the client and check native libraries");
            com.vulkairis.core.VulkairisController.getInstance()
                    .triggerFallback("Unrecoverable compiler failure", fatal.toString());
            reflection.markIncomplete();
        }
        // Lifecycle: no VulkairisShader object exists during compilation, so the states are derived
        // when the shader is constructed below -- SPIRV_VALID from real bytecode, REFLECTED when the
        // reflection is complete, and MODULE_CREATED when a live VkShaderModule was produced.
        // A failed compilation registers only a contained fallback handle (module 0, incomplete
        // reflection): it is never valid and never marked ready. The outcome is retained as the
        // shader info log so status queries report it instead of fabricated success.
        int virtualId = VulkanShaderRegistry.registerShader(name, stage, spirv, glslSource, reflection);
        if (spirv != null && spirv.hasRemaining() && reflection.isComplete()) {
            VulkanShaderRegistry.setShaderInfoLog(virtualId, "");
        } else if (failureLog != null && !failureLog.isBlank()) {
            VulkanShaderRegistry.setShaderInfoLog(virtualId, failureLog);
        } else if (VulkanShaderRegistry.getShaderInfoLog(virtualId).isEmpty()) {
            VulkanShaderRegistry.setShaderInfoLog(virtualId,
                    "compilation of '" + name + "' failed; see diagnostics for the compiler log");
        }
        return VulkanShaderRegistry.getShader(virtualId);
    }

    /**
     * Contained rejection for unsupported stages: records the reason, notes the last compile
     * error, and registers only a non-valid fallback handle. No pipeline may be built from it.
     */
    private static VulkairisShader rejectedShader(String name, String stage, String reason) {
        LOGGER.warn("[Vulkairis] {}", reason);
        DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                DiagnosticManager.Phase.COMPILE,
                "Shader stage rejected", reason, null,
                name, stage, null, -1, null,
                "Use only VERTEX/FRAGMENT programs until the stage is implemented");
        com.vulkairis.core.VulkairisController.getInstance().setLastCompileError(name + ": " + reason);
        ShaderReflection incomplete = new ShaderReflection();
        incomplete.markIncomplete();
        int virtualId = VulkanShaderRegistry.registerShader(name, 0, null, null, incomplete);
        VulkanShaderRegistry.setShaderInfoLog(virtualId, reason);
        return VulkanShaderRegistry.getShader(virtualId);
    }

    /** @return whether Iris may compile this shader type yet */
    public static boolean isSupportedShaderType(ShaderType type) {
        if (type == null) {
            return true;
        }
        return type == ShaderType.VERTEX || type == ShaderType.FRAGMENT;
    }

    /** @return whether this GL stage id may compile (vertex/fragment plus internal aliases) */
    public static boolean isSupportedGlStage(int stage) {
        return stage == GL_VERTEX_SHADER || stage == GL_FRAGMENT_SHADER || stage == 1 || stage == 2;
    }

    /** @return whether this shaderc kind may compile (vertex/fragment/inferred only) */
    public static boolean isSupportedShadercKind(int kind) {
        return kind == Shaderc.shaderc_glsl_vertex_shader
                || kind == Shaderc.shaderc_glsl_fragment_shader
                || kind == Shaderc.shaderc_glsl_infer_from_source;
    }

    /** @return the rejection reason, or {@code null} when the type is supported */
    public static String rejectionReason(ShaderType type) {
        if (isSupportedShaderType(type)) {
            return null;
        }
        return rejectionReason(type != null ? type.name() : "null");
    }

    /** @return the rejection reason, or {@code null} when the shaderc kind is supported */
    public static String rejectionReason(int shadercKind) {
        if (isSupportedShadercKind(shadercKind)) {
            return null;
        }
        return rejectionReason("shaderc kind " + shadercKind);
    }

    private static String rejectionReason(String what) {
        return "Shader stage " + what + " is not supported: tessellation/geometry/compute "
                + "pipeline configuration, capability check and render dispatch are not implemented; "
                + "only VERTEX and FRAGMENT stages compile";
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** @return the cap on simultaneous native shaderc instances (fair semaphore slots) */
    public static int maxConcurrentCompiles() {
        return MAX_CONCURRENT;
    }

    /** @return the highest simultaneously-held compile slot count observed so far */
    public static int maxObservedConcurrency() {
        return MAX_OBSERVED_CONCURRENCY.get();
    }

    /** Resets the observed-concurrency high-water mark; tests only. */
    public static void resetConcurrencyStatsForTests() {
        MAX_OBSERVED_CONCURRENCY.set(0);
    }

    /**
     * Injects {@code #define} lines for the given defines directly after the {@code #version}
     * line (or at the top when there is none), in sorted-name order so the effective source
     * — and therefore the cache key — is deterministic. Empty defines leave the source
     * byte-identical.
     */
    static String injectDefines(String source, Map<String, String> defines) {
        if (source == null || defines == null || defines.isEmpty()) {
            return source;
        }
        Map<String, String> ordered = new TreeMap<>();
        for (Map.Entry<String, String> entry : defines.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                ordered.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        if (ordered.isEmpty()) {
            return source;
        }
        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            block.append("#define ").append(entry.getKey())
                    .append(' ').append(entry.getValue()).append('\n');
        }
        int versionLineEnd = -1;
        int versionStart = source.indexOf("#version");
        if (versionStart >= 0) {
            versionLineEnd = source.indexOf('\n', versionStart);
        }
        if (versionLineEnd < 0) {
            return block + source;
        }
        return source.substring(0, versionLineEnd + 1) + block + source.substring(versionLineEnd + 1);
    }

    /** GUI plan Phase 4: whether detailed compile-failure records are enabled. */
    private static boolean diagnosticsEnabled() {
        try {
            return com.vulkairis.config.VulkairisConfigManager.getConfig()
                    .isLogShaderCompileErrors();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static int mapShaderTypeToShaderc(ShaderType type) {
        if (type == null) return Shaderc.shaderc_glsl_infer_from_source;
        return switch (type) {
            case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
            case GEOMETRY -> Shaderc.shaderc_glsl_geometry_shader;
            case COMPUTE -> Shaderc.shaderc_glsl_compute_shader;
            case TESSELATION_CONTROL -> Shaderc.shaderc_glsl_tess_control_shader;
            case TESSELATION_EVAL -> Shaderc.shaderc_glsl_tess_evaluation_shader;
        };
    }

    public static int mapStageIntToShaderc(int stage) {
        return switch (stage) {
            case 35633 -> Shaderc.shaderc_glsl_vertex_shader;
            case 35632 -> Shaderc.shaderc_glsl_fragment_shader;
            case 36313 -> Shaderc.shaderc_glsl_geometry_shader;
            case 37305 -> Shaderc.shaderc_glsl_compute_shader;
            default -> Shaderc.shaderc_glsl_infer_from_source;
        };
    }
}
