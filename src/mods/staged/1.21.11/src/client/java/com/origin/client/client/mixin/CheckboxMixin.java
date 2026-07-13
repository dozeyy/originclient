package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Restyles every checkbox in the Origin look, in place: rounded shell, accent
// inner square when selected, label to the right. Toggle logic untouched;
// selected() is a public accessor (javap-confirmed).
// priority 2000: Origin's checkbox restyle wins over other UI mods.
@Mixin(value = Checkbox.class, priority = 2000)
public class CheckboxMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderCheckbox(guiGraphics, (Checkbox) (Object) this)) {
			ci.cancel();
		}
	}
}
