package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Restyles every button in the Origin look, on every screen: cancels the
// vanilla button drawing and draws the Origin style instead. Restyling
// happens in-place (no widgets added/removed) -- buttons keep their
// positions, actions, and clicks; only renderWidget is replaced.
//
// Coverage is naturally scoped by the class hierarchy: this mixin applies to
// AbstractButton.renderWidget, so subclasses with their OWN renderWidget
// override (ImageButton, SpriteIconButton, Checkbox, sliders...) bypass it
// and keep vanilla rendering until each gets its own styled pass. Plain
// Button and CycleButton (the "Something: Value" toggles all over the
// Options menus) don't override it, so they're covered here. Dynamic labels
// with no baked Inter texture fall back to vanilla font inside the Origin
// shell, per the settled font decision. Disabled buttons (active=false,
// e.g. Telemetry Data) render the dimmed Origin style and skip hover.
@Mixin(AbstractButton.class)
public class AbstractButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when Origin actually drew -- if the styled draw
		// ever fails (e.g. on a different game version), vanilla buttons return.
		if (OriginButtonRenderer.render(guiGraphics, (AbstractButton) (Object) this)) {
			ci.cancel();
		}
	}
}
