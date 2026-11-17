package com.vulkairis.client.mixin.vulkan;

import com.vulkairis.origin.shadow.ShadowPass;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.MainPass;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (step 6). Target: VulkanMod's {@code WorldRenderer.renderSectionLayer(TerrainRenderType, double,
 * double, double, Matrix4f, Matrix4f)}, the one place VulkanMod draws chunk terrain.
 *
 * <p>Why: Iris's shadow pass never runs under VulkanMod, so a pack's shadow map stayed empty. At HEAD of
 * the SOLID layer, {@link ShadowPass#beforeLayer} draws the opaque layers once more into the shadow map
 * (re-entering this method with the sun's matrices). Inside that nested draw two of VulkanMod's own calls
 * must not run as-is: {@code rebindMainTarget()} would end the shadow pass and switch back to the screen,
 * and {@code enableCull()} would cull the wrong faces (the shadow pass uses an un-flipped viewport, so
 * winding is reversed; Iris draws shadows without culling too).</p>
 *
 * <p>What breaks without it: no shadow map, so packs that sample {@code shadowtex0} (MakeUp and most
 * others) see "everything in shadow" or no shadows at all. Outside a shadow pass both redirects call the
 * original method unchanged.</p>
 */
@Mixin(value = WorldRenderer.class, remap = false)
public class WorldRendererShadowMixin {
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void vulkairis$shadowPass(TerrainRenderType type, double camX, double camY, double camZ,
                                      Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        ShadowPass.beforeLayer((WorldRenderer) (Object) this, type, camX, camY, camZ);
    }

    @Redirect(method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/pass/MainPass;rebindMainTarget()V"))
    private void vulkairis$keepShadowTarget(MainPass mainPass) {
        if (!ShadowPass.isActive()) {
            mainPass.rebindMainTarget();
        }
    }

    @Redirect(method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/VRenderSystem;enableCull()V"))
    private void vulkairis$noCullInShadow() {
        if (ShadowPass.isActive()) {
            VRenderSystem.disableCull();
        } else {
            VRenderSystem.enableCull();
        }
    }
}
