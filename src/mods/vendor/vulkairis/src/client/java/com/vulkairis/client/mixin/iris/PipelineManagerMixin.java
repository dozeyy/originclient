package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PipelineManager.class, remap = false)
public class PipelineManagerMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/PipelineManagerMixin");

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; OpenGL texture units do not exist and calling raw GL causes segfaults.
     */
    @Overwrite(remap = false)
    private void resetTextureState() {
        VULKAIRIS_LOGGER.debug("[Vulkairis] Safely bypassed Iris resetTextureState under Vulkan");
    }

    @Inject(method = "preparePipeline", at = @At("RETURN"), remap = false)
    private void onPreparePipeline(NamespacedId dimension, CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        try {
            com.vulkairis.iris.IrisBridge.onPipelinePrepared();
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.error("[Vulkairis] Failed to trigger onPipelinePrepared in preparePipeline", t);
        }
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"), remap = false)
    private void onDestroyPipeline(CallbackInfo ci) {
        try {
            com.vulkairis.iris.IrisBridge.onShaderPackChanged();
        } catch (Throwable ignored) {
        }
    }
}
