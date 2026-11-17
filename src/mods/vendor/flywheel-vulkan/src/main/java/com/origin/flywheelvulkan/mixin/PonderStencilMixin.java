package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Target: Ponder (catnip) {@code net.createmod.catnip.platform.FabricClientHooksHelper}
 * {@code public void enableStencilBuffer(RenderTarget)}, as nested in Create 6.0.0.0 for Fabric
 * 1.21.1 (ponder-fabric 1.0.82; javap, 2026-11-16). Its only caller is
 * UIRenderHelper.CustomRenderTarget.create, Ponder's GUI render target, at startup.
 *
 * <p>Why: enabling the stencil recreates that render target with a GL_DEPTH24_STENCIL8 texture
 * on GL_DEPTH_STENCIL_ATTACHMENT. VulkanMod 0.6.7 has no GL stencil path: GlUtil.vulkanFormat
 * throws "Unexpected format: 35056" and, past that, VkGlFramebuffer.framebufferTexture2D throws
 * UnsupportedOperationException. Either way Create crashed the game during
 * RenderSystem.finishInitialization in Vulkan mode (crash-2026-11-16_20.32.15 / 20.33.45 on
 * Will's PC). Skipping the request keeps the render target's plain depth buffer; Ponder's
 * stencil-masked scene clipping draws unmasked, nothing else changes.
 *
 * <p>If it stops applying: Create crashes at startup in Vulkan mode. The plugin applies it only
 * with the pinned VulkanMod and when Ponder is loaded (a string target: absent Ponder, it is
 * skipped instead of failing).
 */
@Mixin(targets = "net.createmod.catnip.platform.FabricClientHooksHelper", remap = false)
abstract class PonderStencilMixin {
	@Inject(method = "enableStencilBuffer", at = @At("HEAD"), cancellable = true, remap = false)
	private void origin$noStencilOnVulkan(RenderTarget target, CallbackInfo ci) {
		ci.cancel();
	}
}
