package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Frost-style button skin on every screen (title, pause, options, ...): cancels
// vanilla's button draw and draws the flat translucent-dark square instead.
// In place -- buttons keep positions, actions, clicks; only renderWidget changes.
//
// Coverage is scoped by the class hierarchy: this applies to
// AbstractButton.renderWidget, so plain Button and CycleButton (the
// "Setting: Value" toggles across the Options menus) are covered, while
// subclasses with their OWN renderWidget (sliders, checkboxes) get their own
// mixin. priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = AbstractButton.class, priority = 2000)
public class AbstractButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.render(guiGraphics, (AbstractButton) (Object) this)) {
			ci.cancel();
		}
	}
}
