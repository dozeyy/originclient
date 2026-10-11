package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.SpriteIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Frost-style skin for the sprite-icon buttons -- the Accessibility (person) and
// Language (globe) buttons on the title screen, plus any icon+text button.
//
// These are SpriteIconButton.CenteredIcon / TextAndIcon, which OVERRIDE
// renderWidget and (via their super call) keep drawing the vanilla stone button
// sprite, so the plain AbstractButtonMixin never restyles them. This targets
// their own renderWidget at HEAD, cancels it, and draws the Frost box + icon
// (+ the subclass's own text) instead. The needed geometry lives in protected
// fields on the shared SpriteIconButton superclass -- shadowed here and handed
// to the renderer. priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = {SpriteIconButton.CenteredIcon.class, SpriteIconButton.TextAndIcon.class}, priority = 2000)
public abstract class SpriteIconButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originIcon(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		SpriteIconButton self = (SpriteIconButton) (Object) this;
		// Icon geometry via the accessor on the declaring class (see
		// SpriteIconButtonAccessor -- a @Shadow from these subclasses is rejected).
		SpriteIconButtonAccessor acc = (SpriteIconButtonAccessor) (Object) this;
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderIconButton(guiGraphics, self,
				acc.originclient$sprite(), acc.originclient$spriteWidth(), acc.originclient$spriteHeight())) {
			ci.cancel();
		}
	}
}
