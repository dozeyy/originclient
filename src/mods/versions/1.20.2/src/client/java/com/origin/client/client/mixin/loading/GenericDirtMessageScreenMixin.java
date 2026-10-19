package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The "Preparing Resources..." / "Saving world" screen is GenericDirtMessageScreen
// on 1.20.x (GenericMessageScreen from 1.20.5, where 1.21.1 hooks renderBackground).
// Same result as 1.21.1: the whole Origin loading scene with the screen's message
// as its title. Here that is a HEAD-cancel of render(): vanilla's render only draws
// the dirt, the centred title and an empty widget list, so nothing is lost. It only
// cancels when the scene actually drew; a failed Origin draw leaves vanilla's screen.
@Mixin(GenericDirtMessageScreen.class)
public class GenericDirtMessageScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: the stock dirt screen
		}
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, ((Screen) (Object) this).getTitle())) {
			ci.cancel();
		}
	}
}
