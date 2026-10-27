package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
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

// "Connecting to the server..." draws the one Origin loading picture (opaque flat backdrop,
// the real logo, the plain bar sweeping, the connection status as its caption), the same
// picture as every loading screen (1.21.1's look). Cancel is registered at init (TAIL) as
// the loading Cancel (OriginButtonRenderer.setLoadingButton: the Workbench card, its label in
// Minecraft's font), LOADING_CANCEL_W x 20 GUI px, one picture step under the caption
// (OriginScreenRenderer.connectCancelTop); the logo stays where every loading screen has it.
//
// PER-VERSION (1.21.6+): no HEAD-cancel takeover like 1.21.1's. The picture is drawn at
// render HEAD (not cancelled) and vanilla's render runs on: deferred GUI layers by submission
// order, so the Cancel button Screen.render submits next draws over the opaque picture, and the
// narrator's "Joining world" line (GameNarrator.saySystemNow) stays vanilla's own. This mixin
// drops vanilla's status line (it becomes the caption). ScreenBackgroundMixin skips the menu
// backdrop under it (renderBackground runs before render in this era and the picture is opaque).
// javap ConnectScreen (1.21.6 / 1.21.8 / 1.21.10 / 1.21.11): render(GuiGraphics,int,int,float)
// calls Screen.render, the narration, then exactly one
// GuiGraphics.drawCenteredString(Font,Component,int,int,int) for the status; init()V adds the
// Cancel button. In the required:false loading config (defaultRequire 0): if the wrap stops
// applying, vanilla's status line also shows over the picture; if the HEAD stops applying, the
// stock connect screen shows. Nothing crashes either way. The Main Menu Style = Vanilla check
// is in the skin itself, so vanilla mode keeps the stock button.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	// The status line vanilla shows ("Connecting to the server...", "Logging in...").
	@Shadow
	private Component status;

	// Target: ConnectScreen.init()V, TAIL, after vanilla adds the Cancel button (it runs again on
	// every resize, re-registering the rebuilt button). If it stops applying, Cancel draws as the
	// stock button at vanilla's spot, which can overlap the caption on short windows.
	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$seatCancel(CallbackInfo ci) {
		try {
			Screen self = (Screen) (Object) this;
			boolean seat = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive();
			for (GuiEventListener child : self.children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setLoadingButton(button);
					if (seat) {
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, self.width - 24));
						int bh = 20;
						button.setWidth(bw);
						button.setHeight(bh);
						button.setX((self.width - bw) / 2);
						button.setY(OriginScreenRenderer.connectCancelTop(self.height, bh));
					}
				}
			}
		} catch (Throwable ignored) {
			// No registration: the stock button, still working.
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

	// Target: ConnectScreen.render HEAD, not cancelled: the picture first, then vanilla's widgets over it.
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$originPicture(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: the stock screen
		}
		OriginScreenRenderer.renderConnecting(guiGraphics, this.status);
	}
}
