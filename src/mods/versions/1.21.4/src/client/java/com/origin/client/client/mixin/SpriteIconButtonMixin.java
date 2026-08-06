package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.SpriteIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Frost-style skin for the sprite-icon buttons -- the Accessibility (person) and
// Language (globe) buttons on the title screen, which the 1.21.1 baseline KEEPS
// and re-skins (this version used to hide them instead).
//
// These are SpriteIconButton.CenteredIcon / TextAndIcon, which OVERRIDE
// renderWidget and keep drawing the vanilla stone sprite, so the plain
// AbstractButtonMixin never restyles them. The icon geometry lives in protected
// fields on the shared SpriteIconButton superclass -- read through
// SpriteIconButtonAccessor, because a @Shadow from the subclasses is rejected.
// priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = {SpriteIconButton.CenteredIcon.class, SpriteIconButton.TextAndIcon.class}, priority = 2000)
public abstract class SpriteIconButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originIcon(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		SpriteIconButton self = (SpriteIconButton) (Object) this;
		SpriteIconButtonAccessor acc = (SpriteIconButtonAccessor) (Object) this;
		int iw = acc.originclient$spriteWidth();
		int ih = acc.originclient$spriteHeight();
		int ix = self.getX() + self.getWidth() / 2 - iw / 2;
		int iy = self.getY() + self.getHeight() / 2 - ih / 2;
		// Only cancel vanilla when Origin actually drew (fail-soft contract).
		if (OriginButtonRenderer.renderIconButton(guiGraphics, self, iw, ih,
				() -> guiGraphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, acc.originclient$sprite(), ix, iy, iw, ih))) {
			ci.cancel();
		}
	}
}
