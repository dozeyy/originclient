package com.vulkairis.translation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ShaderCompilerService using VulkanMod's compiler path.
 * ponytail: Reuses SpirvCompiler as the engine.
 */
public class ShaderCompilerService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/CompilerService");
    private static final Map<String, Integer> COMPILE_CACHE = new ConcurrentHashMap<>();

    public static int compile(String name, String source, int stage) {
        // ponytail: simplified caching, assume source is already preprocessed for now
        return SpirvCompiler.compileAndRegister(name, source, stage);
    }

    public static int compile(String name, String source, net.irisshaders.iris.gl.shader.ShaderType type) {
        return SpirvCompiler.compileAndRegister(name, source, type);
    }

    public static ByteBuffer getBytecode(int virtualId) {
        var shader = VulkanShaderRegistry.getShader(virtualId);
        return shader != null ? shader.spirv() : null;
    }
}
