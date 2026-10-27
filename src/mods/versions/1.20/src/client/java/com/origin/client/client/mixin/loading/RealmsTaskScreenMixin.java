package com.origin.client.client.mixin.loading;

import com.mojang.realmsclient.gui.screens.RealmsLongRunningMcoTaskScreen;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.realms.RealmsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Realms' "Connecting to the realm..." (and every other Realms long-running task: create, reset, restore,
// QuickPlay into a realm) is a loading screen with a Cancel button, so it draws the one Origin loading
// picture like Connecting to a server (Will, 2026-10-27: every loading screen one look, with the Workbench
// Cancel): the task's title as the caption over the sweeping bar, and Cancel registered as the loading
// button (OriginButtonRenderer.setLoadingButton) seated under the picture exactly where ConnectScreenMixin
// puts it (OriginScreenRenderer.connectCancelTop).
//
// 1.20 has the OLD screen (javap 1.20 RealmsLongRunningMcoTaskScreen): no layout and no repositionElements;
// init()V builds `cancelOrBackButton` at (width / 2 - 106, row(12), 212 x 20) and the screen DECLARES
// render(GuiGraphics, int, int, float), which draws the title, the dots and `errorMessage`. So: init TAIL
// registers and re-seats that button, and render HEAD (cancellable) draws the Origin picture with only the
// button on top. A task error (error(Component), javap: sets errorMessage, then swaps in a fresh Back button
// at vanilla's spot) is shown by vanilla's own render: the error text is the one thing the picture has no
// place for. Vanilla mode (Main Menu Style = Vanilla) and a failed Origin draw also fall through to vanilla.
// The task thread, Escape and the narration live in the constructor / keyPressed / tick, untouched. In the
// isolated required:false loading config: if a target moves, this mixin is skipped and the stock screen shows.
@Mixin(RealmsLongRunningMcoTaskScreen.class)
public abstract class RealmsTaskScreenMixin extends RealmsScreen {
	@Shadow
	private volatile Component title;

	@Shadow
	private volatile Component errorMessage;

	@Shadow
	private Button cancelOrBackButton;

	private RealmsTaskScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			Button button = this.cancelOrBackButton;
			if (button == null) {
				return;
			}
			OriginButtonRenderer.setLoadingButton(button);
			if (!OriginStyle.vanillaMenusOn(this)) {
				// The same seat as Connecting's Cancel: LOADING_CANCEL_W x 20 GUI px, under the picture.
				int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, this.width - 24));
				button.setWidth(bw);
				button.setX((this.width - bw) / 2);
				button.setY(Math.max(0, OriginScreenRenderer.connectCancelTop(this.height, button.getHeight())));
			}
		} catch (Throwable ignored) {
			// No registration: the stock button at vanilla's spot, still working.
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true)
	private void originclient$originConnecting(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (this.errorMessage != null || OriginStyle.vanillaMenusOn(this)
				|| !OriginScreenRenderer.renderConnecting(guiGraphics, this.title)) {
			return;   // a task error, vanilla mode, or the Origin picture failed: vanilla's own screen
		}
		ci.cancel();
		try {
			// Only the Cancel button on top: the title and the dots are the picture's caption and bar now.
			Button button = this.cancelOrBackButton;
			if (button != null) {
				button.render(guiGraphics, mouseX, mouseY, partialTick);
			}
		} catch (Throwable ignored) {
			// A widget that fails to draw must not take the screen down (Escape still cancels).
		}
	}
}
