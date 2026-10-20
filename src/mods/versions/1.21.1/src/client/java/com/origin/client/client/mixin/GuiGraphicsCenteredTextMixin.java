package com.origin.client.client.mixin;

import com.origin.client.client.gui.MenuText;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: GuiGraphics.drawCenteredString(Font,String,int,int,int) and
// (Font,Component,int,int,int) (javap, mapped 1.21.1; the FormattedCharSequence
// overload is left alone -- pre-wrapped text keeps the vanilla font).
// Why: most of Minecraft's menu titles and headings ("Options", "Select
// World", "Controls", status lines) are drawn through these two calls. Inside
// a vanilla menu frame (MenuTextScopeMixin) they draw in Origin's Inter face at
// the mockup's title size, caps centred where vanilla's glyphs sat. Text the
// SDF atlas can't draw, or a component in several colours, keeps vanilla.
// If this stops applying: those titles stay in the vanilla font.
@Mixin(GuiGraphics.class)
public class GuiGraphicsCenteredTextMixin {

	@Inject(method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
			at = @At("HEAD"), cancellable = true)
	private void originclient$centeredString(Font font, String text, int x, int y, int color, CallbackInfo ci) {
		if (text != null && MenuText.inScope() && originclient$draw(text, x, y, MenuText.vanillaColor(color))) {
			ci.cancel();
		}
	}

	@Inject(method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
			at = @At("HEAD"), cancellable = true)
	private void originclient$centeredComponent(Font font, Component text, int x, int y, int color, CallbackInfo ci) {
		if (text == null || !MenuText.inScope()) {
			return;
		}
		Integer one = MenuText.singleColor(text, MenuText.vanillaColor(color));
		if (one != null && originclient$draw(text.getString(), x, y, MenuText.vanillaColor(one))) {
			ci.cancel();
		}
	}

	private boolean originclient$draw(String s, int x, int y, int argb) {
		try {
			if (s.isEmpty() || !MenuText.usable(s)) {
				return false;
			}
			// Vanilla glyph caps run y .. y+7, so their middle is y + 3.5.
			MenuText.drawCentered((GuiGraphics) (Object) this, s, x, y + 3.5, OriginTheme.CLEAN_EM_TITLE, 0f, argb);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
