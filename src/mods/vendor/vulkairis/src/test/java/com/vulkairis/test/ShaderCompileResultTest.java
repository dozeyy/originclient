package com.vulkairis.test;

import com.vulkairis.core.VulkairisController;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import com.vulkairis.translation.SpirvValidator;
import com.vulkairis.translation.ShadercProfile;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Phase 4: honest compile results, explicit stage mapping, stronger structural validation and
 * SPIR-V-backed reflection. Failures carry status plus log and never yield usable bytecode.
 */
public class ShaderCompileResultTest {

    private static final String SIMPLE_FRAG = """
            #version 450 core
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0, 0.0, 0.0, 1.0);
            }
            """;

    private static final String BROKEN = """
            #version 450 core
            out vec4 fragColor;
            void main() {
                this is not valid glsl;
            }
            """;

    @Test
    public void testStageMappingForEveryShaderType() {
        Assertions.assertEquals(Shaderc.shaderc_glsl_vertex_shader,
                SpirvCompiler.mapShaderTypeToShaderc(ShaderType.VERTEX));
        Assertions.assertEquals(Shaderc.shaderc_glsl_fragment_shader,
                SpirvCompiler.mapShaderTypeToShaderc(ShaderType.FRAGMENT));
        Assertions.assertTrue(SpirvCompiler.isSupportedShaderType(ShaderType.VERTEX));
        Assertions.assertTrue(SpirvCompiler.isSupportedShaderType(ShaderType.FRAGMENT));

        for (ShaderType type : new ShaderType[]{
                ShaderType.GEOMETRY, ShaderType.COMPUTE,
                ShaderType.TESSELATION_CONTROL, ShaderType.TESSELATION_EVAL}) {
            Assertions.assertFalse(SpirvCompiler.isSupportedShaderType(type),
                    type + " must stay rejected until its pipeline dispatch exists");
            String reason = SpirvCompiler.rejectionReason(type);
            Assertions.assertNotNull(reason, "rejection must explain itself for " + type);
            Assertions.assertTrue(reason.contains("not supported")
                            || reason.contains("not implemented"),
                    "rejection must name the missing work for " + type + ": " + reason);
        }
        Assertions.assertNull(SpirvCompiler.rejectionReason(ShaderType.VERTEX));
    }

    @Test
    public void testIntStageMappingPreserved() {
        Assertions.assertEquals(Shaderc.shaderc_glsl_vertex_shader,
                SpirvCompiler.mapStageIntToShaderc(35633));
        Assertions.assertEquals(Shaderc.shaderc_glsl_fragment_shader,
                SpirvCompiler.mapStageIntToShaderc(35632));
        Assertions.assertTrue(SpirvCompiler.isSupportedGlStage(35633));
        Assertions.assertTrue(SpirvCompiler.isSupportedGlStage(35632));
        Assertions.assertFalse(SpirvCompiler.isSupportedGlStage(36313),
                "geometry must stay rejected");
        Assertions.assertFalse(SpirvCompiler.isSupportedGlStage(37305),
                "compute must stay rejected");
    }

    @Test
    public void testUnsupportedKindRejectedWithoutBytecode() {
        ShaderCompileResult result = SpirvCompiler.compileGlslToSpirvDetailed(
                "geo", SIMPLE_FRAG, Shaderc.shaderc_glsl_geometry_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.STAGE_REJECTED, result.status());
        Assertions.assertFalse(result.succeeded());
        Assertions.assertNull(result.spirv(), "rejection must never yield bytecode");
        Assertions.assertFalse(result.compilerLog().isBlank(), "rejection must explain itself");
    }

    @Test
    public void testInvalidGlslYieldsFailedStatusLogAndNoBytecode() {
        ShaderCompileResult result = SpirvCompiler.compileGlslToSpirvDetailed(
                "broken.fsh", BROKEN, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.COMPILE_ERROR, result.status());
        Assertions.assertFalse(result.succeeded());
        Assertions.assertNull(result.spirv(), "failed compiles must not register a module");
        Assertions.assertFalse(result.compilerLog().isBlank(), "compiler log must be retained");
        Assertions.assertFalse(result.fatal(), "a syntax error is expected, not fatal");
        Assertions.assertNotNull(result.sourceHash(), "source hash must be recorded");
        Assertions.assertTrue(result.durationMs() >= 0, "timing must be recorded");
        Assertions.assertTrue(result.profileDigest().contains("vulkan1.1"),
                "profile digest must identify the target: " + result.profileDigest());
    }

