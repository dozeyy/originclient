package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderPackScreen.class)
public class ShaderPackScreenMixin {

    /**
     * Render dark translucent background gradient behind ShaderPackScreen widgets
     * so that text and buttons are clearly legible on top of raw 3D scene.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void vulkairis$renderBackgroundGradient(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        guiGraphics.fillGradient(0, 0, guiGraphics.guiWidth(), guiGraphics.guiHeight(), 0x90050505, 0xB8101010);
    }
}
