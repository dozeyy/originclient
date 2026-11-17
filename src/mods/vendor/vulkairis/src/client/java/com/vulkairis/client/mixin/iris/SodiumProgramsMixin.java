package com.vulkairis.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms.Pass;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @author Vulkairis
 * @reason Sodium terrain OpenGL shaders are not used under VulkanMod; stub to avoid NoSuchMethodError on GlShader constructor.
 *        This mixin replaces SodiumPrograms methods with no-ops compatible with the VulkanMod pipeline.
 */
@Mixin(value = SodiumPrograms.class, remap = false)
public class SodiumProgramsMixin {

    /**
     * @author Vulkairis
     * @reason Returns empty map — no GL shaders created when VulkanMod is active.
     */
    @Overwrite
    private Map<PatchShaderType, GlShader> createGlShaders(String name, Map<PatchShaderType, String> transformed) {
        return Collections.emptyMap();
    }

    /**
     * @author Vulkairis
     * @reason Returns null — no GlProgram created when VulkanMod is active.
     */
    @Overwrite
    private GlProgram<ChunkShaderInterface> createShader(IrisRenderingPipeline pipeline,
                                                         Pass pass,
                                                         ProgramSource source,
                                                         AlphaTest alphaTest,
                                                         CustomUniforms customUniforms,
                                                         Supplier<ImmutableSet<Integer>> flipState,
                                                         Map<PatchShaderType, GlShader> glShaders) {
        return null;
    }
}
