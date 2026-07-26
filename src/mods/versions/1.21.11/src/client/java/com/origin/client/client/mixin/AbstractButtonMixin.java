package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Restyles every button in the Origin look, on every screen: cancels the
// vanilla button drawing and draws the Origin style instead. Restyling
// happens in-place (no widgets added/removed) -- buttons keep their
// positions, actions, and clicks; only renderWidget is replaced.
//
// PER-VERSION DELTA (1.21.11): on 1.21.1 coverage scoped ITSELF by the class
// hierarchy -- subclasses with their own renderWidget override (ImageButton,
// SpriteIconButton, Checkbox, sliders) simply bypassed this mixin. That is no
// longer true here: 1.21.11 made AbstractButton.renderWidget `protected final`
// and moved subclass drawing into the abstract renderContents(...), so there is
// exactly ONE renderWidget in the whole hierarchy and this inject pre-empts
// every button type, including ones with their own styled pass.
//
// So the scoping has to be explicit now. SpriteIconButton is excluded below and
// handled by SpriteIconButtonMixin (which injects renderContents): without the
// exclusion this generic path wins the race and draws the icon buttons as plain
// labelled buttons -- the title screen's Language / Accessibility icons rendered
// their full message text ("Language", "Accessibility Settings") sprawling out
// of a 20px-wide box and overlapping the Options/Quit row.
//
// Plain Button and CycleButton (the "Something: Value" toggles all over the
// Options menus) are covered here. Dynamic labels with no baked Inter texture
// fall back to vanilla font inside the Origin shell, per the settled font
// decision. Disabled buttons (active=false, e.g. Telemetry Data) render the
// dimmed Origin style and skip hover.
// priority 2000: Origin's widget restyle wins over other UI mods.
@Mixin(value = AbstractButton.class, priority = 2000)
public class AbstractButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originStyle(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Icon buttons get their own styled pass in SpriteIconButtonMixin --
		// leave renderWidget alone so it reaches their renderContents.
		if ((Object) this instanceof SpriteIconButton) {
			return;
		}
		// Only cancel vanilla when Origin actually drew -- if the styled draw
		// ever fails (e.g. on a different game version), vanilla buttons return.
		if (OriginButtonRenderer.render(guiGraphics, (AbstractButton) (Object) this)) {
			ci.cancel();
		}
	}
}
