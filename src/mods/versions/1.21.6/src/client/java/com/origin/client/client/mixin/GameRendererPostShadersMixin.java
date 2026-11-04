package com.origin.client.client.mixin;

import com.origin.client.client.render.AfterShadersPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TARGET: GameRenderer.renderLevel(DeltaTracker), around its one call to
// LevelRenderer.renderLevel -- before the "hand" section that follows it.
// The call's exact shape on 1.21.6 - 1.21.8 is not javap-proven here (1.21.10's has
// three Matrix4f; this era has the fog GpuBufferSlice + Vector4f + sky flag at the
// end), so the hook is written for each candidate shape. defaultRequire is 0 in
// this config: a shape that does not exist injects nowhere, the real one carries it.
//
// WHY: Origin's world overlays -- waypoint beams + highlight, party ping
// pillars, the WorldEdit CUI selection and Chunk Borders -- must stay visible
// through a shader pack's clouds and fog. Iris (1.9.6 on 1.21.6) runs the
// pack's composite + final passes in iris$endLevelRender, at the end of
// LevelRenderer.renderLevel, so right after the call the pack has painted its
// clouds into the main target and its overrides are off. AfterShadersPass draws
// the banked overlays here; with shaders off nothing was banked and run()
// returns at once.
//
// IF IT STOPS APPLYING: run() is never called, the pass never sees the hook as
// alive, nothing is ever banked, and the overlays keep drawing at LAST (under
// the pack's clouds, the old behaviour; one log line says the hook is missing).
// CallbackInfo-only handlers.
@Mixin(GameRenderer.class)
public class GameRendererPostShadersMixin {
	// Same call, BEFORE it: start the frame (decide whether to bank this frame).
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void originclient$overlaysFrameStart(CallbackInfo ci) {
		try {
			AfterShadersPass.beginFrame();
		} catch (Throwable t) {
			AfterShadersPass.warnSetupOnce("frame start", t);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			shift = At.Shift.AFTER))
	private void originclient$overlaysAfterShaders(CallbackInfo ci) {
		try {
			AfterShadersPass.run();
		} catch (Throwable t) {
			// an overlay must never take the frame down -- but say why, once
			AfterShadersPass.warnSetupOnce("after-shaders hook", t);
		}
	}
	// Same call, BEFORE it: start the frame (decide whether to bank this frame).
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void originclient$overlaysFrameStartThreeMatrices(CallbackInfo ci) {
		try {
			AfterShadersPass.beginFrame();
		} catch (Throwable t) {
			AfterShadersPass.warnSetupOnce("frame start", t);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			shift = At.Shift.AFTER))
	private void originclient$overlaysAfterShadersThreeMatrices(CallbackInfo ci) {
		try {
			AfterShadersPass.run();
		} catch (Throwable t) {
			// an overlay must never take the frame down -- but say why, once
			AfterShadersPass.warnSetupOnce("after-shaders hook", t);
		}
	}
	// Same call, BEFORE it: start the frame (decide whether to bank this frame).
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void originclient$overlaysFrameStartWithGameRenderer(CallbackInfo ci) {
		try {
			AfterShadersPass.beginFrame();
		} catch (Throwable t) {
			AfterShadersPass.warnSetupOnce("frame start", t);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			shift = At.Shift.AFTER))
	private void originclient$overlaysAfterShadersWithGameRenderer(CallbackInfo ci) {
		try {
			AfterShadersPass.run();
		} catch (Throwable t) {
			// an overlay must never take the frame down -- but say why, once
			AfterShadersPass.warnSetupOnce("after-shaders hook", t);
		}
	}
}
