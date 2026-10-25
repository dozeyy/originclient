package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.components.AbstractWidget;
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

// "Connecting to the server..." draws the one Origin loading picture (logo, rail,
// the connection status as its caption), HEAD-cancelling vanilla's render, then
// draws the screen's own widgets (the Cancel button) on top so Cancel keeps
// working. Cancel is registered at init (TAIL) as a Workbench title button
// (OriginButtonRenderer.setTitleButton, DESIGN-SYSTEM 15.2): the same soft panel
// as the title's buttons, at 200 x 20 GUI px (the title's main button size, TitleLayout
// MAIN_W x BUTTON_H), re-seated centred at 62 % of the height (A-Workbench-3). Without that registration it drew
// as the stock stone button (judge 2026-10-25). The Main Menu Style = Vanilla
// check is in the skin itself, so vanilla mode keeps the stock button. The picture
// is lifted above the button when the window is short (OriginScreenRenderer.renderConnecting).
// Vanilla's render() also says the narrator's "Joining world" line (sayNow, narrator.joining)
// every 2 s; the HEAD-cancel would drop it, so the mixin repeats that exact throttle
// (lastNarration, 2000 ms) itself and screen-reader users still hear it. If the
// lastNarration shadow stops applying, this whole mixin is skipped (required:false): the stock connect screen, narration included.
// In the isolated required:false config: if a target moves, that injection is
// skipped (no skin, or the stock connect screen) and nothing else breaks.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	// The status line vanilla shows ("Connecting to the server...", "Logging in...").
	@Shadow
	private Component status;

	// Vanilla's narrator throttle (ms timestamp of the last "Joining world" line).
	@Shadow
	private long lastNarration;

	// Target: ConnectScreen.init()V, TAIL, after vanilla adds the Cancel button (it
	// runs again on every resize, re-registering the rebuilt button). If it stops
	// applying, Cancel draws as the stock stone button over the Origin picture.
	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			Screen self = (Screen) (Object) this;
			boolean seat = !OriginStyle.vanillaMenusOn(this);
			for (GuiEventListener child : self.children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setTitleButton(button, null);
					if (seat) {
						// A-Workbench-3: Cancel is a Workbench card at most 200 x 20 GUI px (the title's
						// button size, inside the brief's 200 x 28 cap), centred at 62 % of the height
						// and kept 8 GUI px clear of the bottom. renderConnecting lifts the picture
						// above it on short windows (it reads this top edge every frame).
						int bw = Math.min(200, Math.max(80, self.width - 24));
						int bh = 20;
						int by = Math.min((int) Math.round(self.height * 0.62) - bh / 2, self.height - bh - 8);
						button.setWidth(bw);
						button.setHeight(bh);
						button.setX((self.width - bw) / 2);
						button.setY(Math.max(0, by));
					}
				}
			}
		} catch (Throwable ignored) {
			// No registration: the stock button, still working.
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originConnecting(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		Screen self = (Screen) (Object) this;
		// The Cancel button's top edge (GUI px); vanilla puts it at height / 4 + 132.
		int cancelTop = self.height / 4 + 132;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget) {
				cancelTop = widget.getY();
				break;
			}
		}
		// Only cancel vanilla when the Origin picture actually drew (fail-soft).
		if (!OriginScreenRenderer.renderConnecting(guiGraphics, this.status, cancelTop)) {
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
