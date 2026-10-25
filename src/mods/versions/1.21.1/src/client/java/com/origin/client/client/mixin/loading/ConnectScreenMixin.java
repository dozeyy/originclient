package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
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
// as the title's buttons, at vanilla's 200 x 20 GUI px, which is the title's main
// button size (TitleLayout MAIN_W x BUTTON_H). Without that registration it drew
// as the stock stone button (judge 2026-10-25). The Main Menu Style = Vanilla
// check is in the skin itself, so vanilla mode keeps the stock button. The picture
// is lifted above the button when the window is short (OriginScreenRenderer.renderConnecting).
// In the isolated required:false config: if a target moves, that injection is
// skipped (no skin, or the stock connect screen) and nothing else breaks.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	// The status line vanilla shows ("Connecting to the server...", "Logging in...").
	@Shadow
	private Component status;

	// Target: ConnectScreen.init()V, TAIL, after vanilla adds the Cancel button (it
	// runs again on every resize, re-registering the rebuilt button). If it stops
	// applying, Cancel draws as the stock stone button over the Origin picture.
	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			for (GuiEventListener child : ((Screen) (Object) this).children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setTitleButton(button, null);
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
