package com.vulkairis.client.mixin.vulkan;

import com.vulkairis.origin.terrain.TerrainDraw;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (step 5). Target: VulkanMod's {@code VTextureSelector.bindShaderTextures(Pipeline)}, which the
 * chunk loop calls right after binding the terrain pipeline and which binds each sampler to whatever
 * texture sits on the GL unit equal to its image index. Why: a pack terrain pipeline's samplers are
 * numbered densely (gtexture, lightmap, noisetex, ...), so by index they would get the wrong textures;
 * at TAIL this rebinds them by name ({@code TerrainDraw.bindTextures}). Without it pack terrain samples
 * the lightmap or the missing texture as its block atlas. Only pack terrain pipelines are touched.
 */
@Mixin(value = VTextureSelector.class, remap = false)
public class VTextureSelectorMixin {
    @Inject(method = "bindShaderTextures", at = @At("TAIL"))
    private static void vulkairis$packTerrainTextures(Pipeline pipeline, CallbackInfo ci) {
        if (TerrainDraw.isPackPipeline(pipeline)) {
            TerrainDraw.bindTextures(pipeline);
        }
    }
}
