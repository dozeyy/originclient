package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server...": the same loading picture as every other loading
// screen (the official logo at 44 %, the plain bar with a segment sliding along
// it, the connection status as the caption; OriginScreenRenderer.renderConnecting),
// matching 1.21.1. The backdrop is the Origin menu backdrop (ScreenBackgroundMixin,
// level == null while connecting) and the Cancel button stays vanilla's own widget.
//
// 26.x order (javap on 26.1.2/26.2/26.3): extractRenderState calls
// super.extractRenderState (backdrop + widgets), then the narration tick
// (GameNarrator "narrator.joining", every 2 s), then ONE GuiGraphicsExtractor.centeredText
// for the status line. So, unlike 1.21.1's HEAD cancel, this keeps the method:
//   - init TAIL: the Cancel button is seated below the picture (connectCancelTop);
//   - the status centeredText is redirected away while Origin draws the picture;
//   - TAIL: the picture (logo, bar, caption) over the widgets.
// Vanilla mode or a failed Origin draw keeps vanilla's status line. If these stop
// applying: the screen falls back to vanilla's status text and button position;
// clicking Cancel and the narration are never touched.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	@Shadow
	private Component status;

	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$seatCancel(CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.isActive()) {
			return;
		}
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof Button button) {
				button.setY(OriginScreenRenderer.connectCancelTop(self.height, button.getHeight()));
			}
		}
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$statusLine(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
		if (!OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive()) {
			return;   // the status is the picture's caption (TAIL)
		}
		g.centeredText(font, text, x, y, color);
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void originclient$picture(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.isActive()) {
			return;
		}
		OriginScreenRenderer.renderConnecting(g, this.status);
	}
}
