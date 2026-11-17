package com.vulkairis.client.mixin.render;

import com.vulkairis.origin.uniforms.GlProjection;
import com.vulkairis.origin.uniforms.IrisUniformValues;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (step 3). Target: {@code LevelRenderer.renderLevel(DeltaTracker, boolean, Camera, GameRenderer,
 * LightTexture, Matrix4f, Matrix4f)} at HEAD.
 *
 * <p>Why: VulkaIris strips Iris's {@code MixinLevelRenderer}, which is what feeds Iris's
 * {@code CapturedRenderingState} (gbufferModelView, gbufferProjection, tick delta, cloud time) and
 * advances its world-time uniforms. This replays only that capture (no pipeline calls, no GL), with the
 * projection converted back to OpenGL's depth range ({@link GlProjection}), then refreshes the
 * per-frame uniform values the Vulkan upload reads.</p>
 *
 * <p>Without it: every matrix uniform a pack reads is identity/zero, sunPosition and the sky
 * reconstruction break, and time-driven effects freeze.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererIrisStateMixin {
    @Shadow
    private int ticks;

    @Unique
    private static final Matrix4f VULKAIRIS$GL_PROJECTION = new Matrix4f();

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vulkairis$captureIrisState(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                           GameRenderer gameRenderer, LightTexture lightTexture,
                                           Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        try {
            IrisTimeUniforms.updateTime();
            CapturedRenderingState state = CapturedRenderingState.INSTANCE;
            state.setGbufferModelView(new Matrix4f(modelView));
            state.setGbufferProjection(GlProjection.toGlDepth(projection, new Matrix4f()));
            float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(false);
            state.setTickDelta(tickDelta);
            state.setCloudTime((ticks + tickDelta) * 0.03F);
        } catch (Throwable ignored) {
            // Fail soft: the uniforms keep last frame's values.
        }
        IrisUniformValues.beginFrame();
    }
}
