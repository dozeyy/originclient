package com.vulkairis.test;

import com.vulkairis.rendering.state.ProgramUniformStore;
import com.vulkairis.rendering.state.Std140;
import com.vulkairis.rendering.state.UniformBlockPacker;
import com.vulkairis.rendering.state.UniformFrameStore;
import com.vulkairis.rendering.state.UniformPackPlanner;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VirtualGlFacade;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Phase 10: uniform writes are isolated by program and frame, UBO packing honors std140
 * strides from reflection, and nothing assumes a fixed buffer size.
 */
public class UniformIsolationTest {

    private static final String VERT = """
            #version 150 core
            in vec3 iris_Position;
            uniform mat4 projection;
            void main() {
                gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 150 core
            uniform vec4 tintColor;
            uniform mat4 shadowMatrix;
            uniform float weights[4];
            out vec4 fragColor;
            void main() {
                vec4 shadowed = shadowMatrix * vec4(1.0);
                fragColor = tintColor * weights[0] * shadowed.x + vec4(0.0);
            }
            """;

    @Test
    public void testWritesIsolatedByProgram() {
        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(1L);
        store.write(5001, 101,
                ProgramUniformStore.UniformValue.floats("vec4", 1.0f, 0.0f, 0.0f, 1.0f));
        store.write(5002, 101,
                ProgramUniformStore.UniformValue.floats("vec4", 0.0f, 1.0f, 0.0f, 1.0f));

        Assertions.assertEquals(1.0f, store.read(5001, 101).orElseThrow().floats()[0]);
        Assertions.assertEquals(0.0f, store.read(5002, 101).orElseThrow().floats()[0],
                "same location in another program must not alias");
        Assertions.assertEquals(2, store.programCount());
    }

    @Test
    public void testFrameBoundariesDropStaleValues() {
        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(1L);
        store.write(5001, 101, ProgramUniformStore.UniformValue.floats("float", 1.0f));
        Assertions.assertTrue(store.read(5001, 101).isPresent());

        store.beginFrame(1L);
        Assertions.assertTrue(store.read(5001, 101).isPresent(),
                "same frame must keep values");

        store.beginFrame(2L);
        Assertions.assertTrue(store.read(5001, 101).isEmpty(),
                "new frame must not see old values");
        Assertions.assertEquals(2L, store.frameIndex());

        store.write(5001, 101, ProgramUniformStore.UniformValue.floats("float", 2.0f));
        store.invalidate();
        Assertions.assertTrue(store.read(5001, 101).isEmpty(),
                "reload invalidation must not leak values");
        Assertions.assertEquals(0, store.programCount());
    }

    @Test
    public void testStd140Layouts() {
        Assertions.assertEquals(new Std140.MemberLayout(4, 1, 1), Std140.layoutOf("float"));
        Assertions.assertEquals(new Std140.MemberLayout(12, 1, 3), Std140.layoutOf("vec3"));
        Assertions.assertEquals(new Std140.MemberLayout(64, 4, 4), Std140.layoutOf("mat4"));
        Assertions.assertEquals(new Std140.MemberLayout(48, 3, 4), Std140.layoutOf("mat3"));
        Assertions.assertEquals(new Std140.MemberLayout(64, 4, 4), Std140.layoutOf("float[4]"));

        Assertions.assertEquals(List.of(0, 1, 2), Std140.floatSlots("vec3", 3));
        Assertions.assertEquals(List.of(0, 4, 8, 12), Std140.floatSlots("float[4]", 4),
                "array elements stride to 16 bytes");
        Assertions.assertEquals(List.of(0, 1, 4, 5), Std140.floatSlots("mat2", 4),
                "matrix columns stride to 16 bytes");
        Assertions.assertEquals(List.of(0, 1, 2, 4, 5, 6, 8, 9, 10),
                Std140.floatSlots("mat3", 9), "matrix columns stride to 16 bytes");
        Assertions.assertTrue(Std140.floatSlots("double", 2).isEmpty(),
                "doubles are reported, never narrowed");
        Assertions.assertTrue(Std140.floatSlots("dvec3", 6).isEmpty());
    }

