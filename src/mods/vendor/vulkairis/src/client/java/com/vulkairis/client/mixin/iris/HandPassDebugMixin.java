package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.pathways.HandRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person hand diagnostic (implementation plan Phase 10).
 *
 * <p>Vulkairis's pre-launch mixin strip lists remove Iris's hand orchestration mixins
 * ({@code MixinGameRenderer}/{@code MixinLevelRenderer}) while leaving others active, so it is not
 * currently provable whether the hand is drawn by Iris's {@link HandRenderer} or by vanilla. This
 * optional mixin logs once per session which hand path actually fires, so the missing-hand defect
 * can be attributed to either the Iris pass or the vanilla/attachment path.</p>
 *
 * <p>{@code require = 0} keeps it from breaking startup if Iris renames these methods.</p>
 */
@Mixin(value = HandRenderer.class, remap = false)
public class HandPassDebugMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LoggerFactory.getLogger("Vulkairis/HandPass");

    @Unique
    private static boolean vulkairis$solidLogged = false;
    @Unique
    private static boolean vulkairis$translucentLogged = false;

    @Inject(method = "renderSolid", at = @At("HEAD"), require = 0, remap = false)
    private void vulkairis$onRenderSolid(CallbackInfo ci) {
        com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter.getInstance()
                .onStageObserved(com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.FIRST_PERSON_HAND, "Iris HandRenderer.renderSolid");
        if (!vulkairis$solidLogged) {
            vulkairis$solidLogged = true;
            VULKAIRIS_LOGGER.info("[Vulkairis/HandPass] Iris HandRenderer.renderSolid fired (Iris owns the first-person hand)");
        }
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0, remap = false)
    private void vulkairis$onRenderTranslucent(CallbackInfo ci) {
        com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter.getInstance()
                .onStageObserved(com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.FIRST_PERSON_HAND, "Iris HandRenderer.renderTranslucent");
        if (!vulkairis$translucentLogged) {
            vulkairis$translucentLogged = true;
            VULKAIRIS_LOGGER.info("[Vulkairis/HandPass] Iris HandRenderer.renderTranslucent fired");
        }
    }
}
