package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Title footer: vanilla draws the copyright line pure white. On the Origin title it is textMuted
// (text on hover), per DESIGN-SYSTEM 15.2. Only the one button TitleScreenMixin registered
// (OriginButtonRenderer.isTitleFooter) is touched; every other PlainTextButton keeps vanilla's colour.
// Target: PlainTextButton.renderWidget's GuiGraphics.drawString(Font, Component, int, int, int) colour
// argument (index 4). If it stops applying, the copyright simply stays white.
@Mixin(PlainTextButton.class)
public abstract class PlainTextButtonMixin {

	@ModifyArg(method = "renderWidget", index = 4, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
	private int originclient$footerColor(int color) {
		PlainTextButton self = (PlainTextButton) (Object) this;
		return OriginButtonRenderer.isTitleFooter(self) ? OriginButtonRenderer.footerColor(self.isHoveredOrFocused(), color) : color;
	}
}
