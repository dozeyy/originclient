package com.origin.client.mixin;

import com.origin.client.render.OriginBranding;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla main-menu panorama + Mojang logo with the Origin
 * backdrop (near-black, orbital rings, grain, vignette) and the "ORIGIN"
 * wordmark, while keeping the vanilla buttons — the classic-version twin of the
 * Fabric build's TitleScreen treatment. Fail-soft: if the Origin backdrop can't
 * draw, the injection returns without cancelling and the vanilla menu shows.
 */
@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu extends GuiScreen {
    @Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
    private void originclient$drawBrandedMenu(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!OriginBranding.isActive()) {
            return;
        }
        if (!OriginBranding.renderTitleBackground(this.width, this.height)) {
            return;
        }
        OriginBranding.renderTitleWordmark(this.width, this.height);
        // Draw the widgets (buttons + labels) over the Origin backdrop, then
        // skip vanilla's own panorama/logo/splash rendering.
        super.drawScreen(mouseX, mouseY, partialTicks);
        ci.cancel();
    }
}
