package com.origin.client.client.mixin.menus;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.resources.I18n;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// "Connecting to the server..." draws 1.21.1's one loading picture (LoadingLook:
// opaque color.bg, the real logo at 44 % of the height, the plain progress bar
// sweeping, the connection status as its caption), the same picture as every other
// loading screen, then the screen's own Cancel button on top as the Workbench card.
// 1.8.9 has no Realms connecting screen (Realms is retired on 1.8.9's title), so this
// is the only connecting surface.
//
// Target (javap 1.8.9, MCP): GuiConnecting.drawScreen(IIF)V is drawDefaultBackground,
// then drawCenteredString(I18n "connect.connecting" if networkManager == null, else
// "connect.authorizing") at height / 2 - 50, then GuiScreen.drawScreen (buttonList,
// then labelList: GuiConnecting adds no labels). HEAD + cancel draws the same parts
// in Origin's layout; GuiConnecting is not one of the classes OptiFine replaces.
// If this stops applying, the vanilla connect screen shows over the menu backdrop
// (nothing breaks). Any failure here leaves vanilla's draw for that frame.
@Mixin(GuiConnecting.class)
public abstract class GuiConnectingMixin {
	@Shadow
	private NetworkManager networkManager;

	// The caption and what it was built from (no string built per frame).
	@Unique
	private String originclient$status;
	@Unique
	private boolean originclient$authorizing;
	@Unique
	private String originclient$lang;
	// One Gfx for this screen's frames (no allocation per frame).
	@Unique
	private Gfx originclient$gfx;
	// One "hook active" log line per game, so a CI run proves the initGui TAIL applied.
	@Unique
	private static boolean originclient$initLogged;

	// Vanilla anchors Cancel at height/4 + 132 (javap 1.8.9), which on a tall GUI
	// climbs into the middle of the screen (the overlap Will hit joining a world,
	// 2026-10-20 playtest). With the Origin picture up, Cancel is 1.21.1's loading
	// Cancel (Will, 2026-10-27): the Workbench card LOADING_CANCEL_W x 20 GUI px with a
	// parchment Minecraft-font label (OriginButtonRenderer.setLoadingButton), centred one
	// picture step under the caption (OriginScreenRenderer.connectCancelTop); the picture
	// never moves for it. In vanilla mode, 36 px under the vanilla status line's top.
	// Kept on screen. A miss leaves vanilla's layout.
	@Inject(method = "initGui", at = @At("TAIL"))
	private void originclient$clearCancel(CallbackInfo ci) {
		try {
			Object self = this;
			if (!(self instanceof GuiScreenAccessor)) {
				return;
			}
			net.minecraft.client.gui.GuiScreen screen = (net.minecraft.client.gui.GuiScreen) self;
			int h = screen.height;
			boolean origin = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive();
			for (GuiButton b : ((GuiScreenAccessor) self).originclient$buttonList()) {
				if (b.id == 0) {
					if (origin) {
						OriginButtonRenderer.setLoadingButton(b);
						int bw = Math.min(OriginScreenRenderer.LOADING_CANCEL_W, Math.max(80, screen.width - 24));
						b.width = bw;
						b.height = 20;
						b.xPosition = (screen.width - bw) / 2;
						b.yPosition = OriginScreenRenderer.connectCancelTop(h, 20);
					} else {
						b.yPosition = Math.min(Math.max(b.yPosition, h / 2 - 50 + 36), h - 24);
					}
				}
			}
			if (origin && !originclient$initLogged) {
				originclient$initLogged = true;
				com.origin.client.OriginClient.LOGGER.info("[origin] GuiConnecting loading Cancel: hook active");
			}
		} catch (Throwable t) {
			// vanilla's layout
		}
	}

	@Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$originPicture(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		try {
			if (OriginStyle.vanillaMenusOn(this)) {
				return;   // vanilla mode: the stock connect screen
			}
			Object self = this;
			if (!(self instanceof GuiScreenAccessor)) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			boolean authorizing = networkManager != null;
			String lang = mc.gameSettings == null ? null : mc.gameSettings.language;
			if (originclient$status == null || authorizing != originclient$authorizing
					|| !java.util.Objects.equals(lang, originclient$lang)) {
				originclient$authorizing = authorizing;
				originclient$lang = lang;
				originclient$status = I18n.format(authorizing ? "connect.authorizing" : "connect.connecting");
			}
			if (originclient$gfx == null) {
				originclient$gfx = new Gfx();
			}
			Gfx g = originclient$gfx;
			if (!OriginScreenRenderer.renderConnecting(g, originclient$status)) {
				return;   // Origin rendering is off: vanilla draws this frame
			}
			ci.cancel();
			// GuiScreen.drawScreen's own button pass (index loop: no iterator per frame); the registered
			// Cancel draws as the Workbench card, anything else (or a latched card) as vanilla's button.
			List<GuiButton> buttons = ((GuiScreenAccessor) self).originclient$buttonList();
			for (int i = 0; i < buttons.size(); i++) {
				GuiButton b = buttons.get(i);
				boolean hovered = b.visible && mouseX >= b.xPosition && mouseY >= b.yPosition
						&& mouseX < b.xPosition + b.width && mouseY < b.yPosition + b.height;
				if (!b.visible || !OriginButtonRenderer.titleControl(g, b, hovered)) {
					b.drawButton(mc, mouseX, mouseY);
				}
			}
		} catch (Throwable t) {
			// vanilla's screen (if the picture already drew, the frame just lacks its button)
		}
	}
}
