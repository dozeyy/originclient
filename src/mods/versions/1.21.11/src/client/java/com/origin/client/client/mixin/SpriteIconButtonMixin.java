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
// PER-VERSION DELTA (1.21.11): AbstractButton.renderWidget is now `protected
// final` and just delegates to the abstract renderContents(...) -- subclasses
// can no longer override renderWidget at all, they override renderContents
// instead. So this targets renderContents (declared directly on CenteredIcon/
// TextAndIcon, per vanilla source) at HEAD instead of renderWidget; the
// injection point is otherwise identical since renderWidget does nothing but
// call renderContents. priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = {SpriteIconButton.CenteredIcon.class, SpriteIconButton.TextAndIcon.class}, priority = 2000)
public abstract class SpriteIconButtonMixin {

	@Inject(method = "renderContents", at = @At("HEAD"), cancellable = true)
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
