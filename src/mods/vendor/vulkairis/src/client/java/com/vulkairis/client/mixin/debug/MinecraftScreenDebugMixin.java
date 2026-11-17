package com.vulkairis.client.mixin.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftScreenDebugMixin {
    private static final Logger LOGGER = LogManager.getLogger("Vulkairis/ScreenDebug");

    @Inject(method = "setOverlay", at = @At("HEAD"))
    private void onSetOverlay(Overlay overlay, CallbackInfo ci) {
        LOGGER.info("[Vulkairis] Minecraft.setOverlay called with: {}", overlay != null ? overlay.getClass().getSimpleName() : "null");
    }

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        LOGGER.info("[Vulkairis] Minecraft.setScreen called with: {}", screen != null ? screen.getClass().getSimpleName() : "null");
    }
}
