package com.origin.client.client.mixin;

import com.origin.client.client.render.AfterShadersPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TARGET: GameRenderer.renderLevel(DeltaTracker), right AFTER its one call to
// LevelRenderer.renderLevel(...) -- before the "hand" section that follows it
// (javap of the 1.21.10 / 1.21.11 mapped client, CI probe 2026-11-03).
//
// WHY: Origin's world overlays -- waypoint beams + highlight, party ping
// pillars, the WorldEdit CUI selection and Chunk Borders -- must stay visible
// through a shader pack's clouds and fog. Iris (1.9.x on 1.21.10, 1.10.x on
// 1.21.11) runs the pack's composite + final passes in iris$endLevelRender, at
// LevelRenderer.renderLevel's last Matrix4fStack.popMatrix, so at this point the
// pack has painted its clouds into the main target and its overrides are off.
// AfterShadersPass draws the banked overlays here; with shaders off nothing was
// banked and run() returns at once.
//
// IF IT STOPS APPLYING: run() is never called, the pass never sees the hook as
// alive, and the overlays keep drawing at END_MAIN (under the pack's clouds, the
// old behaviour; one log line says the hook is missing). CallbackInfo-only handler.
@Mixin(GameRenderer.class)
public class GameRendererPostShadersMixin {
	// Same call, BEFORE it: start the frame (decide whether to bank this frame).
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void originclient$overlaysFrameStart(CallbackInfo ci) {
		try {
			AfterShadersPass.beginFrame();
		} catch (Throwable t) {
			AfterShadersPass.warnSetupOnce("frame start", t);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			shift = At.Shift.AFTER))
	private void originclient$overlaysAfterShaders(CallbackInfo ci) {
		try {
			AfterShadersPass.run();
		} catch (Throwable t) {
			// an overlay must never take the frame down -- but say why, once
			AfterShadersPass.warnSetupOnce("after-shaders hook", t);
		}
	}
}
