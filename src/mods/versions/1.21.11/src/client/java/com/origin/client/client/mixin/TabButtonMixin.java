package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.TabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The Game/World/More header tabs (Create World and friends) render their own
// nine-sliced dirt button texture. Restyle them in place to the Frost look
// (translucent-dark box + accent underline on the selected tab), keeping their
// clicks/selection logic untouched. Fail-soft: if the Origin draw throws,
// vanilla tabs return (renderTab returns false).
//
// PER-VERSION DELTA (1.21.11): TabButton extends AbstractWidget.WithInactiveMessage,
// NOT AbstractButton -- it still declares its own directly-overridable
// renderWidget(...) (unlike SpriteIconButton's CenteredIcon/TextAndIcon, which
// route through AbstractButton's now-final renderWidget -> renderContents).
// So this injection point is unchanged from 1.21.1.
// priority 2000: Origin's restyle wins over other UI mods.
@Mixin(value = TabButton.class, priority = 2000)
public class TabButtonMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$originTab(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		TabButton self = (TabButton) (Object) this;
		if (OriginButtonRenderer.renderTab(guiGraphics, self, self.getX(), self.getY(),
				self.getWidth(), self.getHeight(), self.getMessage(), self.isSelected(), self.isHovered())) {
			ci.cancel();
		}
	}
}
