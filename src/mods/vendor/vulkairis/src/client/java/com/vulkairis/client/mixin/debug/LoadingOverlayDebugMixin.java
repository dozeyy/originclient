package com.vulkairis.client.mixin.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayDebugMixin {
    private static final Logger LOGGER = LogManager.getLogger("Vulkairis/LoadingOverlay");
    private static int renderCount = 0;

    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;
    @Shadow private boolean fadeIn;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(CallbackInfo ci) {
        renderCount++;
        if (renderCount % 60 == 1) { // Log once per second
            LOGGER.info("[Vulkairis] LoadingOverlay.render() frame={}, fadeIn={}, fadeInStart={}, fadeOutStart={}",
                    renderCount, fadeIn, fadeInStart, fadeOutStart);
        }
    }
}
