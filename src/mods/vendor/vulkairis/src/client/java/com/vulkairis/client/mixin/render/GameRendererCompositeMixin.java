package com.vulkairis.client.mixin.render;

import com.vulkairis.origin.composite.CompositeRunner;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (step 2). Runs the pack's post chain once per frame at the end of the world pass (see
 * {@link #vulkairis$runPostChain}; moved before the hand on 2026-11-17).
 *
 * <p>Why: the pack's deferred/composite/final chain must run exactly once per frame, after the whole
 * world is drawn and before the GUI. Upstream ran it from {@code Renderer.endRenderPass}, which fires
 * on every render-pass end (re-entrant, several times a frame, and also on menus).</p>
 *
 * <p>Without it: no post chain runs, so a shader pack changes nothing on screen (the vanilla world
 * shows through, which is also the fail-soft state).</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCompositeMixin {
    /**
     * Origin (step 3). Same target method, at HEAD: advances Iris's frame counter and frame timer
     * ({@code frameCounter}, {@code frameTimeCounter}, {@code frameTime}) and the real tick delta.
     * Iris does this from its own {@code MixinGameRenderer}, which VulkaIris strips; without it every
     * animated effect in a pack (water waves, clouds, wind) stands still.
     */
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void vulkairis$beginIrisFrame(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        try {
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE
                    .setRealTickDelta(deltaTracker.getGameTimeDeltaPartialTick(true));
            net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.beginFrame();
            net.irisshaders.iris.uniforms.SystemTimeUniforms.TIMER.beginFrame(net.minecraft.Util.getNanos());
        } catch (Throwable ignored) {
            // Fail soft: time uniforms stay where they were.
        }
        // Origin (2026-11-17): chunk normals are encoded only while pack terrain is linked (TerrainNormals).
        com.vulkairis.origin.terrain.TerrainNormals.update();
    }

    /**
     * Origin (2026-11-17). Target: {@code GameRenderer.renderLevel(DeltaTracker)}, just before its
     * {@code popPush("hand")}: the world is finished ({@code LevelRenderer.renderLevel} returned, world
     * depth captured) and vanilla has not yet cleared depth or drawn the hand. Iris runs its composites
     * at the same point and draws the hand afterwards on top of the final image.
     *
     * <p>Why here and not after {@code renderLevel}: there the hand is already in the main colour target,
     * so the composite/final chain overwrote or re-graded it (MakeUp: the arm washed pale, the sleeve
     * layer gone). Running before the hand leaves the hand exactly as vanilla draws it.</p>
     */
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                    args = "ldc=hand"))
    private void vulkairis$runPostChain(DeltaTracker deltaTracker, CallbackInfo ci) {
        CompositeRunner.afterWorld();
    }
}
