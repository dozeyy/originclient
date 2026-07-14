package com.origin.client.client.mixin.loading;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The "Preparing Resources..." screen shown while a world loads is a
// GenericMessageScreen. On 1.21.1+ it OVERRIDES renderBackground to draw the
// blurred game world, which bypasses ScreenBackgroundMixin (that targets
// Screen.renderBackground, not this subclass override). So world entry showed
// the vanilla blur instead of the Origin backdrop. Replace it with the Origin
// loading backdrop (bg + bar, no title — the vanilla message text stays on top).
// required:false: on versions where GenericMessageScreen has no renderBackground
// override (<=1.20.x), the target is absent and this is skipped, and Screen's own
// mixin already handles the background there.
@Mixin(GenericMessageScreen.class)
public class GenericMessageScreenMixin {

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// null title -> Origin draws its backdrop + indeterminate bar only; the
		// screen's own "Preparing Resources..." label renders over it as usual.
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, null)) {
			ci.cancel();
		}
	}
}
