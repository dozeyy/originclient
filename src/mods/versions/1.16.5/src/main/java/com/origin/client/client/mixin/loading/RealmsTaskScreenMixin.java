package com.origin.client.client.mixin.loading;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.realmsclient.gui.screens.RealmsLongRunningMcoTaskScreen;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Realms' "Connecting to the realm..." (and every other Realms long-running task: create, reset, restore)
// is a loading screen with a Cancel button, so it draws the one Origin loading picture like Connecting to a
// server (Will, 2026-10-27: every loading screen one look, with the Workbench Cancel; ported from 1.21.1):
// the task's title as the caption over the sweeping bar, and Cancel registered as the loading button
// (OriginButtonRenderer.setLoadingButton) seated under the picture exactly where ConnectScreenMixin puts it
// (OriginScreenRenderer.connectCancelTop, LOADING_CANCEL_W wide).
//
// Targets (javap, this module's RealmsLongRunningMcoTaskScreen): init()V (declared; adds the one Cancel
// button, and re-runs on every resize), render(PoseStack, int, int, float)V (declared: renderBackground,
// the title at row 3, the error or the animated dots, then RealmsScreen.render for the button), and the
// private volatile `title` / `errorMessage` the task thread sets. On this era the screen DECLARES render, so
// this is a HEAD cancel (1.21.1 adds an override instead). An error message, Main Menu Style = Vanilla, or a
// failed Origin draw leave vanilla's render to run: the stock screen, its button included. The task thread
// (started in the constructor), Escape and the narration are untouched. In the isolated required:false
// loading config: if a target moves, this mixin is skipped and the stock Realms screen shows.
@Mixin(RealmsLongRunningMcoTaskScreen.class)
public class RealmsTaskScreenMixin {
	@Shadow
	private volatile Component title;
	@Shadow
	private volatile Component errorMessage;

	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			Screen self = (Screen) (Object) this;
			boolean seat = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive();
			for (GuiEventListener child : self.children()) {
				if (child instanceof Button) {
					AbstractWidget w = (AbstractWidget) child;
					OriginButtonRenderer.setLoadingButton(w);
					if (seat) {
						// The same seat as Connecting's Cancel: LOADING_CANCEL_W x 20 GUI px, under the picture.
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, self.width - 24));
						w.setWidth(bw);
						w.x = (self.width - bw) / 2;
						w.y = OriginScreenRenderer.connectCancelTop(self.height, w.getHeight());
					}
				}
			}
		} catch (Throwable ignored) {
			// No registration: the stock button where vanilla put it, still working.
		}
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true)
	private void originclient$picture(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (this.errorMessage != null || OriginStyle.vanillaMenusOn(this)
				|| !OriginScreenRenderer.renderConnecting(pose, this.title)) {
			return;   // an error to show, vanilla mode, or the Origin picture failed: vanilla's render
		}
		ci.cancel();
		try {
			// Only the Cancel button on top: the title and the loading dots are the picture's caption and bar now.
			for (GuiEventListener child : ((Screen) (Object) this).children()) {
				if (child instanceof Button) {
					((AbstractWidget) child).render(pose, mouseX, mouseY, partialTick);
				}
			}
		} catch (Throwable ignored) {
			// A widget that fails to draw must not take the screen down (Escape still cancels).
		}
	}
}
