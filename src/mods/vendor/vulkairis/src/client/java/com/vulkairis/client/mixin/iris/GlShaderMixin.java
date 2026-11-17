package com.vulkairis.client.mixin.iris;

import com.vulkairis.compatibility.AnalysisResult;
import com.vulkairis.compatibility.ShaderAnalyzer;
import com.vulkairis.config.ConfigManager;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.iris.SourceProvenance;
import com.vulkairis.iris.SourceUnit;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.UnifiedTranslationService;
import com.vulkairis.translation.VulkanShaderRegistry;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = GlShader.class, remap = false)
public abstract class GlShaderMixin extends GlResource {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LoggerFactory.getLogger("Vulkairis/GlShaderMixin");

    protected GlShaderMixin(int id) {
        super(id);
    }

    /**
     * @author Vulkairis
     * @reason Intercept Iris GLSL shader compilation, run dry-run analysis, and compile to SPIR-V.
     */
    @Overwrite
    private static int createShader(ShaderType type, String name, String source) {
        VULKAIRIS_LOGGER.info("[Vulkairis] Intercepted Iris shader compilation: '{}' (stage: {})", name, type);

        // Phase 2: capture the final Iris-transformed source exactly once per pipeline
        // generation. This runs after Iris's TransformPatcher passes and before any raw
        // OpenGL call; Iris keeps owning pack loading, options and macro resolution.
        SourceUnit unit = SourceProvenance.capture(
                IrisBridge.getCurrentPackName(), name, type != null ? type.name() : null, source);
        // Phase D mesh contract: terrain shader interceptions settle against the same
        // once-settled vertex layout (settled for real at renderer init; this only ensures
        // the contract description is available for diagnostics, never re-settling).
        if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("terrain")) {
            try {
                com.vulkairis.vulkan.pipeline.TerrainVertexContract.settle("GlShader.terrain-intercept");
            } catch (Throwable ignored) {
            }
        }
        if (ConfigManager.getConfig().isTraceLogging()) {
            VULKAIRIS_LOGGER.info("[Vulkairis/Trace] post-iris-transform intercept: '{}' unit={} gen={}",
                    name, unit.id(), unit.pipelineGeneration());
        }

        // Dry run analyzer check
        int stage = (type != null) ? type.id : 0;
        AnalysisResult analysis = ShaderAnalyzer.analyzeAndTest(name, source, stage);
        if (!analysis.isSuccess()) {
            DiagnosticManager.log(DiagnosticManager.Category.SHADER,
                    "Shader rejected by dry-run analyzer: " + name, analysis.reason());
            DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                    DiagnosticManager.Phase.INTERCEPT,
                    "Shader rejected by dry-run analyzer", analysis.reason(), null,
                    name, type != null ? type.name() : null, null, -1,
                    DiagnosticManager.hashSource(source),
                    "Remove the unsupported feature or disable the affected program");
            VulkairisController.getInstance().setLastCompileError(name + ": " + analysis.reason());
            VulkairisController.getInstance().triggerFallback("Unsupported shader feature: " + name, analysis.reason());
        }

        int virtualId;
        try {
            virtualId = SpirvCompiler.compileAndRegister(name, source, type);
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Exception compiling shader '{}': {}. Returning fallback virtual handle.", name, t.getMessage());
            DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                    DiagnosticManager.Phase.COMPILE,
                    "Exception compiling shader", t.getMessage(), t,
                    name, type != null ? type.name() : null, null, -1,
                    DiagnosticManager.hashSource(source),
                    "Check /vulkairis status for the compile error; the shader is disabled");
            VulkairisController.getInstance().setLastCompileError(name + ": " + t.getMessage());
            virtualId = VulkanShaderRegistry.registerShader(name, stage, null, source);
        }
        UnifiedTranslationService.bufferRawSource(virtualId, name, source, type != null ? type.name() : null);
        VulkairisController.getInstance().incrementShaderCount();
        return virtualId;
    }

    /**
     * @author Vulkairis
     * @reason Clean up VkShaderModule when Iris destroys a GlShader.
     */
    @Overwrite
    protected void destroyInternal() {
        VulkanShaderRegistry.deleteShader(this.getGlId());
    }
}
