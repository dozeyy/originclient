package com.origin.client.client.mixin.loading;

import com.mojang.realmsclient.gui.screens.RealmsLongRunningMcoTaskScreen;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.realms.RealmsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Realms' "Connecting to the realm..." (and every other Realms long-running task: create, reset, restore,
// QuickPlay into a realm) is a loading screen with a Cancel button, so it draws the one Origin loading
// picture like Connecting to a server (1.21.1, Will 2026-10-27: every loading screen one look, with the
// Workbench Cancel): the task's title as the caption over the sweeping bar, and Cancel registered as the
// loading button (OriginButtonRenderer.setLoadingButton) seated exactly where ConnectScreenMixin puts it.
//
// Targets (javap 26.1.2 / 26.2 / 26.3 RealmsLongRunningMcoTaskScreen, identical): repositionElements()V
// (declared; init() builds the layout -- Realms logo, LoadingDotsWidget with the title, Cancel -- and ends by
// calling it, every resize re-runs it), and the private volatile `title` the task thread updates through
// setTitle. The screen declares neither extractBackground nor extractRenderState, so this mixin ADDS both
// overrides (it extends RealmsScreen, so super.* is the inherited Screen draw):
//   - extractBackground: the opaque picture (its own stratum, before the widgets);
//   - extractRenderState: when the picture drew this frame, only the Cancel button (the logo and the dots are
//     the picture's logo, bar and caption now); otherwise the stock widgets.
// Vanilla mode (Main Menu Style = Vanilla) and a failed Origin draw both keep the stock screen. The task thread,
// the Escape key and the repeated narration live in the constructor / keyPressed / tick, none touched here.
// In the isolated required:false loading config: if a target moves, this mixin is skipped, the stock screen shows.
@Mixin(RealmsLongRunningMcoTaskScreen.class)
public abstract class RealmsTaskScreenMixin extends RealmsScreen {
	@Shadow
	private volatile Component title;

	/** The picture drew in this frame's background pass (render thread only). */
	@Unique
	private boolean originclient$pictured;

	private RealmsTaskScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	@Inject(method = "repositionElements()V", at = @At("TAIL"))
	private void originclient$workbenchCancel(CallbackInfo ci) {
		try {
			boolean seat = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive();
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setLoadingButton(button);
					if (seat) {
						// The same seat as Connecting's Cancel: LOADING_CANCEL_W x 20 GUI px, under the picture.
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, this.width - 24));
						int bh = 20;
						button.setWidth(bw);
						button.setHeight(bh);
						button.setX((this.width - bw) / 2);
						button.setY(OriginScreenRenderer.connectCancelTop(this.height, bh));
					}
				}
			}
		} catch (Throwable t) {
			// No registration: the stock button where the layout put it, still working.
			com.origin.client.OriginClient.LOGGER.warn("[Origin] Realms task: Cancel not restyled", t);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		this.originclient$pictured = !OriginStyle.vanillaMenusOn(this)
				&& OriginScreenRenderer.renderConnecting(g, this.title);
		if (!this.originclient$pictured) {
			super.extractBackground(g, mouseX, mouseY, partialTick);   // vanilla mode, or the picture failed
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (!this.originclient$pictured) {
			super.extractRenderState(g, mouseX, mouseY, partialTick);
			return;
		}
		try {
			// Only the Cancel button on top: the title and the loading dots are the picture's caption and bar now.
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button && child instanceof Renderable renderable) {
					renderable.extractRenderState(g, mouseX, mouseY, partialTick);
				}
			}
		} catch (Throwable ignored) {
			// A widget that fails to draw must not take the screen down (Escape still cancels).
		}
	}
}
