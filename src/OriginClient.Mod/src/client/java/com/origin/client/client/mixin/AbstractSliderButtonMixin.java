package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Restyles every slider (FOV, volumes, mouse sensitivity, ...) in the Origin
// look, in place: vanilla's drag/click/keyboard logic is untouched, only the
// drawing is replaced. `value` is a protected field declared directly on
// AbstractSliderButton (javap-confirmed) -- a declared-field @Shadow, the safe
// case, unlike the inherited-method shadow that failed before.
@Mixin(AbstractSliderButton.class)
public class AbstractSliderButtonMixin {

	@Shadow
	protected double value;

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginButtonRenderer.renderSlider(guiGraphics, (AbstractSliderButton) (Object) this, this.value);
		ci.cancel();
	}
}