    @Test
    public void testPlannerPacksFromReflectionWithStrides() {
        VulkairisShader vert = SpirvCompiler.compileShader("iso_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("iso_frag", FRAG, ShaderType.FRAGMENT);
        Assertions.assertTrue(frag.getReflection().isComplete());
        int progId = VulkanShaderRegistry.registerProgram(
                "iso_prog", List.of(vert.virtualId(), frag.virtualId()));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);

        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(7L);
        int tintLoc = VirtualGlFacade.getUniformLocation(progId, "tintColor");
        int weightsLoc = VirtualGlFacade.getUniformLocation(progId, "weights");
        int shadowLoc = VirtualGlFacade.getUniformLocation(progId, "shadowMatrix");
        int projLoc = VirtualGlFacade.getUniformLocation(progId, "projection");
        Assertions.assertTrue(tintLoc >= 0 && weightsLoc >= 0 && shadowLoc >= 0 && projLoc >= 0,
                "planner inputs resolve through the facade");
        store.write(progId, tintLoc,
                ProgramUniformStore.UniformValue.floats("vec4", 0.25f, 0.5f, 0.75f, 1.0f));
        store.write(progId, weightsLoc,
                ProgramUniformStore.UniformValue.floats("float", 1.0f, 2.0f, 3.0f, 4.0f));
        float[] identity = new float[16];
        for (int i = 0; i < 16; i += 5) {
            identity[i] = 1.0f;
        }
        store.write(progId, shadowLoc,
                ProgramUniformStore.UniformValue.floats("mat4", identity));
        store.write(progId, projLoc,
                ProgramUniformStore.UniformValue.floats("mat4", identity));

        UniformPackPlanner.ProgramPack pack = UniformPackPlanner.plan(program, store);
        StringBuilder structure = new StringBuilder();
        for (Map.Entry<String, UniformBlockPacker.PackResult> entry : pack.blocks().entrySet()) {
            structure.append("key=").append(entry.getKey())
                    .append(" written=").append(entry.getValue().written())
                    .append(" missing=").append(entry.getValue().missing()).append("; ");
        }
        for (VulkairisShader stage : program.getAttachedShaders()) {
            for (ShaderReflection.UniformBlock block : stage.getReflection().getUniformBlocks()) {
                structure.append("reflected[").append(stage.getName()).append("]:")
                        .append(block.name()).append(block.members()).append("; ");
            }
        }
        Assertions.assertTrue(pack.unresolved().isEmpty(),
                "every written member must resolve; unresolved=" + pack.unresolved()
                        + " structure=" + structure);
        Assertions.assertFalse(pack.blocks().isEmpty());

        // Verify bytes at the reflected offsets (no fixed-size assumption anywhere).
        for (VulkairisShader stage : program.getAttachedShaders()) {
            for (ShaderReflection.UniformBlock block : stage.getReflection().getUniformBlocks()) {
                UniformBlockPacker.PackResult result =
                        pack.blocks().get(stage.getName() + ":" + block.name());
                Assertions.assertNotNull(result,
                        "every reflected block must have a pack result");
                Assertions.assertTrue(result.isComplete());
                java.nio.ByteBuffer data = result.data().order(ByteOrder.nativeOrder());
                for (ShaderReflection.UniformInfo member : block.members()) {
                    if (member.name().equals("tintColor")) {
                        Assertions.assertEquals(0.25f, data.getFloat(member.offset()), 1e-6f);
                    }
                    if (member.name().equals("weights")) {
                        Assertions.assertEquals(1.0f, data.getFloat(member.offset()), 1e-6f);
                        Assertions.assertEquals(2.0f, data.getFloat(member.offset() + 16), 1e-6f,
                                "array elements stride to 16 bytes");
                        Assertions.assertEquals(4.0f, data.getFloat(member.offset() + 48), 1e-6f);
                    }
                }
            }
        }
    }

    @Test
    public void testPlannerIsolatesProgramsAndReportsMissing() {
        VulkairisShader vert = SpirvCompiler.compileShader("iso2_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("iso2_frag", FRAG, ShaderType.FRAGMENT);
        int first = VulkanShaderRegistry.registerProgram(
                "iso_first", List.of(vert.virtualId(), frag.virtualId()));
        int second = VulkanShaderRegistry.registerProgram(
                "iso_second", List.of(vert.virtualId(), frag.virtualId()));

        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(3L);
        int loc = VirtualGlFacade.getUniformLocation(first, "tintColor");
        store.write(first, loc,
                ProgramUniformStore.UniformValue.floats("vec4", 9.0f, 9.0f, 9.0f, 9.0f));

        UniformPackPlanner.ProgramPack planned = UniformPackPlanner.plan(
                VulkanShaderRegistry.getProgram(first), store);
        UniformPackPlanner.ProgramPack empty = UniformPackPlanner.plan(
                VulkanShaderRegistry.getProgram(second), store);
        Assertions.assertFalse(planned.blocks().isEmpty());
        Assertions.assertFalse(empty.blocks().isEmpty());
        Assertions.assertFalse(empty.unresolved().isEmpty(),
                "a program with no written values must report missing members, not zeros");
        for (UniformBlockPacker.PackResult result : empty.blocks().values()) {
            Assertions.assertFalse(result.isComplete());
        }
    }
}
