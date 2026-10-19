package com.origin.client.client.mixin;

import com.origin.client.client.waypoints.WaypointRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TARGET: GameRenderer.renderLevel(DeltaTracker), right AFTER its call to
// LevelRenderer.renderLevel(...) -- and therefore before the vanilla
// "hand" section's RenderSystem.clear(GL_DEPTH_BUFFER_BIT) + renderItemInHand
// that follow it (javap of 1.21 and 1.21.1 GameRenderer.renderLevel: same call, same order).
//
// WHY: Origin's waypoint beams must stay visible through a shader pack's
// clouds. Iris 1.8.8 runs the pack's composite + final passes in
// iris$endLevelRender, injected at LevelRenderer.renderLevel RETURN, so at this
// point the pack has already painted its clouds into the main target; its
// shader overrides are off (finalizeLevelRendering clears isRenderingWorld);
// and the main target's depth texture -- the very texture Iris uses as its
// depthtex0 (RenderTargets is built from main.getDepthTextureId()) -- still
// holds the scene depth, so terrain keeps hiding the beam. WaypointRenderer
// only defers its world pass here while a shader pack is in use; with shaders
// off this call returns at once and the beams draw at WorldRenderEvents.LAST
// exactly as before.
//
// IF IT STOPS APPLYING (renamed method / moved call): renderAfterShaders() is
// never called, WaypointRenderer never sees the hook as alive, and beams keep
// drawing at LAST -- under a shader pack they are then covered by its clouds
// again (the old bug), nothing worse. Matched with a CallbackInfo-only handler.
@Mixin(GameRenderer.class)
public class GameRendererPostShadersMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			shift = At.Shift.AFTER))
	private void originclient$waypointsAfterShaders(CallbackInfo ci) {
		try {
			WaypointRenderer.renderAfterShaders();
		} catch (Throwable t) {
			// a waypoint beam must never take the frame down
		}
	}
}
