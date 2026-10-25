package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Title screen only: tooltip text in Inter (TITLE_SMALL_EM, parchment) inside Origin's tooltip panel
// (TooltipRenderUtilMixin), so the title's own text is one typeface (judge 2026-10-25). Both hooks ask
// OriginButtonRenderer, which answers "vanilla" on every other screen, for the vanilla Main Menu Style, when
// Inter is not live, or after a latched failure; then nothing changes. getWidth must agree with renderText,
// since vanilla sizes the panel from it.
// Targets (javap 1.21.6-1.21.8): ClientTextTooltip.getWidth(Font)I and renderText(GuiGraphics, Font, int,
// int)V, HEAD -- this era hands renderText the GuiGraphics (1.21.1 passed a Matrix4f + BufferSource).
// If either stops applying, title tooltips keep Minecraft's font.
@Mixin(ClientTextTooltip.class)
public abstract class ClientTextTooltipMixin {
	@Shadow
	@Final
	private FormattedCharSequence text;

	@Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
	private void originclient$titleWidth(Font font, CallbackInfoReturnable<Integer> cir) {
		int w = OriginButtonRenderer.titleTipWidth(this.text);
		if (w >= 0) {
			cir.setReturnValue(w);
		}
	}

	@Inject(method = "renderText", at = @At("HEAD"), cancellable = true)
	private void originclient$titleText(GuiGraphics g, Font font, int x, int y, CallbackInfo ci) {
		if (OriginButtonRenderer.renderTitleTip(this.text, x, y, g)) {
			ci.cancel();
		}
	}
}
