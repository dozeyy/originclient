package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server..." draws the one Origin loading picture (LoadingLook: the
// real logo, the sweeping bar, the connection status as its caption), HEAD-cancelling
// vanilla's render, then draws the screen's own widgets (the Cancel button) on top so
// Cancel keeps working. Ported from 1.21.1 (2026-10-27): Cancel is registered at init (TAIL)
// as the Workbench loading button (OriginButtonRenderer.setLoadingButton: the title's card, its
// label Minecraft's font in parchment), LOADING_CANCEL_W x 20 GUI px, re-seated one picture step
// under the caption (OriginScreenRenderer.connectCancelTop). Its height is left alone: vanilla
// builds it 20 high already, and 1.20.2 has no setHeight (javap), so this fork never calls it.
// Vanilla's render() also says the narrator's "Joining world" line (sayNow, narrator.joining)
// every 2 s; the HEAD-cancel would drop it, so the mixin repeats that exact throttle
// (lastNarration, 2000 ms, javap ConnectScreen.render on 1.20-1.21.4) and screen-reader users
// still hear it. Main Menu Style = Vanilla keeps the stock screen and button position.
// In the isolated required:false config: if a target moves (the status / lastNarration
// shadows included), this mixin is skipped and the stock connect screen shows; nothing else breaks.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	// The status line vanilla shows ("Connecting to the server...", "Logging in...").
	@Shadow
	private Component status;

	// Vanilla's narrator throttle (ms timestamp of the last "Joining world" line).
	@Shadow
	private long lastNarration;

	// Target: ConnectScreen.init()V, TAIL, after vanilla adds the Cancel button (it runs
	// again on every resize, re-seating the rebuilt button). If it stops applying, Cancel
	// stays at vanilla's spot and may overlap the picture at small GUI scales.
	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			Screen self = (Screen) (Object) this;
			for (GuiEventListener child : self.children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setLoadingButton(button);
					if (!OriginStyle.vanillaMenusOn(this)) {
						// LOADING_CANCEL_W wide (vanilla mode keeps the stock 200 px button and spot).
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, self.width - 24));
						button.setWidth(bw);
						button.setX((self.width - bw) / 2);
						button.setY(Math.max(0, OriginScreenRenderer.connectCancelTop(self.height, button.getHeight())));
					}
				}
			}
		} catch (Throwable ignored) {
			// No registration: the stock button at vanilla's spot, still working.
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originConnecting(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		// Only cancel vanilla when the Origin picture actually drew (fail-soft).
		if (!OriginScreenRenderer.renderConnecting(guiGraphics, this.status)) {
			return;
		}
		ci.cancel();
		try {
			// Vanilla's narration, kept (javap ConnectScreen.render: 2000 ms throttle, narrator.joining).
			long now = Util.getMillis();
			if (now - this.lastNarration > 2000L) {
				this.lastNarration = now;
				Minecraft.getInstance().getNarrator().sayNow(Component.translatable("narrator.joining"));
			}
		} catch (Throwable ignored) {
			// Narration is a nicety: never take the connect screen down for it.
		}
		try {
			Screen self = (Screen) (Object) this;
			for (GuiEventListener child : self.children()) {
				if (child instanceof Renderable renderable) {
					renderable.render(guiGraphics, mouseX, mouseY, partialTick);
				}
			}
		} catch (Throwable ignored) {
			// A widget that fails to draw must not take the connect screen down.
		}
	}
}
