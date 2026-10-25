package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
// Cancel keeps working. Ported from 1.21.1 (rings out, 2026-10-25); this fork keeps
// vanilla's own Cancel button (its stock 200 x 20 size: 1.20.2 has no setHeight) and
// only re-seats it at init (TAIL) below the picture (OriginScreenRenderer.connectCancelTop),
// because vanilla's h/4 + 132 overlaps the logo at GUI scale 1-2.
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
	private void originclient$seatCancel(CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock layout
		}
		try {
			Screen self = (Screen) (Object) this;
			for (GuiEventListener child : self.children()) {
				if (child instanceof Button button) {
					button.setX((self.width - button.getWidth()) / 2);
					button.setY(OriginScreenRenderer.connectCancelTop(self.height, button.getHeight()));
				}
			}
		} catch (Throwable ignored) {
			// Vanilla's position: the button still works.
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
