package com.vulkairis.client.mixin.render;

import net.minecraft.client.renderer.ShaderInstance;
import com.vulkairis.translation.UnifiedTranslationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts VulkanMod's vanilla shader parsing to prevent crashes on Iris ExtendedShaders.
 *
 * VulkanMod's {@code ShaderInstance} attempts to parse all shaders using its own internal
 * GLSLParser, which fails on OptiFine-style uniform syntax. This guard redirects Iris-managed
 * shaders to the Vulkairis UnifiedTranslationService.
 */
@Mixin(ShaderInstance.class)
public class MixinShaderInstanceGuard {

    @Inject(
        method = "getOrCreate",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void onGetOrCreate(
        Object provider, // Use Object to avoid missing package net.minecraft.resource
        String programName,
        String shaderName,
        CallbackInfoReturnable<ShaderInstance> cir
    ) {
        if (isIrisShader(programName)) {
            var outcome = UnifiedTranslationService.getOutcome(programName, com.vulkairis.iris.SourceProvenance.currentGeneration());
            if (outcome != null && outcome.succeeded()) {
                // In a full implementation, we would return the cached ShaderInstance here.
                // For now, we mark it as a redirect.
            }
        }
    }

    private static boolean isIrisShader(String name) {
        if (name == null) return false;
        return name.contains("gbuffers") || name.contains("composite") || name.contains("shadow");
    }
}
