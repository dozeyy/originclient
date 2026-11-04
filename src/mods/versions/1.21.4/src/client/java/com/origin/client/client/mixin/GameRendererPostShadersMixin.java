package com.origin.client.client.mixin;

import com.origin.client.client.render.AfterShadersPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TARGET: GameRenderer.renderLevel(DeltaTracker), around its one call to LevelRenderer.renderLevel --
// before the hand section that follows it.
// The call's exact shape is not javap-proven for every release this jar runs on
// (1.21.2 - 1.21.4): it is written once without and once with a LightTexture
// argument. defaultRequire is 0 in this config, so the shape that does not exist
// simply injects nowhere; the one that does carries the hook.
//
// WHY: Origin's world overlays -- waypoint beams + highlight, party ping
// pillars, the WorldEdit CUI selection and Chunk Borders -- must stay visible
// through a shader pack's clouds and fog. Iris 1.8.x (on 1.21.4) runs the
// pack's composite + final passes at LevelRenderer.renderLevel RETURN, so right
// after the call the pack has painted its clouds into the main target and its
// overrides are off. AfterShadersPass draws the banked overlays here; with
// shaders off nothing was banked and run() returns at once.
//
// IF IT STOPS APPLYING: run() is never called, the pass never sees the hook as
// alive, nothing is ever banked, and the overlays keep drawing at LAST (under
// the pack's clouds, the old behaviour; one log line says the hook is missing).
// CallbackInfo-only handlers.
@Mixin(GameRenderer.class)
public class GameRendererPostShadersMixin {
	// Same call, BEFORE it: start the frame (decide whether to bank this frame).
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void originclient$overlaysFrameStart(CallbackInfo ci) {
		try {
			AfterShadersPass.beginFrame();
		} catch (Throwable t) {
			AfterShadersPass.warnSetupOnce("frame start", t);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
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
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void originclient$overlaysFrameStartWithLight(CallbackInfo ci) {
		try {
			AfterShadersPass.beginFrame();
		} catch (Throwable t) {
			AfterShadersPass.warnSetupOnce("frame start", t);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			shift = At.Shift.AFTER))
	private void originclient$overlaysAfterShadersWithLight(CallbackInfo ci) {
		try {
			AfterShadersPass.run();
		} catch (Throwable t) {
			// an overlay must never take the frame down -- but say why, once
			AfterShadersPass.warnSetupOnce("after-shaders hook", t);
		}
	}
}
