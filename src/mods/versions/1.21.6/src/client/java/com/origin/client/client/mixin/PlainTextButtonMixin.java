package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Title footer: vanilla draws the copyright line pure white in Minecraft's font. On the Origin title it is
// Inter (TITLE_SMALL_EM) in textMuted, parchment on hover (DESIGN-SYSTEM 15.2, A-Workbench-3; DECISIONS
// 2026-10-25 allows Inter on Origin's own title text), or textMuted in Minecraft's font when Inter is not
// live. Only the one button TitleScreenMixin registered (OriginButtonRenderer.isTitleFooter) is touched;
// every other PlainTextButton draws exactly as vanilla. Its hit box is sized to the Inter text by
// TitleScreenMixin.originclient$footerSeat.
// Target: PlainTextButton.renderWidget's one GuiGraphics.drawString(Font, Component, int, int, int) call.
// PER-VERSION (1.21.6-1.21.8, javap): that drawString returns void (1.21.1's returns int).
// If it stops applying, the copyright simply stays vanilla white.
@Mixin(PlainTextButton.class)
public abstract class PlainTextButtonMixin {

	@Redirect(method = "renderWidget", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$footerText(GuiGraphics g, Font font, Component text, int x, int y, int color) {
		PlainTextButton self = (PlainTextButton) (Object) this;
		if (!OriginButtonRenderer.isTitleFooter(self)) {
			g.drawString(font, text, x, y, color);
			return;
		}
		int c = OriginButtonRenderer.footerColor(self.isHoveredOrFocused(), color);
		if (!OriginButtonRenderer.renderTitleFooterButton(g, self, x, y, c)) {
			g.drawString(font, text, x, y, c);
		}
	}
}
