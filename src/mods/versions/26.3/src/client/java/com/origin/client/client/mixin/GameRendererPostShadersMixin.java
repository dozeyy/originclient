package com.origin.client.client.mixin;

import com.origin.client.client.render.AfterShadersPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TARGET: GameRenderer.renderLevel(), right AFTER its one call to
// LevelRenderer.render(...) -- before render3dHud (the first-person hand) that
// follows it (javap of the 26.3 client, CI probe 2026-11-03).
//
// WHY: Origin's world overlays -- waypoint beams + highlight, party ping
// pillars, the WorldEdit CUI selection and Chunk Borders -- must stay visible
// through a shader pack's clouds and fog. Iris 1.11.x runs the pack's composite +
// final passes in iris$endLevelRender, at LevelRenderer.render's last
// Matrix4fStack.popMatrix, so at this point the pack has painted its clouds into
// the main target and its overrides are off. AfterShadersPass draws the banked
// overlays here; with shaders off nothing was banked and run() returns at once.
//
// IF IT STOPS APPLYING: run() is never called, the pass never sees the hook as
// alive, and the overlays keep submitting to the level pass (under the pack's
// clouds, the old behaviour; one log line says the hook is missing).
// CallbackInfo-only handler.
@Mixin(GameRenderer.class)
public class GameRendererPostShadersMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
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
