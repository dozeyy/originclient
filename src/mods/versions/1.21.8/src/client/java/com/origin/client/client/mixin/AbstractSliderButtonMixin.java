package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Frost-style slider skin (FOV, volumes, mouse sensitivity, ...): vanilla's
// drag/click/keyboard logic is untouched, only the drawing is replaced. `value`
// is a protected field declared directly on AbstractSliderButton
// (javap-confirmed for 1.21.1) -- a declared-field @Shadow, the safe case.
// priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = AbstractSliderButton.class, priority = 2000)
public class AbstractSliderButtonMixin {

	@Shadow
	protected double value;

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderSlider(guiGraphics, (AbstractSliderButton) (Object) this, this.value)) {
			ci.cancel();
		}
	}
}
