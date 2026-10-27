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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server..." draws the one Origin loading picture (the real logo, the plain bar
// sweeping, the connection status as its caption), the same picture as every loading screen
// (1.21.1's look), and its Cancel is the Workbench loading button: LOADING_CANCEL_W x 20 GUI px, a
// parchment Minecraft-font label, seated one picture step under the caption
// (OriginButtonRenderer.setLoadingButton, OriginScreenRenderer.connectCancelTop).
//
// PER-VERSION (1.21.6 to 1.21.8, deferred GUI): Screen.renderWithTooltip calls renderBackground
// BEFORE render, each in its own stratum, and ConnectScreen declares no renderBackground of its own
// (javap 1.21.6 / 1.21.8), so this mixin ADDS the override: it draws the Origin picture in place of
// the inherited menu backdrop (ScreenBackgroundMixin's panorama + blur), and falls back to super when
// vanilla mode is on or the picture fails. Vanilla's render then runs as usual on top: the Cancel
// button and the narrator's "Joining world" line (GameNarrator.saySystemNow, 2000 ms throttle), so
// nothing has to re-implement either. The only thing dropped from render is vanilla's status line
// (it is the picture's caption): the WrapOperation on the single
// GuiGraphics.drawCenteredString(Font,Component,III)V that render calls.
// In the required:false loading config (defaultRequire 0): if the wrap stops applying, vanilla's
// status line shows over the picture; if the init TAIL stops applying, the stock stone Cancel shows
// at vanilla's spot. Nothing crashes either way.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin extends Screen {
	// The status line vanilla shows ("Connecting to the server...", "Logging in...").
	@Shadow
	private Component status;

	// True when this frame's renderBackground drew the Origin picture (render then drops the status line).
	@Unique
	private boolean originclient$drew;

	private ConnectScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	// Target: ConnectScreen.init()V, TAIL, after vanilla adds the Cancel button (it runs again on
	// every resize, re-registering the rebuilt button). If it stops applying, Cancel draws as the
	// stock stone button at vanilla's spot.
	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			boolean seat = !OriginStyle.vanillaMenusOn(this);
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button button) {
					// The skin checks Main Menu Style itself, so vanilla mode keeps the stock button.
					OriginButtonRenderer.setLoadingButton(button);
					if (seat) {
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, this.width - 24));
						int bh = 20;
						button.setWidth(bw);
						button.setHeight(bh);
						button.setX((this.width - bw) / 2);
						button.setY(Math.max(0, OriginScreenRenderer.connectCancelTop(this.height, bh)));
					}
				}
			}
		} catch (Throwable ignored) {
			// No registration: the stock button, still working.
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		originclient$drew = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.renderConnecting(guiGraphics, this.status);
		if (!originclient$drew) {
			super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);   // vanilla mode, or the picture failed
		}
	}

	// Vanilla's status line: dropped while the Origin picture shows (its caption is the same status).
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$dropStatusLine(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int color,
											 Operation<Void> original) {
		if (!originclient$drew) {
			original.call(guiGraphics, font, text, x, y, color);
		}
	}
}
