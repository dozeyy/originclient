package com.vulkairis.client.mixin.iris;

import net.irisshaders.batchedentityrendering.impl.MemoryTrackingBuffer;
import net.irisshaders.batchedentityrendering.mixin.BufferSourceAccessor;
import net.irisshaders.batchedentityrendering.mixin.OutlineBufferSourceAccessor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into RenderBuffers to intercept freeAndDeleteBuffers() and memory queries added by Iris.
 *
 * Under VulkanMod, Minecraft's vertex buffers are vanilla ByteBufferBuilder instances
 * rather than Iris's MemoryTrackingBuffer. Iris's unchecked casts in
 * MixinRenderBuffers.freeAndDeleteBuffers() and MixinRenderBuffers.getMiscBufferAllocatedSize()
 * throw a ClassCastException:
 *   "ByteBufferBuilder cannot be cast to MemoryTrackingBuffer"
 * which aborts the pipeline cleanup and causes cascade failures.
 *
 * This mixin cancels Iris's unchecked calls and performs safe instance checks.
 */
@Mixin(value = RenderBuffers.class, priority = 1500)
public abstract class RenderBuffersMixin {
    private static final Logger VULKAIRIS_LOGGER = LoggerFactory.getLogger("Vulkairis/RenderBuffersMixin");

    @Shadow
    private MultiBufferSource.BufferSource bufferSource;

    @Shadow
    private OutlineBufferSource outlineBufferSource;

    @Dynamic("Added by Iris MixinRenderBuffers")
    @Inject(method = "freeAndDeleteBuffers", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vulkairis$safeFreeAndDeleteBuffers(CallbackInfo ci) {
        ci.cancel();
        VULKAIRIS_LOGGER.info("[Vulkairis] Safely intercepted freeAndDeleteBuffers() under VulkanMod; avoiding ClassCastException");

        try {
            if (this.bufferSource instanceof BufferSourceAccessor accessor) {
                try {
                    var fixed = accessor.getFixedBuffers();
                    if (fixed != null) {
                        fixed.forEach((k, v) -> {
                            if (v instanceof MemoryTrackingBuffer mtb) {
                                mtb.freeAndDeleteBuffer();
                            }
                        });
                        fixed.clear();
                    }
                } catch (Throwable t) {
                    VULKAIRIS_LOGGER.debug("[Vulkairis] Error clearing fixedBuffers: {}", t.getMessage());
                }
            }

            if (this.outlineBufferSource instanceof OutlineBufferSourceAccessor accessor) {
                try {
                    Object outline = accessor.getOutlineBufferSource();
                    if (outline instanceof MemoryTrackingBuffer mtb) {
                        mtb.freeAndDeleteBuffer();
                    }
                } catch (Throwable t) {
                    VULKAIRIS_LOGGER.debug("[Vulkairis] Error cleaning outlineBufferSource: {}", t.getMessage());
                }
            }
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Exception during safe freeAndDeleteBuffers(): {}", t.getMessage());
        }
    }

    @Dynamic("Added by Iris MixinRenderBuffers")
    @Inject(method = "getMiscBufferAllocatedSize", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vulkairis$safeGetMiscBufferAllocatedSize(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(0L);
    }

    @Dynamic("Added by Iris MixinRenderBuffers")
    @Inject(method = "getEntityBufferAllocatedSize", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vulkairis$safeGetEntityBufferAllocatedSize(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(0L);
    }
}
