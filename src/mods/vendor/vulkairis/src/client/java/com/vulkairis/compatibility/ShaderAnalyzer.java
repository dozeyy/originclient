package com.vulkairis.compatibility;

import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Shader Compatibility Analyzer & Dry-Run Verification Engine (Phases 13 & 14).
 * Performs static analysis and virtual dry-run compilation of GLSL shader sources
 * to guarantee that shaders can execute without crashing the runtime.
 */
public class ShaderAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ShaderAnalyzer");

    public static AnalysisResult analyzeAndTest(String shaderName, String glslSource, int stage) {
        if (glslSource == null || glslSource.isBlank()) {
            return AnalysisResult.unsupported(shaderName, "Empty shader source", "No GLSL code provided");
        }

        // DH degrade pass: without the Distant Horizons mod, dh_* sources can never declare
        // their identifiers, so compiling them only produces shaderc error dumps (plus one
        // analyzer warning each). Reject fast with the labeled reason instead; non-DH
        // sources are unaffected and flow into the checks below.
        if (DistantHorizonsSupport.shouldSkipShader(shaderName, glslSource)) {
            return AnalysisResult.unsupported(shaderName,
                    DistantHorizonsSupport.SKIP_HEAD,
                    DistantHorizonsSupport.skipReason(shaderName));
        }

        // 1. Static feature checks for features currently outside prototype scope
        if (glslSource.contains("rayPayloadEXT") || glslSource.contains("accelerationStructureEXT")) {
            return AnalysisResult.unsupported(shaderName, "Hardware Ray Tracing in shader",
                    "Ray tracing extensions are outside prototype scope");
        }

        if (stage == 36313 /* GL_GEOMETRY_SHADER */) {
            return AnalysisResult.unsupported(shaderName, "Geometry shader stage",
                    "Geometry shaders are outside prototype scope");
        }

        // 2. Preprocess GLSL
        String preprocessed;
        try {
            boolean isVertex = (stage == 35633);
            preprocessed = ShaderTranslator.preprocessGlsl(glslSource, isVertex, shaderName);
        } catch (Exception e) {
            return AnalysisResult.error(shaderName, "GLSL Preprocessing failed", e.getMessage());
        }

        // 3. Virtual dry-run compilation
        try {
            int shadercKind = SpirvCompiler.mapStageIntToShaderc(stage);
            ByteBuffer spirv = SpirvCompiler.compileGlslToSpirv(shaderName, preprocessed, shadercKind);

            // 4. SPIR-V structure validation
            SpirvValidator.ValidationResult validation = SpirvValidator.validate(spirv);
            if (!validation.valid()) {
                throw new RuntimeException("Invalid SPIR-V: " + validation.detail());
            }

            LOGGER.info("[Vulkairis/Analyzer] Shader '{}' passed dry-run analysis (SPIR-V: {} bytes)",
                    shaderName, spirv.remaining());
            return AnalysisResult.pass(shaderName);
        } catch (Exception e) {
            LOGGER.warn("[Vulkairis/Analyzer] Dry-run compilation failed for '{}': {}", shaderName, e.getMessage());
            return AnalysisResult.unsupported(shaderName, "SPIR-V compilation error", e.getMessage());
        } catch (LinkageError | OutOfMemoryError | StackOverflowError fatal) {
            // Native-stack exhaustion on huge pack sources (MemoryStack "Out of stack
            // space") and other JVM-level failures must stay contained here: the
            // dry-run is advisory, and letting an Error escape rejects the whole pack
            // and forces bridge fallback for what is one bad program.
            LOGGER.warn("[Vulkairis/Analyzer] Dry-run contained JVM-level failure for '{}': {}",
                    shaderName, fatal.toString());
            return AnalysisResult.unsupported(shaderName, "SPIR-V compilation error",
                    "Contained JVM-level failure (no fallback): " + fatal);
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis/Analyzer] Dry-run failed for '{}': {}", shaderName, t.toString());
            return AnalysisResult.unsupported(shaderName, "SPIR-V compilation error", t.toString());
        }
    }
}
