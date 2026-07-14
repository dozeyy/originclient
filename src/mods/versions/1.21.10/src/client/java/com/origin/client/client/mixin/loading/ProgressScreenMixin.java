package com.origin.client.client.mixin.loading;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Preparing to create world" / world-creation progress stages. HEAD-cancel
// takeover -> the Origin loading scene. The generation logic runs on the
// integrated-server thread via the ProgressListener interface, independent of
// render(), so replacing the draw is safe.
@Mixin(ProgressScreen.class)
public class ProgressScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, ((Screen) (Object) this).getTitle())) {
			ci.cancel();
		}
	}
}
