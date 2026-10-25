package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Title footer: vanilla draws the copyright line pure white in Minecraft's font. On the Origin title it is
// Inter (TITLE_SMALL_EM) in textMuted, parchment on hover (DESIGN-SYSTEM 15.2, A-Workbench-3; DECISIONS
// 2026-10-25 allows Inter on Origin's own title text), or textMuted in Minecraft's font when Inter is not
// live. Only the one button TitleScreenMixin registered (OriginButtonRenderer.isTitleFooter) is touched;
// every other PlainTextButton draws exactly as vanilla. Its hit box is sized to the Inter text by
// TitleScreenMixin.originclient$footerSeat.
// Target (javap 1.19.2, Mojmap): PlainTextButton.renderButton(PoseStack, int, int, float)'s one call of the
// inherited GuiComponent static drawString(PoseStack, Font, Component, int, int, int)V, which the bytecode
// owns as PlainTextButton (1.20+ calls GuiGraphics.drawString instead). If it stops applying, the copyright
// simply stays vanilla white.
@Mixin(PlainTextButton.class)
public abstract class PlainTextButtonMixin {
	// One wrapper for the game's life, re-pointed at each frame's PoseStack (no per-frame allocation).
	@Unique
	private static Gfx originclient$gfx;

	@Redirect(method = "renderButton", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/PlainTextButton;drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$footerText(PoseStack pose, Font font, Component text, int x, int y, int color) {
		PlainTextButton self = (PlainTextButton) (Object) this;
		if (!OriginButtonRenderer.isTitleFooter(self)) {
			GuiComponent.drawString(pose, font, text, x, y, color);
			return;
		}
		int c = OriginButtonRenderer.footerColor(self.isHoveredOrFocused(), color);
		Gfx g = originclient$gfx == null ? (originclient$gfx = new Gfx(pose)) : originclient$gfx.rebind(pose);
		if (!OriginButtonRenderer.renderTitleFooterButton(g, self, x, y, c)) {
			GuiComponent.drawString(pose, font, text, x, y, c);
		}
	}
}
