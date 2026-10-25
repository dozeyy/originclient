package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Title footer (1.21.1's Workbench look): vanilla draws the copyright line pure white in Minecraft's
// font. On the Origin title it is Inter (TITLE_SMALL_EM) in textMuted, parchment on hover (DECISIONS
// 2026-10-25 allows Inter on Origin's own title text), or textMuted in Minecraft's font when Inter is
// not live. Only the one button TitleScreenMixin registered (OriginButtonRenderer.isTitleFooter) is
// touched; every other PlainTextButton draws exactly as vanilla. Its hit box is sized to the Inter
// text by TitleScreenMixin.originclient$footerSeat.
// Target (javap 26.1.2 / 26.2 / 26.3): PlainTextButton.extractContents's one
// GuiGraphicsExtractor.text(Font, Component, int, int, int)V call. If it stops applying, the
// copyright simply stays vanilla white.
@Mixin(PlainTextButton.class)
public abstract class PlainTextButtonMixin {

	@Redirect(method = "extractContents", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$footerText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
		PlainTextButton self = (PlainTextButton) (Object) this;
		if (!OriginButtonRenderer.isTitleFooter(self)) {
			g.text(font, text, x, y, color);
			return;
		}
		int c = OriginButtonRenderer.footerColor(self.isHoveredOrFocused(), color);
		if (!OriginButtonRenderer.renderTitleFooterButton(g, self, x, y, c)) {
			g.text(font, text, x, y, c);
		}
	}
}
