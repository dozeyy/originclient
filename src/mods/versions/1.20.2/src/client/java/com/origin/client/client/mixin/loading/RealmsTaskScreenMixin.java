package com.origin.client.client.mixin.loading;

import com.mojang.realmsclient.gui.screens.RealmsLongRunningMcoTaskScreen;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
// Targets (javap 1.20.2 / 1.20.4 / 1.21 and 1.21.1, RealmsLongRunningMcoTaskScreen): repositionElements()V (declared; init() ends by
// calling it and every resize re-runs it, re-centring the layout), and the private volatile `title` the
// task thread updates through setTitle. The screen declares no render(), so this mixin ADDS the override
// (it extends RealmsScreen so super.render is the screen's own inherited draw). Vanilla mode (Main Menu
// Style = Vanilla) and a failed Origin draw both call super.render: the stock screen, Cancel included.
// The task thread, the Escape key and the repeated narration live in the constructor / keyPressed / tick,
// none of which this touches. In the isolated required:false loading config: if a target moves, this
// mixin is skipped and the stock Realms screen shows.
@Mixin(RealmsLongRunningMcoTaskScreen.class)
public abstract class RealmsTaskScreenMixin extends RealmsScreen {
	@Shadow
	private volatile Component title;

	private RealmsTaskScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	@Inject(method = "repositionElements()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			boolean seat = !OriginStyle.vanillaMenusOn(this);
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setLoadingButton(button);
					if (seat) {
						// The same seat as Connecting's Cancel: LOADING_CANCEL_W x 20 GUI px, under the picture.
						// Its height stays vanilla's 20 (1.20.2 has no setHeight, javap), so this fork never sets it.
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, this.width - 24));
						button.setWidth(bw);
						button.setX((this.width - bw) / 2);
						button.setY(Math.max(0, OriginScreenRenderer.connectCancelTop(this.height, button.getHeight())));
					}
				}
			}
		} catch (Throwable ignored) {
			// No registration: the stock button where the layout put it, still working.
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (OriginStyle.vanillaMenusOn(this)
				|| !OriginScreenRenderer.renderConnecting(guiGraphics, this.title)) {
			super.render(guiGraphics, mouseX, mouseY, partialTick);   // vanilla mode, or the Origin picture failed
			return;
		}
		try {
			// Only the Cancel button on top: the title and the loading dots are the picture's caption and bar now.
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button && child instanceof Renderable renderable) {
					renderable.render(guiGraphics, mouseX, mouseY, partialTick);
				}
			}
		} catch (Throwable ignored) {
			// A widget that fails to draw must not take the screen down (Escape still cancels).
		}
	}
}