    @Test
    public void testLegacyThrowingFormPreserved() {
        RuntimeException failure = Assertions.assertThrows(RuntimeException.class,
                () -> SpirvCompiler.compileGlslToSpirv(
                        "broken.fsh", BROKEN, Shaderc.shaderc_glsl_fragment_shader),
                "the legacy form must still throw with the log");
        Assertions.assertTrue(failure.getMessage().contains("broken.fsh"));
    }

    @Test
    public void testValidFixtureSucceedsWithValidationAndReflection() {
        ShaderCompileResult result = SpirvCompiler.compileGlslToSpirvDetailed(
                "simple.fsh", SIMPLE_FRAG, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, result.status());
        Assertions.assertTrue(result.succeeded());
        Assertions.assertTrue(result.spirv().remaining() > 0);
        Assertions.assertTrue(SpirvValidator.isValid(result.spirv()),
                "every successful fixture must pass structural validation");

        ShaderReflection reflection = SpirvReflector.reflect(result.spirv().duplicate());
        Assertions.assertTrue(reflection.isComplete());
        Assertions.assertEquals("main", reflection.getEntryPointName(),
                "entry point must come from the module, not a hardcoded guess");
        Assertions.assertEquals(1, reflection.getStageOutputs().size());
        Assertions.assertEquals(0, reflection.getStageOutputs().get(0).location(),
                "fragment output location must be reflected");
    }

    @Test
    public void testRejectedStageCompilesToContainedHandle() {
        VulkairisShader shader = SpirvCompiler.compileShader(
                "rejected_geo", SIMPLE_FRAG, ShaderType.GEOMETRY);
        Assertions.assertNotNull(shader, "Iris handle bookkeeping still gets a handle");
        Assertions.assertFalse(shader.isValid(), "rejected stages must not look valid");
        Assertions.assertFalse(shader.getReflection().isComplete());
        String lastError = VulkairisController.getInstance().getLastCompileError();
        Assertions.assertTrue(lastError != null && lastError.contains("not supported"),
                "the rejection must be visible in status, got: " + lastError);
    }

    @Test
    public void testValidatorRejectsBadVersion() {
        ByteBuffer zero = header(0x00000000, 0);
        Assertions.assertFalse(SpirvValidator.validate(zero).valid(),
                "version word zero must be rejected");
    }

    @Test
    public void testValidatorRejectsBadSchema() {
        ByteBuffer schema = header(0x00010300, 1);
        Assertions.assertFalse(SpirvValidator.validate(schema).valid(),
                "non-zero schema word must be rejected");
    }

    @Test
    public void testValidatorRequiresEntryPoint() {
        ByteBuffer nop = header(0x00010300, 0);
        ByteBuffer module = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        module.put(nop);
        module.putInt((1 << 16) | 0); // OpNop
        module.flip();
        SpirvValidator.ValidationResult result = SpirvValidator.validate(module);
        Assertions.assertFalse(result.valid(), "a module without OpEntryPoint must be rejected: "
                + result.detail());
    }

    @Test
    public void testValidatorAcceptsRealFixtureModule() {
        ByteBuffer spirv = SpirvCompiler.compileGlslToSpirv(
                "simple.fsh", SIMPLE_FRAG, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertTrue(SpirvValidator.validate(spirv).valid(),
                "a shaderc-produced module must validate");
    }

    @Test
    public void testProfileDigestStable() {
        Assertions.assertEquals(ShadercProfile.DEFAULT.digest(), ShadercProfile.DEFAULT.digest());
        Assertions.assertTrue(ShadercProfile.DEFAULT.digest().contains("vulkan1.1"));
    }

    private static ByteBuffer header(int version, int schema) {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x07230203);
        buffer.putInt(version);
        buffer.putInt(1);
        buffer.putInt(2);
        buffer.putInt(schema);
        buffer.flip();
        return buffer;
    }
}
