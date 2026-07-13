package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2 blurs the menu backdrop via GuiGraphicsExtractor.blurBeforeThisStratum()
// (called before the widget stratum, gated by Options.getMenuBackgroundBlurriness).
// That single call is the chokepoint for ALL menu blur regardless of which screen
// method triggers it. Origin replaces the backdrop with its own crisp charcoal +
// rings, and its widgets/panels are intentionally translucent — so any blur bleeds
// through them as a grey haze. No-op the blur for out-of-world Origin menus
// (level == null) so the Origin look stays crisp; in-world screens (pause/options)
// keep vanilla's world blur.
@Mixin(value = GuiGraphicsExtractor.class, priority = 2000)
public class MenuBlurMixin {

	@Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
	private void originclient$noMenuBlur(CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}
}
