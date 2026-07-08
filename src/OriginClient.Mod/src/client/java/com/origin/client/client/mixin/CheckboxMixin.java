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
@Mixin(Checkbox.class)
public class CheckboxMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginButtonRenderer.renderCheckbox(guiGraphics, (Checkbox) (Object) this);
		ci.cancel();
	}
}
