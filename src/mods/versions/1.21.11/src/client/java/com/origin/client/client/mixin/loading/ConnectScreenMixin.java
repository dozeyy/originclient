package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server..." draws the one Origin loading picture (the real logo,
// the plain bar sweeping, the connection status as its caption), the same picture
// as every loading screen (1.21.1's look). PER-VERSION (1.21.6+): no HEAD-cancel
// takeover like 1.21.1's. Vanilla's render runs as usual (the menu backdrop from
// ScreenBackgroundMixin, the Cancel button, and the narrator's "Joining world" line,
// which here is GameNarrator.saySystemNow); this mixin only drops vanilla's own status
// line (it becomes the caption) and draws the picture at TAIL. Cancel is re-seated
// under the picture at init. javap ConnectScreen (1.21.6 / 1.21.8 / 1.21.10 / 1.21.11):
// render(GuiGraphics,int,int,float) calls Screen.render, the narration, then exactly one
// GuiGraphics.drawCenteredString(Font,Component,int,int,int) for the status; init()V adds
// the Cancel button. In the required:false loading config (defaultRequire 0): if the wrap
// stops applying, vanilla's status line also shows above the picture; if the TAIL stops
// applying, the stock connect screen shows. Nothing crashes either way.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	// The status line vanilla shows ("Connecting to the server...", "Logging in...").
	@Shadow
	private Component status;

	// Target: ConnectScreen.init()V, TAIL, after vanilla adds the Cancel button (it runs
	// again on every resize). Cancel goes centred at 62 % of the height or lower, always
	// under the picture (the logo never moves for it). If it stops applying, Cancel stays
	// at vanilla's spot, which can overlap the caption on short windows.
	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$seatCancel(CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.isActive()) {
			return;   // vanilla mode, or Origin drawing is off: the stock layout
		}
		try {
			Screen self = (Screen) (Object) this;
			for (GuiEventListener child : self.children()) {
				if (child instanceof Button button) {
					int top = OriginScreenRenderer.connectCancelTop(self.height, button.getHeight());
					button.setX((self.width - button.getWidth()) / 2);
					button.setY(top);
				}
			}
		} catch (Throwable ignored) {
			// The stock position, still working.
		}
	}

	// Vanilla's status line: dropped while Origin draws the picture (its caption shows the
	// same status). Drawn as vanilla's whenever Origin's picture is off or has failed.
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$dropStatusLine(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int color,
											 Operation<Void> original) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.isActive()) {
			original.call(guiGraphics, font, text, x, y, color);
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void originclient$originPicture(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: the stock screen
		}
		OriginScreenRenderer.renderConnecting(guiGraphics, this.status);
	}
}
