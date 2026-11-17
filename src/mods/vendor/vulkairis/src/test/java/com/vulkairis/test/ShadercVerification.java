package com.vulkairis.test;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class ShadercVerification {
    public static void main(String[] args) {
        System.out.println("=== Starting In-Process Shaderc SPIR-V Verification ===");

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            System.err.println("FAILED: Shaderc compiler returned 0");
            System.exit(1);
        }

        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            System.err.println("FAILED: Shaderc compile options returned 0");
            System.exit(1);
        }

        Shaderc.shaderc_compile_options_set_target_env(
                options,
                Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_1
        );
        Shaderc.shaderc_compile_options_set_invert_y(options, true);

        String vertSource = """
                #version 450 core
                layout (location = 0) in vec3 Position;
                layout (location = 1) in vec4 Color;
                layout (location = 0) out vec4 vertexColor;
                void main() {
                    gl_Position = vec4(Position, 1.0);
                    vertexColor = Color;
                }
                """;

        System.out.println("Compiling GLSL Vertex Shader into SPIR-V...");
        long result = Shaderc.shaderc_compile_into_spv(
                compiler,
                vertSource,
                Shaderc.shaderc_glsl_vertex_shader,
                "iris_test_vertex.vsh",
                "main",
                options
        );

        if (result == 0L) {
            System.err.println("FAILED: Shaderc compilation result pointer is 0");
            System.exit(1);
        }

        int status = Shaderc.shaderc_result_get_compilation_status(result);
        if (status != Shaderc.shaderc_compilation_status_success) {
            String errorMsg = Shaderc.shaderc_result_get_error_message(result);
            System.err.println("FAILED: Shaderc compilation error:\n" + errorMsg);
            Shaderc.shaderc_result_release(result);
            System.exit(1);
        }

        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
        if (spv == null || !spv.hasRemaining()) {
            System.err.println("FAILED: SPIR-V bytecode buffer is empty");
            System.exit(1);
        }

        IntBuffer intBuf = spv.asIntBuffer();
        int magic = intBuf.get(0);
        int version = intBuf.get(1);

        System.out.println("SUCCESS: SPIR-V generated!");
        System.out.println("  Bytecode Size: " + spv.remaining() + " bytes");
        System.out.println("  SPIR-V Magic : 0x" + Integer.toHexString(magic) + " (expected: 0x07230203)");
        System.out.println("  SPIR-V Version: 0x" + Integer.toHexString(version));

        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);

        if (magic != 0x07230203) {
            System.err.println("FAILED: SPIR-V magic mismatch");
            System.exit(1);
        }

        System.out.println("=== In-Process Shaderc SPIR-V Verification Passed! ===");
    }
}
