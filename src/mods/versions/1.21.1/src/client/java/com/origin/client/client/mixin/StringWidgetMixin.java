package com.origin.client.client.mixin;

import com.origin.client.client.gui.MenuText;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: StringWidget.renderWidget(GuiGraphics,int,int,float) and its
// `private float alignX` (javap, mapped 1.21.1). Vanilla draws the text at
// x = getX() + round(alignX * (width - textWidth)), y = getY() + (height-9)/2,
// clipping with an ellipsis when it doesn't fit.
// Why: the headers of the options screens (HeaderAndFooterLayout titles) and
// form labels are StringWidgets. Inside a vanilla menu frame
// (MenuTextScopeMixin) they draw in Origin's Inter face -- centred ones at the
// title size, left-aligned labels at the label size -- at the same alignment.
// Text wider than the widget, mixed-colour text, or glyphs the atlas lacks
// keep vanilla's own clipped draw.
// If this stops applying: those headers stay in the vanilla font.
@Mixin(StringWidget.class)
public abstract class StringWidgetMixin extends AbstractStringWidget {

	@Shadow
	private float alignX;

	protected StringWidgetMixin(int x, int y, int w, int h, Component message, Font font) {
		super(x, y, w, h, message, font);
	}

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void originclient$cleanText(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!MenuText.inScope()) {
			return;
		}
		try {
			Component msg = getMessage();
			String s = msg.getString();
			if (s.isEmpty() || !MenuText.usable(s)) {
				return;
			}
			Integer col = MenuText.singleColor(msg, MenuText.vanillaColor(getColor()));
			if (col == null) {
				return;
			}
			int argb = MenuText.vanillaColor(col);
			int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, this.alpha)));
			argb = (a << 24) | (argb & 0xFFFFFF);
			float em = alignX > 0.25f ? OriginTheme.CLEAN_EM_TITLE : OriginTheme.CLEAN_EM_LABEL;
			float tw = MenuText.width(s, em);
			if (tw > getWidth()) {
				return;   // vanilla clips it with an ellipsis instead
			}
			double x = getX() + alignX * (getWidth() - tw);
			double mid = getY() + (getHeight() - 9) / 2 + 3.5;
			MenuText.drawMid(g, s, x, mid, em, 0f, argb);
			ci.cancel();
		} catch (Throwable ignored) {
			// vanilla draws it
		}
	}
}
