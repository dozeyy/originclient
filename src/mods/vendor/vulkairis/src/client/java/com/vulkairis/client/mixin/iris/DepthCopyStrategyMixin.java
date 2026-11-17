package com.vulkairis.client.mixin.iris;

import com.vulkairis.client.iris.VulkanDepthCopyStrategy;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DepthCopyStrategy.class, remap = false)
public interface DepthCopyStrategyMixin {
    @Inject(method = "fastest", at = @At("HEAD"), cancellable = true)
    private static void vulkairis$useVulkanStrategy(boolean combinedDepthStencil, CallbackInfoReturnable<DepthCopyStrategy> cir) {
        cir.setReturnValue(VulkanDepthCopyStrategy.INSTANCE);
    }
}
