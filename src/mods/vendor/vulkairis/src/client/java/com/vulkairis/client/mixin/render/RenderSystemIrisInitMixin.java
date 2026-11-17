package com.vulkairis.client.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (step 2). Target: {@code RenderSystem.initRenderer(int, boolean)} at RETURN (VulkanMod's
 * renderer is up by then).
 *
 * <p>Why: VulkaIris strips Iris's own {@code MixinRenderSystem} (its texture-tracking injections clash
 * with VulkanMod), and that mixin is the only caller of Iris's render-system init. Without the init,
 * Iris never loads the pack named in {@code iris.properties} at startup (the log says "No shader pack
 * active" even with {@code enableShaders=true}), and VulkaIris's own {@code IrisRenderSystem.initRenderer}
 * overwrite never runs. This replays the same init sequence Iris runs there, minus the texture hooks.</p>
 *
 * <p>Without it: shader packs only load after the player re-applies one in the shader menu (if ever),
 * so a fresh start always shows vanilla.</p>
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemIrisInitMixin {
    private static final Logger VULKAIRIS$LOGGER = LoggerFactory.getLogger("Vulkairis/IrisInit");

    @Inject(method = "initRenderer(IZ)V", at = @At("RETURN"), remap = false)
    private static void vulkairis$initIris(int debugVerbosity, boolean sync, CallbackInfo ci) {
        // Same order as Iris's MixinRenderSystem.iris$onRendererInit; each step is isolated so one
        // GL-only query failing under Vulkan cannot stop the pack from loading.
        vulkairis$step("duringRenderSystemInit", net.irisshaders.iris.Iris::duringRenderSystemInit);
        vulkairis$step("GLDebug.reloadDebugState", net.irisshaders.iris.gl.GLDebug::reloadDebugState);
        vulkairis$step("IrisRenderSystem.initRenderer", net.irisshaders.iris.gl.IrisRenderSystem::initRenderer);
        vulkairis$step("IrisSamplers.initRenderer", net.irisshaders.iris.samplers.IrisSamplers::initRenderer);
        vulkairis$step("onRenderSystemInit", net.irisshaders.iris.Iris::onRenderSystemInit);
    }

    private static void vulkairis$step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            VULKAIRIS$LOGGER.warn("[Vulkairis/IrisInit] {} failed under Vulkan: {}", name, t.toString());
        }
    }
}
