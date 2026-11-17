package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/**
 * Origin (2026-11-17). Target: Iris's {@code ShaderMap(Function<ShaderKey, ShaderInstance>)} constructor,
 * the one place Iris builds its replacement for every vanilla core shader (entities, hand, particles,
 * sky, block entities, text...) as an {@code ExtendedShader}.
 *
 * <p>Why: under VulkanMod those ExtendedShaders go through VulkanMod's legacy-shader path, which does not
 * accept Iris's transformed GLSL (36 programs failed to compile into SPIR-V with MakeUp, e.g.
 * "'texture' : no matching overloaded function") and has no supplier for Iris's uniforms (the ~100
 * "field X not present in uniform map" lines per uniform). A failed one still sat in the map, so Iris
 * handed it to the game: the hand drew plain white. With every entry null, Iris's own override
 * ({@code MixinGameRenderer.override}) keeps the vanilla shader, so everything that is not chunk terrain
 * draws exactly as VulkanMod draws it without a pack. Pack terrain, shadows and the post chain are
 * Vulkairis's own paths and are unaffected.</p>
 *
 * <p>What breaks without it: a white hand, 72 SPIR-V compile stack traces at every pack load, and the
 * uniform-map log flood. Remove once entities/hand get a real pack path.</p>
 */
@Mixin(value = ShaderMap.class, remap = false)
public class ShaderMapMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object vulkairis$vanillaForNonTerrain(Function<ShaderKey, ?> factory, Object key) {
        return null;
    }
}
