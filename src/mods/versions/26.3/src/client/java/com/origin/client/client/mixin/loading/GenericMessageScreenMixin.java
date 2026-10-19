package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
// loading scene with the screen's message as its title.
// required:false: on versions where GenericMessageScreen has no renderBackground
// override (<=1.20.x), the target is absent and this is skipped, and Screen's own
// mixin already handles the background there.
@Mixin(GenericMessageScreen.class)
public class GenericMessageScreenMixin {

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// The whole Origin loading scene, title included ("Preparing for world
		// creation...", "Saving world"), as on 1.21.1. Vanilla's own label is a
		// boxed FocusableTextWidget that would draw over it, so it is hidden for
		// this frame's widget pass, which runs right after the background; a
		// failed Origin draw leaves it visible over vanilla's background.
		net.minecraft.client.gui.screens.Screen self = (net.minecraft.client.gui.screens.Screen) (Object) this;
		if (OriginStyle.vanillaMenusOn(self)) {
			return;   // vanilla mode: stock "Preparing..." screen
		}
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, self.getTitle())) {
			for (net.minecraft.client.gui.components.events.GuiEventListener child : self.children()) {
				if (child instanceof net.minecraft.client.gui.components.FocusableTextWidget label) {
					label.visible = false;
				}
			}
			ci.cancel();
		}
	}
}
