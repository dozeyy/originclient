package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server...": the one loading picture every loading screen shows
// (the opaque flat backdrop, the official logo at 44 %, the bar sweeping, the connection
// status as the caption; OriginScreenRenderer.renderConnecting), matching 1.21.1, with
// the Cancel button as the Workbench card (OriginButtonRenderer.setLoadingButton:
// Minecraft-font parchment label, LOADING_CANCEL_W x 20 GUI px) one picture step under
// the caption (OriginScreenRenderer.connectCancelTop).
//
// 26.x order (javap on 26.1.2/26.2/26.3): Screen.extractRenderStateWithTooltipAndSubtitles
// runs extractBackground in its own stratum, THEN extractRenderState. ConnectScreen's
// extractRenderState calls super (the widgets), then the narration tick (GameNarrator
// "narrator.joining", every 2 s), then ONE GuiGraphicsExtractor.centeredText for the
// status line. The picture is opaque now (the parity spec), so it can no longer go at
// extractRenderState TAIL (it would cover Cancel). Instead:
//   - init TAIL: Cancel is registered as the loading button and seated under the picture;
//   - extractBackground: ConnectScreen declares none, so this mixin ADDS the override and
//     draws the picture there; Cancel then draws on top in the next stratum;
//   - the status centeredText is redirected away while Origin draws the picture.
// Vanilla mode or a failed Origin draw calls super.extractBackground (ScreenBackgroundMixin's
// menu backdrop) and keeps vanilla's status line. If these stop applying (required:false
// config): the stock screen; clicking Cancel and the narration are never touched.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin extends Screen {
	@Shadow
	private Component status;

	private ConnectScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	@Inject(method = "init()V", at = @At("TAIL"))
	private void originclient$seatCancel(CallbackInfo ci) {
		try {
			boolean seat = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive();
			for (GuiEventListener child : this.children()) {
				if (child instanceof Button button) {
					OriginButtonRenderer.setLoadingButton(button);   // the skin itself checks vanilla mode
					if (seat) {
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
			// No registration: the stock button where vanilla put it, still working.
			com.origin.client.OriginClient.LOGGER.warn("[Origin] Connecting: Cancel not restyled", t);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (OriginStyle.vanillaMenusOn(this) || !OriginScreenRenderer.renderConnecting(g, this.status)) {
			super.extractBackground(g, mouseX, mouseY, partialTick);   // vanilla mode, or the picture failed
		}
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void originclient$statusLine(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
		if (!OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive()) {
			return;   // the status is the picture's caption (extractBackground)
		}
		g.centeredText(font, text, x, y, color);
	}
}
